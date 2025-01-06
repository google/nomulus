// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.server;

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.TransactionManager.ThrowingRunnable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.util.Optional;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * A lock on some shared resource.
 *
 * <p>Locks are either specific to a tld or global to the entire system, in which case a scope of
 * GLOBAL is used.
 *
 * <p>This is the "barebone" lock implementation, that requires manual locking and unlocking. For
 * safe calls that automatically lock and unlock, see LockHandler.
 */
@Entity
@Table
@IdClass(Lock.LockId.class)
public class Lock extends ImmutableObject implements Serializable {

  private static final long serialVersionUID = 756397280691684645L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * The scope of a lock that is not specific to a single tld.
   *
   * <p>Note: we'd use a null "tld" here for global locks, except Hibernate/Postgres don't allow for
   * null values in primary-key columns.
   */
  static final String GLOBAL = "GLOBAL";

  /** Disposition of locking, for monitoring. */
  enum LockState {
    IN_USE,
    FREE,
    TIMED_OUT
  }

  @VisibleForTesting static LockMetrics lockMetrics = new LockMetrics();

  /** The name of the locked resource. */
  @Transient @Id String lockId;

  /** When the lock can be considered implicitly released. */
  @Column(nullable = false)
  DateTime expirationTime;

  /** When was the lock acquired. Used for logging. */
  @Column(nullable = false)
  DateTime acquiredTime;

  /** The resource name used to create the lock. */
  @Column(nullable = false)
  @Id
  String resourceName;

  /** The tld used to create the lock, or GLOBAL if it's cross-TLD. */
  @Column(nullable = false)
  @Id
  String scope;

  public DateTime getExpirationTime() {
    return expirationTime;
  }

  @PostLoad
  void postLoad() {
    lockId = makeLockId(resourceName, scope);
  }

  /**
   * Create a new {@link Lock} for the given resource name in the specified tld (or in the GLOBAL
   * namespace).
   */
  private static Lock create(
      String resourceName,
      String scope,
      DateTime acquiredTime,
      Duration leaseLength) {
    checkArgument(!Strings.isNullOrEmpty(resourceName), "resourceName cannot be null or empty");
    Lock instance = new Lock();
    // Add the scope to the Lock's id so that it is unique for locks acquiring the same resource
    // across different TLDs.
    instance.lockId = makeLockId(resourceName, scope);
    instance.expirationTime = acquiredTime.plus(leaseLength);
    instance.acquiredTime = acquiredTime;
    instance.resourceName = resourceName;
    instance.scope = scope;
    return instance;
  }

  private static String makeLockId(String resourceName, String scope) {
    return String.format("%s-%s", scope, resourceName);
  }

  record AcquireResult(
      DateTime transactionTime,
      @Nullable Lock existingLock,
      @Nullable Lock newLock,
      LockState lockState) {}

  private static void logAcquireResult(AcquireResult acquireResult) {
    try {
      Lock lock = acquireResult.existingLock();
      DateTime now = acquireResult.transactionTime();
      switch (acquireResult.lockState()) {
        case IN_USE:
          logger.atInfo().log(
              "Existing lock by request is still valid now %s (until %s) lock: %s",
              now, lock.expirationTime, lock.lockId);
          break;
        case TIMED_OUT:
          logger.atInfo().log(
              "Existing lock by request is timed out now %s (was valid until %s) lock: %s",
              now, lock.expirationTime, lock.lockId);
          break;
        case FREE:
          // There was no existing lock
          break;
      }
      Lock newLock = acquireResult.newLock();
      if (acquireResult.newLock() != null) {
        logger.atInfo().log("acquire succeeded %s lock: %s", newLock, newLock.lockId);
      }
    } catch (Throwable e) {
      // We might get here if there is a NullPointerException for example, if AcquireResult wasn't
      // constructed correctly. Simply log it for debugging but continue as if nothing happened
      logger.atWarning().withCause(e).log(
          "Error while logging AcquireResult %s. Continuing.", acquireResult);
    }
  }

