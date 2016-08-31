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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link MetricReporter}. */
@RunWith(MockitoJUnitRunner.class)
public class MetricReporterTest {

  @Mock MetricRegistry registry;
  @Mock Metric<?> metric;
  @Mock ThreadFactory threadFactory;
  @Mock MetricWriter writer;
  @Mock MetricSchema metricSchema;
  @Mock BlockingQueue<Optional<ImmutableList<MetricPoint<?>>>> writeQueue;

  @Test
  public void testRunOneIteration_enqueuesBatch() throws Exception {
    Metric<?> metric =
        new Counter("/name", "description", "vdn", ImmutableSet.<LabelDescriptor>of());
    when(registry.getRegisteredMetrics()).thenReturn(ImmutableList.of(metric, metric));
    MetricReporter reporter = new MetricReporter(writer, 10L, threadFactory, registry, writeQueue);

    reporter.runOneIteration();

    verify(writeQueue).offer(Optional.of(ImmutableList.<MetricPoint<?>>of()));
  }

  @Test
  public void testShutDown_enqueuesBatchAndPoisonPill() throws Exception {
    // Set up a registry with no metrics.
    when(registry.getRegisteredMetrics()).thenReturn(ImmutableList.<Metric<?>>of());
    MetricReporter reporter =
        spy(new MetricReporter(writer, 10L, threadFactory, registry, writeQueue));

    reporter.shutDown();

    verify(reporter).runOneIteration();
    InOrder interactions = Mockito.inOrder(writeQueue);
    interactions.verify(writeQueue).offer(Optional.of(ImmutableList.<MetricPoint<?>>of()));
    interactions.verify(writeQueue).offer(Optional.<ImmutableList<MetricPoint<?>>>absent());
  }
}
