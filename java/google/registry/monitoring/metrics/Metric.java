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

import com.google.common.collect.ImmutableList;

/**
 * A Metric which stores timestamped values.
 *
 * <p>This is a read-only view.
 */
public interface Metric<V> {

  /**
   * Returns the latest {@link MetricPoint} instances for every label-value combination tracked for
   * this metric.
   */
  ImmutableList<MetricPoint<V>> getTimestampedValues();

  /** Returns the count of values being stored with this metric. */
  int getCardinality();

  /** Returns the schema of this metric. */
  MetricSchema getMetricSchema();

  /**
   * Returns the type for the value of this metric, which would otherwise be erased at compile-time.
   * This is useful for implementors of {@link MetricWriter}.
   */
  Class<V> getValueClass();
}