  /** Try to acquire a lock. Returns absent if it can't be acquired. */
  public static Optional<Lock> acquire(
      String resourceName, @Nullable String tld, Duration leaseLength) {
    String scope = tld != null ? tld : GLOBAL;
    Callable<AcquireResult> lockAcquirer =
        () -> {
          DateTime now = tm().getTransactionTime();

          // Checking if an unexpired lock still exists - if so, the lock can't be acquired.
          Lock lock =
              tm().loadByKeyIfPresent(VKey.create(Lock.class, new LockId(resourceName, scope)))
                  .orElse(null);
          if (lock != null) {
            logger.atInfo().log(
                "Loaded existing lock: %s for resource: %s", lock.lockId, lock.resourceName);
          }
          LockState lockState;
          if (lock == null) {
            lockState = LockState.FREE;
          } else if (isAtOrAfter(now, lock.expirationTime)) {
            lockState = LockState.TIMED_OUT;
          } else {
            lockState = LockState.IN_USE;
            return new AcquireResult(now, lock, null, lockState);
          }

          Lock newLock = create(resourceName, scope, now, leaseLength);
          tm().put(newLock);

          return new AcquireResult(now, lock, newLock, lockState);
        };
    /*
     * Use REPEATABLE READ to avoid write conflicts with other locks.
     *
     * <p>Since the Lock table is small, Postgresql may choose table scan instead of PK lookup. At
     * the SERIALIZABLE level this can cause conflicts with other locks. There is no way to forbid
     * table scan on a per-query or per-table basis.
     *
     * <p>Using REPEATABLE READ is safe since Lock acquire/release only accesses one row. Note that
     * passing the isolation level to the `transact` method requires that it is not a nested
     * transaction. As of the time of this change this is the case.
     *
     * <p>See b/333537928 for more information.
     */
    AcquireResult acquireResult = tm().transact(TRANSACTION_REPEATABLE_READ, lockAcquirer);

    logAcquireResult(acquireResult);
    lockMetrics.recordAcquire(resourceName, scope, acquireResult.lockState());
    return Optional.ofNullable(acquireResult.newLock());
  }

  /** Release the lock. */
  public void release() {
    // Just use the default clock because we aren't actually doing anything that will use the clock.
    ThrowingRunnable lockReleaser =
        () -> {
          // To release a lock, check that no one else has already obtained it and if not
          // delete it. If the lock in the database was different, then this lock is gone already;
          // this can happen if release() is called around the expiration time and the lock
          // expires underneath us.
          VKey<Lock> key = VKey.create(Lock.class, new LockId(resourceName, scope));
          Lock loadedLock = tm().loadByKeyIfPresent(key).orElse(null);
          if (equals(loadedLock)) {
            // Use deleteIgnoringReadOnly() so that we don't create a commit log entry for deleting
            // the lock.
            logger.atInfo().log("Deleting lock: %s", lockId);
            tm().delete(key);

            lockMetrics.recordRelease(
                resourceName, scope, new Duration(acquiredTime, tm().getTransactionTime()));
          } else {
            logger.atSevere().log(
                "The lock we acquired was transferred to someone else before we"
                    + " released it! Did action take longer than lease length?"
                    + " Our lock: %s, current lock: %s",
                this, loadedLock);
            logger.atInfo().log(
                "Not deleting lock: %s - someone else has it: %s", lockId, loadedLock);
          }
        };
    // See comments in the `acquire` method for this isolation level.
    tm().transact(TRANSACTION_REPEATABLE_READ, lockReleaser);
  }

  static class LockId extends ImmutableObject implements Serializable {

    String resourceName;

    String scope;

    // Required for Hibernate
    @SuppressWarnings("unused")
    private LockId() {}

    LockId(String resourceName, String scope) {
      this.resourceName = checkArgumentNotNull(resourceName, "The resource name cannot be null");
      this.scope = checkArgumentNotNull(scope, "Scope of a lock cannot be null");
    }
  }
}
