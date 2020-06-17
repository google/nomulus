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

package google.registry.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.math.IntMath.pow;
import static google.registry.util.PredicateUtils.supertypeOf;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import java.io.Serializable;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.Duration;

/** Wrapper that does retry with exponential backoff. */
public class Retrier implements Serializable {

  private static final long serialVersionUID = 1167386907195735483L;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Sleeper sleeper;
  private final int attempts;

  /** Holds functions to call whenever the code being retried fails. */
  public interface FailureReporter {

    /**
     * Called after a retriable failure happened.
     *
     * <p>Not called after the final failure, nor if the Throwable thrown isn't "a retriable error".
     *
     * <p>Not called at all if the retrier succeeded on its first attempt.
     */
    void beforeRetry(Throwable thrown, int failures, int maxAttempts);
  }

  @Inject
  public Retrier(Sleeper sleeper, @Named("transientFailureRetries") int transientFailureRetries) {
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
   * original exception is propagated through to the caller. Checked exceptions are wrapped in a
   * RuntimeException, while unchecked exceptions are propagated as-is.
   *
   * @return the value returned by the {@link Callable}.
   */
  public <V> V callWithRetry(Callable<V> callable, Predicate<Throwable> isRetryable) {
    return callWithRetry(callable, LOGGING_FAILURE_REPORTER, isRetryable);
  }

  /**
   * Retries a unit of work in the face of transient errors and returns the result.
   *
   * <p>Retrying is done a fixed number of times, with exponential backoff, if the exception that is
   * thrown is on an allow list of retryable errors. If the error is not on the allow list, or if
   * the thread is interrupted, or if the allowable number of attempts has been exhausted, the
   * original exception is propagated through to the caller. Checked exceptions are wrapped in a
   * RuntimeException, while unchecked exceptions are propagated as-is.
   *
   * <p>Uses a default FailureReporter that logs before each retry.
   *
   * @return the value returned by the {@link Callable}.
   */
  @SafeVarargs
  public final <V> V callWithRetry(
      Callable<V> callable,
      Class<? extends Throwable> retryableError,
      Class<? extends Throwable>... moreRetryableErrors) {
    return callWithRetry(callable, LOGGING_FAILURE_REPORTER, retryableError, moreRetryableErrors);
  }

  /**
   * Retries a unit of work in the face of transient errors, without returning a value.
   *
   * @see #callWithRetry(Callable, Class, Class[])
   */
  @SafeVarargs
  public final void callWithRetry(
      VoidCallable callable,
      Class<? extends Throwable> retryableError,
      Class<? extends Throwable>... moreRetryableErrors) {
    callWithRetry(callable.asCallable(), retryableError, moreRetryableErrors);
  }

  /**
   * Retries a unit of work in the face of transient errors and returns the result.
   *
   * <p>Retrying is done a fixed number of times, with exponential backoff, if the exception that is
   * thrown is on an allow list of retryable errors. If the error is not on the allow list, or if
   * the thread is interrupted, or if the allowable number of attempts has been exhausted, the
   * original exception is propagated through to the caller. Checked exceptions are wrapped in a
   * RuntimeException, while unchecked exceptions are propagated as-is.
   *
   * @return the value returned by the {@link Callable}.
   */
  @SafeVarargs
  public final <V> V callWithRetry(
      Callable<V> callable,
      FailureReporter failureReporter,
      Class<? extends Throwable> retryableError,
      Class<? extends Throwable>... moreRetryableErrors) {
    final Set<Class<?>> retryables =
        new ImmutableSet.Builder<Class<?>>().add(retryableError).add(moreRetryableErrors).build();
    return callWithRetry(
        callable, failureReporter, e -> retryables.stream().anyMatch(supertypeOf(e.getClass())));
  }

  /**
   * Retries a unit of work in the face of transient errors, without returning a value.
   *
   * @see #callWithRetry(Callable, FailureReporter, Class, Class[])
   */
  @SafeVarargs
  public final void callWithRetry(
      VoidCallable callable,
      FailureReporter failureReporter,
      Class<? extends Throwable> retryableError,
      Class<? extends Throwable>... moreRetryableErrors) {
    callWithRetry(callable.asCallable(), failureReporter, retryableError, moreRetryableErrors);
  }

  private <V> V callWithRetry(
      Callable<V> callable, FailureReporter failureReporter, Predicate<Throwable> isRetryable) {
    int failures = 0;
    while (true) {
      try {
        return callable.call();
      } catch (Throwable e) {
        if (++failures == attempts || !isRetryable.test(e)) {
          throwIfUnchecked(e);
          throw new RuntimeException(e);
        }
        failureReporter.beforeRetry(e, failures, attempts);
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

  private static final FailureReporter LOGGING_FAILURE_REPORTER =
      (thrown, failures, maxAttempts) ->
          logger.atInfo().withCause(thrown).log(
              "Retrying transient error, attempt %d/%d", failures, maxAttempts);
}
