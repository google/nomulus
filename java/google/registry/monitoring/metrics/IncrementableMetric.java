// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.monitoring.metrics;

/**
 * A {@link Metric} which can be incremented.
 *
 * <p>This is a view into a {@link Counter} to provide compile-time checking to disallow arbitrarily
 * setting the metric, which is useful for metrics which should be monotonically increasing.
 */
public interface IncrementableMetric extends Metric<Long> {

  /**
   * Increments a metric by 1 for the given label values.
   *
   * <p>Use this method rather than {@link IncrementableMetric#incrementBy(long, String...)} if the
   * increment value is zero, as it will be slightly more performant.
   *
   * <p>If the metric is undefined for given label values, it will be incremented from zero.
   *
   * <p>The metric's timestamp will be updated to the current time for the given label values.
   *
   * <p>The count of {@code labelValues} must be equal to the underlying metric's count of labels.
   */
  void increment(String... labelValues);

  /**
   * Increments a metric by the given non-negative offset for the given label values.
   *
   * <p>If the metric is undefined for given label values, it will be incremented from zero.
   *
   * <p>The metric's timestamp will be updated to the current time for the given label values.
   *
   * <p>The count of {@code labelValues} must be equal to the underlying metric's count of labels.
   *
   * @throws IllegalArgumentException if the offset is negative.
   */
  void incrementBy(long offset, String... labelValues);

  /**
   * Resets the value and start timestamp of the metric for the given label values.
   *
   * <p>This is useful if the counter is tracking a value that is reset as part of a retrying
   * transaction, for example.
   */
  void reset(String... labelValues);

  /**
   * Atomically resets the value and start timestamp of the metric for all label values.
   *
   * <p>This is useful if the counter is tracking values that are reset as part of a retrying
   * transaction, for example.
   */
  void reset();
}
