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

package google.registry.model.common;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusYears;
import static google.registry.util.DateTimeUtils.plusYears;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TimeOfYear}. */
class TimeOfYearTest {

  private static final Instant february28 = Instant.parse("2012-02-28T01:02:03.0Z");
  private static final Instant february29 = Instant.parse("2012-02-29T01:02:03.0Z");
  private static final Instant march1 = Instant.parse("2012-03-01T01:02:03.0Z");

  @Test
  void testSuccess_fromInstant() {
    // We intentionally don't allow leap years in TimeOfYear, so February 29 should be February 28.
    assertThat(TimeOfYear.fromInstant(february28)).isEqualTo(TimeOfYear.fromInstant(february29));
    assertThat(TimeOfYear.fromInstant(february29)).isNotEqualTo(TimeOfYear.fromInstant(march1));
  }

  @Test
  void testSuccess_nextAfter() {
    // This should be lossless because atOrAfter includes an exact match.
    assertThat(TimeOfYear.fromInstant(march1).getNextInstanceAtOrAfter(march1)).isEqualTo(march1);
    // This should be a year later because we stepped forward a millisecond
    assertThat(
            TimeOfYear.fromInstant(march1)
                .getNextInstanceAtOrAfter(march1.plus(Duration.ofMillis(1))))
        .isEqualTo(plusYears(march1, 1));
  }

  @Test
  void testSuccess_nextBefore() {
    // This should be lossless because beforeOrAt includes an exact match.
    assertThat(TimeOfYear.fromInstant(march1).getLastInstanceBeforeOrAt(march1)).isEqualTo(march1);
    // This should be a year earlier because we stepped backward a millisecond
    assertThat(
            TimeOfYear.fromInstant(march1)
                .getLastInstanceBeforeOrAt(march1.minus(Duration.ofMillis(1))))
        .isEqualTo(minusYears(march1, 1));
  }

  @Test
  void testSuccess_getInstancesInRange_closed() {
    Instant startDate = Instant.parse("2012-05-01T00:00:00Z");
    Instant endDate = Instant.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(Instant.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<Instant> expected =
        ImmutableSet.of(
            Instant.parse("2012-05-01T00:00:00Z"),
            Instant.parse("2013-05-01T00:00:00Z"),
            Instant.parse("2014-05-01T00:00:00Z"),
            Instant.parse("2015-05-01T00:00:00Z"),
            Instant.parse("2016-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closed(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_openClosed() {
    Instant startDate = Instant.parse("2012-05-01T00:00:00Z");
    Instant endDate = Instant.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(Instant.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<Instant> expected =
        ImmutableSet.of(
            Instant.parse("2013-05-01T00:00:00Z"),
            Instant.parse("2014-05-01T00:00:00Z"),
            Instant.parse("2015-05-01T00:00:00Z"),
            Instant.parse("2016-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.openClosed(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_closedOpen() {
    Instant startDate = Instant.parse("2012-05-01T00:00:00Z");
    Instant endDate = Instant.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(Instant.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<Instant> expected =
        ImmutableSet.of(
            Instant.parse("2012-05-01T00:00:00Z"),
            Instant.parse("2013-05-01T00:00:00Z"),
            Instant.parse("2014-05-01T00:00:00Z"),
            Instant.parse("2015-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closedOpen(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_open() {
    Instant startDate = Instant.parse("2012-05-01T00:00:00Z");
    Instant endDate = Instant.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(Instant.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<Instant> expected =
        ImmutableSet.of(
            Instant.parse("2013-05-01T00:00:00Z"),
            Instant.parse("2014-05-01T00:00:00Z"),
            Instant.parse("2015-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.open(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_normalizedLowerBound() {
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(START_INSTANT);
    ImmutableSet<Instant> expected =
        ImmutableSet.of(START_INSTANT, plusYears(START_INSTANT, 1), plusYears(START_INSTANT, 2));
    assertThat(timeOfYear.getInstancesInRange(Range.atMost(plusYears(START_INSTANT, 2))))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_normalizedUpperBound() {
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(END_INSTANT);
    ImmutableSet<Instant> expected =
        ImmutableSet.of(minusYears(END_INSTANT, 2), minusYears(END_INSTANT, 1), END_INSTANT);
    assertThat(timeOfYear.getInstancesInRange(Range.atLeast(minusYears(END_INSTANT, 2))))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesOfTimeOfYearInRange_empty() {
    Instant startDate = Instant.parse("2012-05-01T00:00:00Z");
    Instant endDate = Instant.parse("2013-02-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromInstant(Instant.parse("2012-03-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closed(startDate, endDate))).isEmpty();
  }
}
