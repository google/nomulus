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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.concurrent.ThreadSafe;
import org.joda.time.Instant;

/**
 * A metric which is stateful.
 *
 * <p>The values are stored and set over time. This metric is generally suitable for state
 * indicators, such as indicating that a server is in a RUNNING state or in a STOPPED state.
 *
 * <p>See {@link Counter} for a subclass which is suitable for stateful incremental values.
 *
 * <p>The {@link MetricPoint#interval()} of values of instances of this metric will always have a
 * start time equal to the end time, since the metric value represents a point-in-time snapshot with
 * no relationship to prior values.
 */
@ThreadSafe
public class StoredMetric<V> extends AbstractMetric<V> implements SettableMetric<V> {

  private static final String LABEL_COUNT_ERROR =
      "The count of labelValues must be equal to the underlying "
          + "MetricDescriptor's count of labels.";

  private final ConcurrentHashMap<ImmutableList<String>, V> values = new ConcurrentHashMap<>();

  StoredMetric(
      String name,
      String description,
      String valueDisplayName,
      ImmutableSet<LabelDescriptor> labels,
      Class<V> valueClass) {
    super(name, description, valueDisplayName, Kind.GAUGE, labels, valueClass);
  }

  @VisibleForTesting
  final void set(V value, ImmutableList<String> labelValues) {
    this.values.put(labelValues, value);
  }

  @Override
  public final void set(V value, String... labelValues) {
    checkArgument(labelValues.length == this.getMetricSchema().labels().size(), LABEL_COUNT_ERROR);

    set(value, ImmutableList.copyOf(labelValues));
  }

  /**
   * Returns a snapshot of the metric's values. The timestamp of each MetricPoint will be the last
   * modification time for that tuple of label values.
   */
  @Override
  public final ImmutableList<MetricPoint<V>> getTimestampedValues() {
    return getTimestampedValues(Instant.now());
  }

  @Override
  public final int getCardinality() {
    return values.size();
  }

  @VisibleForTesting
  final ImmutableList<MetricPoint<V>> getTimestampedValues(Instant timestamp) {
    ImmutableList.Builder<MetricPoint<V>> timestampedValues = new Builder<>();
    for (Entry<ImmutableList<String>, V> entry : values.entrySet()) {
      timestampedValues.add(
          MetricPoint.create(this, entry.getKey(), timestamp, timestamp, entry.getValue()));
    }

    return timestampedValues.build();
  }
}
