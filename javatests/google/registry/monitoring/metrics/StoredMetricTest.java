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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link StoredMetric}. */
@RunWith(JUnit4.class)
public class StoredMetricTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  @Test
  public void testGetCardinality_reflectsCurrentCardinality() {
    StoredMetric<Boolean> smallMetric =
        new StoredMetric<>(
            "/metric", "description", "vdn", ImmutableSet.<LabelDescriptor>of(), Boolean.class);
    assertThat(smallMetric.getCardinality()).isEqualTo(0);

    smallMetric.set(true);

    assertThat(smallMetric.getCardinality()).isEqualTo(1);

    StoredMetric<Boolean> dimensionalMetric =
        new StoredMetric<>(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("foo", "bar")),
            Boolean.class);

    dimensionalMetric.set(true, "test_value1");
    dimensionalMetric.set(true, "test_value2");

    assertThat(dimensionalMetric.getCardinality()).isEqualTo(2);
  }

  @Test
  public void testSet_wrongNumberOfLabels_throwsException() {
    StoredMetric<Boolean> dimensionalMetric =
        new StoredMetric<>(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(
                LabelDescriptor.create("label1", "bar"), LabelDescriptor.create("label2", "bar")),
            Boolean.class);

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("The count of labelValues must be equal to");
    dimensionalMetric.set(true, "foo");
  }

  @Test
  public void testSet_setsValue() {
    StoredMetric<Boolean> metric =
        new StoredMetric<>(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")),
            Boolean.class);

    assertThat(metric.getTimestampedValues()).isEmpty();

    metric.set(true, ImmutableList.of("test_value1"));
    assertThat(metric.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(metric, ImmutableList.of("test_value1"), new Instant(1337), true));

    metric.set(false, ImmutableList.of("test_value1"));
    metric.set(true, ImmutableList.of("test_value2"));
    assertThat(metric.getTimestampedValues(new Instant(1338)))
        .containsExactly(
            MetricPoint.create(metric, ImmutableList.of("test_value1"), new Instant(1338), false),
            MetricPoint.create(metric, ImmutableList.of("test_value2"), new Instant(1338), true));
  }
}
