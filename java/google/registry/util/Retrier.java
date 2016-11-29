// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.any;
import static com.google.common.math.IntMath.pow;
import static google.registry.util.PredicateUtils.supertypeOf;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import google.registry.config.ConfigModule.Config;
import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import org.joda.time.Duration;

/** Wrapper that does retry with exponential backoff. */
public class Retrier implements Serializable {

  private static final long serialVersionUID = 1167386907195735483L;

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  private final Sleeper sleeper;
  private final int attempts;

  @Inject
  public Retrier(Sleeper sleeper, @Config("transientFailureRetries") int transientFailureRetries) {
    this.sleeper = sleeper;
    checkArgument(transientFailureRetries > 0, "Number of attempts must be positive");
    this.attempts = transientFailureRetries;
  }

  /**
   * Retries a unit of work in the face of transient errors.
   *
   * <p>Retrying is done a fixed number of times, with exponential backoff, if the exception that is
   * thrown is deemed retryable by the predicate. If the error is not considered retryable, or if
   * the thread is interrupted, or if the allowable number of attempts has been exhausted, the
   * original exception is propagated through to the caller.
   *
   * @return <V> the value returned by the {@link Callable}.
   */
  public final <V> V callWithRetry(Callable<V> callable, Predicate<Throwable> isRetryable) {
    int failures = 0;
    while (true) {
      try {
        return callable.call();
      } catch (Throwable e) {
        if (++failures == attempts || !isRetryable.apply(e)) {
          throwIfUnchecked(e);
          throw new RuntimeException(e);
        }
        logger.info(e, "Retrying transient error, attempt " + failures);
        try {
          // Wait 100ms on the first attempt, doubling on each subsequent attempt.
          sleeper.sleep(Duration.millis(pow(2, failures) * 100));
        } catch (InterruptedException e2) {
          // Since we're not rethrowing InterruptedException, set the interrupt state on the thread
          // so the next blocking operation will know to abort the thread.
          Thread.currentThread().interrupt();
          throwIfUnchecked(e);
          throw new RuntimeException(e);
        }
      }
    }
  }

  /**
   * Retries a unit of work in the face of transient errors.
   *
   * <p>Retrying is done a fixed number of times, with exponential backoff, if the exception that is
   * thrown is on a whitelist of retryable errors. If the error is not on the whitelist, or if the
   * thread is interrupted, or if the allowable number of attempts has been exhausted, the original
   * exception is propagated through to the caller.
   *
   * @return <V> the value returned by the {@link Callable}.
   */
  @SafeVarargs
  public final <V> V callWithRetry(
      Callable<V> callable,
      Class<? extends Throwable> retryableError,
      Class<? extends Throwable>... moreRetryableErrors) {
    final Set<Class<?>> retryables =
        new ImmutableSet.Builder<Class<?>>().add(retryableError).add(moreRetryableErrors).build();
    return callWithRetry(callable, new Predicate<Throwable>() {
      @Override
      public boolean apply(Throwable e) {
        return any(retryables, supertypeOf(e.getClass()));
      }});
  }

  // TODO(user): Replace with Throwables.throwIfUnchecked
  private static void throwIfUnchecked(Throwable throwable) {
    if (throwable instanceof RuntimeException) {
      throw (RuntimeException) throwable;
    }
    if (throwable instanceof Error) {
      throw (Error) throwable;
    }
  }
}
