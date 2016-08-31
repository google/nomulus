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
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.joda.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link Counter}. */
@RunWith(MockitoJUnitRunner.class)
public class CounterTest {

  @Test
  public void testGetCardinality_reflectsCurrentCardinality() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));
    assertThat(counter.getCardinality()).isEqualTo(0);

    counter.increment("foo");
    assertThat(counter.getCardinality()).isEqualTo(1);
    counter.increment("bar");
    assertThat(counter.getCardinality()).isEqualTo(2);
    counter.increment("foo");
    assertThat(counter.getCardinality()).isEqualTo(2);
  }

  @Test
  public void testIncrementBy_wrongLabelValueCount_throwsException() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(
                LabelDescriptor.create("label1", "bar"), LabelDescriptor.create("label2", "bar")));

    try {
      counter.increment("blah");
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void testIncrement_incrementsValues() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));

    assertThat(counter.getTimestampedValues()).isEmpty();

    // use package-private incrementBy once to set the start timestamp predictably.
    counter.incrementBy(1, new Instant(1337), ImmutableList.of("test_value1"));
    assertThat(counter.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(counter, ImmutableList.of("test_value1"), new Instant(1337), 1L));

    counter.increment("test_value1");
    assertThat(counter.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(counter, ImmutableList.of("test_value1"), new Instant(1337), 2L));
  }

  @Test
  public void testIncrementBy_incrementsValues() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));

    assertThat(counter.getTimestampedValues()).isEmpty();

    counter.incrementBy(1, new Instant(1337), ImmutableList.of("test_value1"));
    assertThat(counter.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(counter, ImmutableList.of("test_value1"), new Instant(1337), 1L));

    counter.set(-10L, new Instant(1337), ImmutableList.of("test_value2"));
    counter.incrementBy(5, new Instant(1337), ImmutableList.of("test_value2"));
    assertThat(counter.getTimestampedValues(new Instant(1337)))
        .containsExactly(
            MetricPoint.create(counter, ImmutableList.of("test_value1"), new Instant(1337), 1L),
            MetricPoint.create(counter, ImmutableList.of("test_value2"), new Instant(1337), -5L));
  }

  @Test
  public void testIncrementBy_negativeOffset_throwsException() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));

    try {
      counter.incrementBy(-1L, "foo");
      fail("Test should not allow non-negative offsets");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("The offset provided must be non-negative");
    }
  }

  @Test
  public void testResetAll_resetsAllValuesAndStartTimestamps() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));

    counter.incrementBy(3, new Instant(1337), ImmutableList.of("foo"));
    counter.incrementBy(5, new Instant(1338), ImmutableList.of("moo"));

    assertThat(counter.getTimestampedValues(new Instant(1400)))
        .containsExactly(
            MetricPoint.create(
                counter, ImmutableList.of("foo"), new Instant(1337), new Instant(1400), 3L),
            MetricPoint.create(
                counter, ImmutableList.of("moo"), new Instant(1338), new Instant(1400), 5L));

    counter.reset(new Instant(1339));

    assertThat(counter.getTimestampedValues(new Instant(1400)))
        .containsExactly(
            MetricPoint.create(
                counter, ImmutableList.of("foo"), new Instant(1339), new Instant(1400), 0L),
            MetricPoint.create(
                counter, ImmutableList.of("moo"), new Instant(1339), new Instant(1400), 0L));
  }

  @Test
  public void testReset_resetsValuesAndStartTimestamps() {
    Counter counter =
        new Counter(
            "/metric",
            "description",
            "vdn",
            ImmutableSet.of(LabelDescriptor.create("label1", "bar")));

    counter.incrementBy(3, new Instant(1337), ImmutableList.of("foo"));
    counter.incrementBy(5, new Instant(1338), ImmutableList.of("moo"));

    assertThat(counter.getTimestampedValues(new Instant(1400)))
        .containsExactly(
            MetricPoint.create(
                counter, ImmutableList.of("foo"), new Instant(1337), new Instant(1400), 3L),
            MetricPoint.create(
                counter, ImmutableList.of("moo"), new Instant(1338), new Instant(1400), 5L));

    counter.reset(new Instant(1339), ImmutableList.of("foo"));

    assertThat(counter.getTimestampedValues(new Instant(1400)))
        .containsExactly(
            MetricPoint.create(
                counter, ImmutableList.of("foo"), new Instant(1339), new Instant(1400), 0L),
            MetricPoint.create(
                counter, ImmutableList.of("moo"), new Instant(1338), new Instant(1400), 5L));
  }
}
