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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/** A singleton registry of {@link Metric}s. */
@ThreadSafe
public final class MetricRegistryImpl implements MetricRegistry {

  private static final Logger logger = Logger.getLogger(MetricRegistryImpl.class.getName());
  private static final MetricRegistryImpl INSTANCE = new MetricRegistryImpl();

  /** The canonical registry for metrics. The map key is the metric name. */
  private final ConcurrentHashMap<String, Metric<?>> registeredMetrics = new ConcurrentHashMap<>();

  private MetricRegistryImpl() {}

  public static MetricRegistryImpl getDefault() {
    return INSTANCE;
  }

  @Override
  @CanIgnoreReturnValue
  public <V> Metric<V> newGauge(
      String name,
      String description,
      String valueDisplayName,
      ImmutableSet<LabelDescriptor> labels,
      Supplier<ImmutableMap<ImmutableList<String>, V>> metricCallback,
      Class<V> valueClass) {
    VirtualMetric<V> metric =
        new VirtualMetric<>(
            name, description, valueDisplayName, labels, metricCallback, valueClass);
    registerMetric(name, metric);
    logger.info("Registered new callback metric: " + name);

    return metric;
  }

  @Override
  public <V> SettableMetric<V> newSettableMetric(
      String name,
      String description,
      String valueDisplayName,
      ImmutableSet<LabelDescriptor> labels,
      Class<V> valueClass) {
    StoredMetric<V> metric =
        new StoredMetric<>(name, description, valueDisplayName, labels, valueClass);
    registerMetric(name, metric);
    logger.info("Registered new stored metric: " + name);

    return metric;
  }

  @Override
  public IncrementableMetric newIncrementableMetric(
      String name,
      String description,
      String valueDisplayName,
      ImmutableSet<LabelDescriptor> labels) {
    Counter metric = new Counter(name, description, valueDisplayName, labels);
    registerMetric(name, metric);
    logger.info("Registered new counter: " + name);

    return metric;
  }

  @Override
  public ImmutableList<Metric<?>> getRegisteredMetrics() {
    return ImmutableList.copyOf(registeredMetrics.values());
  }

  /**
   * Unregisters a metric.
   *
   * <p>This is a no-op if the metric is not already registered.
   *
   * <p>{@link MetricWriter} implementations should not send unregistered metrics on subsequent
   * write intervals.
   */
  @VisibleForTesting
  void unregisterMetric(String name) {
    registeredMetrics.remove(name);
    logger.info("Unregistered metric: " + name);
  }

  /** Registers a metric. */
  @VisibleForTesting
  void registerMetric(String name, Metric<?> metric) {
    Metric<?> previousMetric = registeredMetrics.putIfAbsent(name, metric);

    checkState(previousMetric == null, "Duplicate metric of same name: %s", name);
  }
}
