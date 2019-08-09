// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.metrics;

import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;

import com.google.common.collect.ImmutableSet;
import google.registry.monitoring.blackbox.metrics.MetricsCollector.ResponseType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link MetricsCollector}.
 */
@RunWith(JUnit4.class)
public class MetricsCollectorTest {

  private final String actionName = "blah12345";
  private final String protocol = "protocol";

  private final MetricsCollector metrics = new MetricsCollector();

  @Before
  public void setUp() {
    metrics.resetMetric();
  }

  @Test
  public void testSuccess_oneRecord() {
    metrics.recordResult(protocol, actionName, ResponseType.SUCCESS, 100);

    assertThat(MetricsCollector.responsesCounter)
        .hasValueForLabels(1, protocol, actionName, ResponseType.SUCCESS.name())
        .and()
        .hasNoOtherValues();
    assertThat(MetricsCollector.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(100), protocol, actionName,
            ResponseType.SUCCESS.name())
        .and()
        .hasNoOtherValues();
  }

  @Test
  public void testSuccess_multipleRecords_same() {
    metrics.recordResult(protocol, actionName, ResponseType.FAILURE, 100);
    metrics.recordResult(protocol, actionName, ResponseType.FAILURE, 200);
    assertThat(MetricsCollector.responsesCounter)
        .hasValueForLabels(2, protocol, actionName, ResponseType.FAILURE.name())
        .and()
        .hasNoOtherValues();
    assertThat(MetricsCollector.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(100, 200), protocol, actionName,
            ResponseType.FAILURE.name())
        .and()
        .hasNoOtherValues();
  }

  @Test
  public void testSuccess_multipleRecords_differentStatus() {
    metrics.recordResult(protocol, actionName, ResponseType.SUCCESS, 100);
    metrics.recordResult(protocol, actionName, ResponseType.FAILURE, 200);
    assertThat(MetricsCollector.responsesCounter)
        .hasValueForLabels(1, protocol, actionName, ResponseType.SUCCESS.name())
        .and()
        .hasValueForLabels(1, protocol, actionName, ResponseType.FAILURE.name())
        .and()
        .hasNoOtherValues();
    assertThat(MetricsCollector.latencyMs)
        .hasDataSetForLabels(ImmutableSet.of(100), protocol, actionName,
            ResponseType.SUCCESS.name())
        .and()
        .hasDataSetForLabels(ImmutableSet.of(200), protocol, actionName,
            ResponseType.FAILURE.name())
        .and()
        .hasNoOtherValues();
  }
}
