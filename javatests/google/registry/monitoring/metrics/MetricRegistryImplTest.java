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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.metrics.MetricSchema.Kind;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link MetricRegistryImpl}.
 *
 * <p>The MetricRegistryImpl is a singleton, so we have to be careful to empty it after every test
 * to maintain a blank slate.
 */
@RunWith(JUnit4.class)
public class MetricRegistryImplTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private final LabelDescriptor label =
      LabelDescriptor.create("test_labelname", "test_labeldescription");

  @After
  public void clearMetrics() {
    ImmutableList<Metric<?>> metrics = MetricRegistryImpl.getDefault().getRegisteredMetrics();

    for (Metric<?> metric : metrics) {
      MetricRegistryImpl.getDefault().unregisterMetric(metric.getMetricSchema().name());
    }
  }

  @Test
  public void testRegisterAndUnregister_tracksRegistrations() {
    assertThat(MetricRegistryImpl.getDefault().getRegisteredMetrics()).isEmpty();

    AbstractMetric<?> metric = mock(AbstractMetric.class);
    MetricRegistryImpl.getDefault().registerMetric("/test/metric", metric);

    assertThat(MetricRegistryImpl.getDefault().getRegisteredMetrics()).containsExactly(metric);

    MetricRegistryImpl.getDefault().unregisterMetric("/test/metric");

    assertThat(MetricRegistryImpl.getDefault().getRegisteredMetrics()).isEmpty();
  }

  @Test
  public void testNewGauge_createsGauge() {
    Metric<?> testGauge =
        MetricRegistryImpl.getDefault()
            .newGauge(
                "/test_metric",
                "test_description",
                "test_valuedisplayname",
                ImmutableSet.of(label),
                new Supplier<ImmutableMap<ImmutableList<String>, Long>>() {
                  @Override
                  public ImmutableMap<ImmutableList<String>, Long> get() {
                    return ImmutableMap.of(ImmutableList.of("foo"), 1L);
                  }
                },
                Long.class);

    assertThat(testGauge.getValueClass()).isSameAs(Long.class);
    assertThat(testGauge.getMetricSchema())
        .isEqualTo(
            MetricSchema.create(
                "/test_metric",
                "test_description",
                "test_valuedisplayname",
                Kind.GAUGE,
                ImmutableSet.of(label)));
  }

  @Test
  public void testNewCounter_createsCounter() {
    IncrementableMetric testCounter =
        MetricRegistryImpl.getDefault()
            .newIncrementableMetric(
                "/test_counter",
                "test_description",
                "test_valuedisplayname",
                ImmutableSet.of(label));

    assertThat(testCounter.getValueClass()).isSameAs(Long.class);
    assertThat(testCounter.getMetricSchema())
        .isEqualTo(
            MetricSchema.create(
                "/test_counter",
                "test_description",
                "test_valuedisplayname",
                Kind.CUMULATIVE,
                ImmutableSet.of(label)));
  }

  @Test
  public void testNewSettableMetric_createsSettableMetric() {
    SettableMetric<Boolean> testMetric =
        MetricRegistryImpl.getDefault()
            .newSettableMetric(
                "/test_metric",
                "test_description",
                "test_valuedisplayname",
                ImmutableSet.of(label),
                Boolean.class);

    assertThat(testMetric.getValueClass()).isSameAs(Boolean.class);
    assertThat(testMetric.getMetricSchema())
        .isEqualTo(
            MetricSchema.create(
                "/test_metric",
                "test_description",
                "test_valuedisplayname",
                Kind.GAUGE,
                ImmutableSet.of(label)));
  }

  @Test
  public void testRegister_duplicateMetric_throwsException() {
    SettableMetric<Boolean> testMetric =
        MetricRegistryImpl.getDefault()
            .newSettableMetric(
                "/test_metric",
                "test_description",
                "test_valuedisplayname",
                ImmutableSet.of(label),
                Boolean.class);
    MetricRegistryImpl.getDefault().registerMetric("/test/metric", testMetric);

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Duplicate metric of same name");
    MetricRegistryImpl.getDefault().registerMetric("/test/metric", testMetric);
  }
}
