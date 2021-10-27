// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.request.lock;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.UncheckedExecutionException;
import google.registry.model.server.Lock;
import google.registry.util.AppEngineTimeLimiter;
import google.registry.util.Clock;
import google.registry.util.RequestStatusChecker;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/** Implementation of {@link LockHandler} that uses the datastore lock. */
public class LockHandlerImpl implements LockHandler {

  private static final long serialVersionUID = 5746905970040002524L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Fudge factor to make sure we kill threads before a lock actually expires. */
  private static final Duration LOCK_TIMEOUT_FUDGE = Duration.standardSeconds(5);

  private final RequestStatusChecker requestStatusChecker;
  private final Clock clock;

  @Inject
  public LockHandlerImpl(RequestStatusChecker requestStatusChecker, Clock clock) {
    this.requestStatusChecker = requestStatusChecker;
    this.clock = clock;
  }

  /**
   * Acquire one or more locks and execute a Void {@link Callable}.
   *
   * <p>Thread will be killed if it doesn't complete before the lease expires.
   *
   * <p>Note that locks are specific either to a given tld or to the entire system (in which case
   * tld should be passed as null).
   *
   * @return whether all locks were acquired and the callable was run.
   */
  @Override
  public boolean executeWithLocks(
      final Callable<Void> callable,
      @Nullable String tld,
      Duration leaseLength,
      String... lockNames) {
    return executeWithLockAcquirer(callable, tld, leaseLength, this::acquire, lockNames);
  }

  /**
   * Acquire one or more locks using only Cloud SQL and execute a Void {@link Callable}.
   *
   * <p>Thread will be killed if it doesn't complete before the lease expires.
   *
   * <p>Note that locks are specific either to a given tld or to the entire system (in which case
   * tld should be passed as null).
   *
   * <p>This method exists so that Beam pipelines can acquire / load / release locks.
   *
   * @return whether all locks were acquired and the callable was run.
   */
  @Override
  public boolean executeWithSqlLocks(
      final Callable<Void> callable,
      @Nullable String tld,
      Duration leaseLength,
      String... lockNames) {
    return executeWithLockAcquirer(callable, tld, leaseLength, this::acquireSql, lockNames);
  }

  private boolean executeWithLockAcquirer(
      final Callable<Void> callable,
      @Nullable String tld,
      Duration leaseLength,
      LockAcquirer lockAcquirer,
      String... lockNames) {
    DateTime startTime = clock.nowUtc();
    String sanitizedTld = Strings.emptyToNull(tld);
    try {
      return AppEngineTimeLimiter.create()
          .callWithTimeout(
              new LockingCallable(callable, lockAcquirer, sanitizedTld, leaseLength, lockNames),
              leaseLength.minus(LOCK_TIMEOUT_FUDGE).getMillis(),
              TimeUnit.MILLISECONDS);
    } catch (ExecutionException | UncheckedExecutionException e) {
      // Unwrap the execution exception and throw its root cause.
      Throwable cause = e.getCause();
      if (cause instanceof TimeoutException) {
        throw new RuntimeException(
            String.format(
                "Execution on locks '%s' for TLD '%s' timed out after %s; started at %s",
                Joiner.on(", ").join(lockNames),
                Optional.ofNullable(sanitizedTld).orElse("(null)"),
                new Duration(startTime, clock.nowUtc()),
                startTime),
            cause);
      }
      throwIfUnchecked(cause);
      throw new RuntimeException(cause);
    } catch (Exception e) {
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    }
  }

  /** Allows injection of mock Lock in tests. */
  @VisibleForTesting
  Optional<Lock> acquire(String lockName, @Nullable String tld, Duration leaseLength) {
    return Lock.acquire(lockName, tld, leaseLength, requestStatusChecker, true);
  }

  @VisibleForTesting
  Optional<Lock> acquireSql(String lockName, @Nullable String tld, Duration leaseLength) {
    return Lock.acquireSql(lockName, tld, leaseLength, requestStatusChecker, true);
  }

  private interface LockAcquirer {
    Optional<Lock> acquireLock(String lockName, @Nullable String tld, Duration leaseLength);
  }

  /** A {@link Callable} that acquires and releases a lock around a delegate {@link Callable}. */
  private static class LockingCallable implements Callable<Boolean> {
    final Callable<Void> delegate;
    final LockAcquirer lockAcquirer;
    @Nullable final String tld;
    final Duration leaseLength;
    final Set<String> lockNames;

    LockingCallable(
        Callable<Void> delegate,
        LockAcquirer lockAcquirer,
        String tld,
        Duration leaseLength,
        String... lockNames) {
      checkArgument(leaseLength.isLongerThan(LOCK_TIMEOUT_FUDGE));
      this.delegate = delegate;
      this.lockAcquirer = lockAcquirer;
      this.tld = tld;
      this.leaseLength = leaseLength;
      // Make sure we join locks in a fixed (lexicographical) order to avoid deadlock.
      this.lockNames = ImmutableSortedSet.copyOf(lockNames);
    }

    @Override
    public Boolean call() throws Exception {
      Set<Lock> acquiredLocks = new HashSet<>();
      try {
        for (String lockName : lockNames) {
          Optional<Lock> lock = lockAcquirer.acquireLock(lockName, tld, leaseLength);
          if (!lock.isPresent()) {
            logger.atInfo().log("Couldn't acquire lock named: %s for TLD %s.", lockName, tld);
            return false;
          }
          logger.atInfo().log("Acquired lock: %s", lock);
          acquiredLocks.add(lock.get());
        }
        delegate.call();
        return true;
      } finally {
        for (Lock lock : acquiredLocks) {
          lock.release();
          logger.atInfo().log("Released lock: %s", lock);
        }
      }
    }
  }
}
