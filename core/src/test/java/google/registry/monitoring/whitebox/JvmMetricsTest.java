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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.monitoring.metrics.MetricRegistry;
import com.google.monitoring.metrics.MetricRegistryImpl;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link JvmMetrics}. */
class JvmMetricsTests {

  private MetricRegistry registry;
  private JvmMetrics jvmMetrics;

  private MemoryMXBean mockMemoryMXBean = mock(MemoryMXBean.class);

  private static final MemoryUsage HEAP_USAGE = new MemoryUsage(100, 200, 500, 1000);
  private static final MemoryUsage NON_HEAP_USAGE = new MemoryUsage(50, 100, 250, 500);

  @BeforeEach
  public void setUp() {
    registry = MetricRegistryImpl.getDefault();

    when(mockMemoryMXBean.getHeapMemoryUsage()).thenReturn(HEAP_USAGE);
    when(mockMemoryMXBean.getNonHeapMemoryUsage()).thenReturn(NON_HEAP_USAGE);


    jvmMetrics = new JvmMetrics(mockMemoryMXBean);
  }

  @Test
  public void metricsRegistered() {
    // We expect 3 metrics to be registered by JvmMetrics
    assertThat(registry.getRegisteredMetrics()).hasSize(3);
    // We can't easily verify their names, but we know they are VirtualMetrics
    for (var metric : registry.getRegisteredMetrics()) {
      assertThat(metric).isInstanceOf(com.google.monitoring.metrics.VirtualMetric.class);
    }
  }



  @Test
  public void testGetUsedMemory() {
    ImmutableMap<ImmutableList<String>, Long> values = jvmMetrics.getUsedMemory();
    assertThat(values).containsExactly(
        ImmutableList.of("heap"), 100L,
        ImmutableList.of("non_heap"), 50L);
  }
}
