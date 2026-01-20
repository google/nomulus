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

package google.registry.monitoring.whitebox;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.monitoring.metrics.LabelDescriptor;
import com.google.monitoring.metrics.MetricRegistry;
import com.google.monitoring.metrics.MetricRegistryImpl;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/** Exposes JVM metrics. */
@Singleton
class JvmMetrics {

  private static final ImmutableSet<LabelDescriptor> TYPE_LABEL_SET =
      ImmutableSet.of(LabelDescriptor.create("type", "Memory type (e.g., heap, non_heap)"));
  private final MemoryMXBean memoryMxBean;

  @Inject
  JvmMetrics() {
    this(ManagementFactory.getMemoryMXBean());
  }

  // Constructor for testing
  JvmMetrics(MemoryMXBean memoryMxBean) {
    this.memoryMxBean = memoryMxBean;
  }
  /** Registers JVM gauges with the default registry. */
  void register() {
    MetricRegistry registry = MetricRegistryImpl.getDefault();

    registry.newGauge(
        "/jvm/memory/used",
        "Current memory usage in bytes",
        "bytes",
        TYPE_LABEL_SET,
        (Supplier<ImmutableMap<ImmutableList<String>, Long>>) this::getUsedMemory,
        Long.class);

    registry.newGauge(
        "/jvm/memory/committed",
        "Committed memory in bytes",
        "bytes",
        TYPE_LABEL_SET,
        (Supplier<ImmutableMap<ImmutableList<String>, Long>>) this::getCommittedMemory,
        Long.class);

    registry.newGauge(
        "/jvm/memory/max",
        "Maximum memory in bytes",
        "bytes",
        TYPE_LABEL_SET,
        (Supplier<ImmutableMap<ImmutableList<String>, Long>>) this::getMaxMemory,
        Long.class);
  }


  ImmutableMap<ImmutableList<String>, Long> getUsedMemory() {
    MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();

    return ImmutableMap.of(
        ImmutableList.of("heap"), heapUsage.getUsed(),
        ImmutableList.of("non_heap"), nonHeapUsage.getUsed());
  }

  ImmutableMap<ImmutableList<String>, Long> getCommittedMemory() {
    MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();

    return ImmutableMap.of(
        ImmutableList.of("heap"), heapUsage.getCommitted(),
        ImmutableList.of("non_heap"), nonHeapUsage.getCommitted());
  }

  ImmutableMap<ImmutableList<String>, Long> getMaxMemory() {
    MemoryUsage heapUsage = memoryMxBean.getHeapMemoryUsage();
    MemoryUsage nonHeapUsage = memoryMxBean.getNonHeapMemoryUsage();

    return ImmutableMap.of(
        ImmutableList.of("heap"), heapUsage.getMax(),
        ImmutableList.of("non_heap"), nonHeapUsage.getMax());
  }
}
