package google.registry.util;

import com.google.common.flogger.FluentLogger;
import java.time.Duration;

/**
 * A helper class to log only if the time elapsed between calls is more than a specified threshold.
 */
public final class StopwatchLogger {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  Duration threshold = Duration.ofMillis(400);
  private final long thresholdNanos;
  private long lastTickNanos;

  public StopwatchLogger() {
    this.thresholdNanos = threshold.toNanos();
    this.lastTickNanos = System.nanoTime();
  }

  public void tick(String message) {
    long currentNanos = System.nanoTime();
    long elapsedNanos = currentNanos - lastTickNanos;

    // Only log if the elapsed time is over the threshold.
    if (elapsedNanos > thresholdNanos) {
      logger.atInfo().log("%s (took %d ms)", message, Duration.ofNanos(elapsedNanos).toMillis());
    }

    this.lastTickNanos = currentNanos;
  }
}
