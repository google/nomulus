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
