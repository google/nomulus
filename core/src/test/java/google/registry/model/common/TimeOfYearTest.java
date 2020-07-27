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
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TimeOfYear}. */
class TimeOfYearTest {

  private static final DateTime february28 = DateTime.parse("2012-02-28T01:02:03.0Z");
  private static final DateTime february29 = DateTime.parse("2012-02-29T01:02:03.0Z");
  private static final DateTime march1 = DateTime.parse("2012-03-01T01:02:03.0Z");

  @Test
  void testSuccess_fromDateTime() {
    // We intentionally don't allow leap years in TimeOfYear, so February 29 should be February 28.
    assertThat(TimeOfYear.fromDateTime(february28)).isEqualTo(TimeOfYear.fromDateTime(february29));
    assertThat(TimeOfYear.fromDateTime(february29)).isNotEqualTo(TimeOfYear.fromDateTime(march1));
  }

  @Test
  void testSuccess_nextAfter() {
    // This should be lossless because atOrAfter includes an exact match.
    assertThat(TimeOfYear.fromDateTime(march1).getNextInstanceAtOrAfter(march1)).isEqualTo(march1);
    // This should be a year later because we stepped forward a millisecond
    assertThat(TimeOfYear.fromDateTime(march1).getNextInstanceAtOrAfter(march1.plusMillis(1)))
        .isEqualTo(march1.plusYears(1));
  }

  @Test
  void testSuccess_nextBefore() {
    // This should be lossless because beforeOrAt includes an exact match.
    assertThat(TimeOfYear.fromDateTime(march1).getLastInstanceBeforeOrAt(march1)).isEqualTo(march1);
    // This should be a year earlier because we stepped backward a millisecond
    assertThat(TimeOfYear.fromDateTime(march1).getLastInstanceBeforeOrAt(march1.minusMillis(1)))
        .isEqualTo(march1.minusYears(1));
  }

  @Test
  void testSuccess_getInstancesInRange_closed() {
    DateTime startDate = DateTime.parse("2012-05-01T00:00:00Z");
    DateTime endDate = DateTime.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(DateTime.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<DateTime> expected = ImmutableSet.of(
        DateTime.parse("2012-05-01T00:00:00Z"),
        DateTime.parse("2013-05-01T00:00:00Z"),
        DateTime.parse("2014-05-01T00:00:00Z"),
        DateTime.parse("2015-05-01T00:00:00Z"),
        DateTime.parse("2016-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closed(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_openClosed() {
    DateTime startDate = DateTime.parse("2012-05-01T00:00:00Z");
    DateTime endDate = DateTime.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(DateTime.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<DateTime> expected = ImmutableSet.of(
        DateTime.parse("2013-05-01T00:00:00Z"),
        DateTime.parse("2014-05-01T00:00:00Z"),
        DateTime.parse("2015-05-01T00:00:00Z"),
        DateTime.parse("2016-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.openClosed(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_closedOpen() {
    DateTime startDate = DateTime.parse("2012-05-01T00:00:00Z");
    DateTime endDate = DateTime.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(DateTime.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<DateTime> expected = ImmutableSet.of(
        DateTime.parse("2012-05-01T00:00:00Z"),
        DateTime.parse("2013-05-01T00:00:00Z"),
        DateTime.parse("2014-05-01T00:00:00Z"),
        DateTime.parse("2015-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closedOpen(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_open() {
    DateTime startDate = DateTime.parse("2012-05-01T00:00:00Z");
    DateTime endDate = DateTime.parse("2016-05-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(DateTime.parse("2012-05-01T00:00:00Z"));
    ImmutableSet<DateTime> expected = ImmutableSet.of(
        DateTime.parse("2013-05-01T00:00:00Z"),
        DateTime.parse("2014-05-01T00:00:00Z"),
        DateTime.parse("2015-05-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.open(startDate, endDate)))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_normalizedLowerBound() {
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(START_OF_TIME);
    ImmutableSet<DateTime> expected =
        ImmutableSet.of(START_OF_TIME, START_OF_TIME.plusYears(1), START_OF_TIME.plusYears(2));
    assertThat(timeOfYear.getInstancesInRange(Range.atMost(START_OF_TIME.plusYears(2))))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesInRange_normalizedUpperBound() {
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(END_OF_TIME);
    ImmutableSet<DateTime> expected =
        ImmutableSet.of(END_OF_TIME.minusYears(2), END_OF_TIME.minusYears(1), END_OF_TIME);
    assertThat(timeOfYear.getInstancesInRange(Range.atLeast(END_OF_TIME.minusYears(2))))
        .containsExactlyElementsIn(expected);
  }

  @Test
  void testSuccess_getInstancesOfTimeOfYearInRange_empty() {
    DateTime startDate = DateTime.parse("2012-05-01T00:00:00Z");
    DateTime endDate = DateTime.parse("2013-02-01T00:00:00Z");
    TimeOfYear timeOfYear = TimeOfYear.fromDateTime(DateTime.parse("2012-03-01T00:00:00Z"));
    assertThat(timeOfYear.getInstancesInRange(Range.closed(startDate, endDate))).isEmpty();
  }
}
