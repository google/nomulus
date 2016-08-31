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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link VirtualMetric}. */
@RunWith(JUnit4.class)
public class VirtualMetricTest {

  private final VirtualMetric<String> metric =
      new VirtualMetric<>(
          "/metric",
          "description",
          "vdn",
          ImmutableSet.of(LabelDescriptor.create("label1", "bar")),
          Suppliers.ofInstance(
              ImmutableMap.of(
                  ImmutableList.of("label_value1"), "value1",
                  ImmutableList.of("label_value2"), "value2")),
          String.class);

  @Test
  public void testGetCardinality_afterGetTimestampedValues_returnsLastCardinality() {
    metric.getTimestampedValues();
    assertThat(metric.getCardinality()).isEqualTo(2);
  }

  @Test
  public void testGetCardinality_beforeGetTimestampedValues_returnsZero() {
    assertThat(metric.getCardinality()).isEqualTo(0);
  }

  @Test
  public void testGetTimestampedValues_returnsValues() {
    assertThat(metric.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(
                metric, ImmutableList.of("label_value1"), new Instant(1337), "value1"),
            MetricPoint.create(
                metric, ImmutableList.of("label_value2"), new Instant(1337), "value2"));
  }
}
