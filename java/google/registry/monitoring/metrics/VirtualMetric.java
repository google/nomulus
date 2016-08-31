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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import java.util.Map.Entry;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.Instant;

/**
 * A metric whose value is computed at sample-time.
 *
 * <p>This pattern works well for gauge-like metrics, such as CPU usage, memory usage, and file
 * descriptor counts.
 *
 * <p>The {@link MetricPoint#interval()} of values of instances of this metric will always have a
 * start time equal to the end time, since the metric value represents a point-in-time snapshot with
 * no relationship to prior values.
 */
@ThreadSafe
public final class VirtualMetric<V> extends AbstractMetric<V> {

  private final Supplier<ImmutableMap<ImmutableList<String>, V>> valuesSupplier;

  /**
   * Local cache of the count of values so that we don't have to evaluate the callback function to
   * get the metric's cardinality.
   */
  private volatile int cardinality;

  VirtualMetric(
      String name,
      String description,
      String valueDisplayName,
      ImmutableSet<LabelDescriptor> labels,
      Supplier<ImmutableMap<ImmutableList<String>, V>> valuesSupplier,
      Class<V> valueClass) {
    super(name, description, valueDisplayName, Kind.GAUGE, labels, valueClass);

    this.valuesSupplier = valuesSupplier;
  }

  /**
   * Returns a snapshot of the metric's values. This will evaluate the stored callback function. The
   * timestamp for each MetricPoint will be the current time.
   */
  @Override
  public ImmutableList<MetricPoint<V>> getTimestampedValues() {
    return getTimestampedValues(Instant.now());
  }

  /**
   * Returns the cached value of the cardinality of this metric. The cardinality is computed when
   * the metric is evaluated. If the metric has never been evaluated, the cardinality is zero.
   */
  @Override
  public int getCardinality() {
    return cardinality;
  }

  @VisibleForTesting
  ImmutableList<MetricPoint<V>> getTimestampedValues(Instant timestamp) {
    ImmutableMap<ImmutableList<String>, V> values = valuesSupplier.get();

    ImmutableList.Builder<MetricPoint<V>> metricPoints = ImmutableList.builder();
    for (Entry<ImmutableList<String>, V> entry : values.entrySet()) {
      metricPoints.add(
          MetricPoint.create(this, entry.getKey(), timestamp, timestamp, entry.getValue()));
    }

    cardinality = values.size();
    return metricPoints.build();
  }
}
