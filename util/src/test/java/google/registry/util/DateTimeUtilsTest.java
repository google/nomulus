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

package google.registry.util;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.formatInstant;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;
import static google.registry.util.DateTimeUtils.minusDays;
import static google.registry.util.DateTimeUtils.minusHours;
import static google.registry.util.DateTimeUtils.minusMinutes;
import static google.registry.util.DateTimeUtils.minusMonths;
import static google.registry.util.DateTimeUtils.minusWeeks;
import static google.registry.util.DateTimeUtils.minusYears;
import static google.registry.util.DateTimeUtils.plusDays;
import static google.registry.util.DateTimeUtils.plusHours;
import static google.registry.util.DateTimeUtils.plusMinutes;
import static google.registry.util.DateTimeUtils.plusMonths;
import static google.registry.util.DateTimeUtils.plusWeeks;
import static google.registry.util.DateTimeUtils.plusYears;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DateTimeUtils}. */
class DateTimeUtilsTest {

  private ImmutableList<Instant> sampleInstants =
      ImmutableList.of(
          START_INSTANT, START_INSTANT.plus(1, ChronoUnit.DAYS), END_INSTANT, END_INSTANT);

  @Test
  void testSuccess_earliestOf_instant() {
    assertThat(earliestOf(START_INSTANT, END_INSTANT)).isEqualTo(START_INSTANT);
    assertThat(earliestOf(sampleInstants)).isEqualTo(START_INSTANT);
  }

  @Test
  void testSuccess_latestOf_instant() {
    assertThat(latestOf(START_INSTANT, END_INSTANT)).isEqualTo(END_INSTANT);
    assertThat(latestOf(sampleInstants)).isEqualTo(END_INSTANT);
  }

  @Test
  void testSuccess_isBeforeOrAt() {
    assertThat(isBeforeOrAt(START_INSTANT, START_INSTANT.plus(1, ChronoUnit.DAYS))).isTrue();
    assertThat(isBeforeOrAt(START_INSTANT, START_INSTANT)).isTrue();
    assertThat(isBeforeOrAt(START_INSTANT.plus(1, ChronoUnit.DAYS), START_INSTANT)).isFalse();
  }

  @Test
  void testSuccess_isAtOrAfter() {
    assertThat(isAtOrAfter(START_INSTANT, START_INSTANT.plus(1, ChronoUnit.DAYS))).isFalse();
    assertThat(isAtOrAfter(START_INSTANT, START_INSTANT)).isTrue();
    assertThat(isAtOrAfter(START_INSTANT.plus(1, ChronoUnit.DAYS), START_INSTANT)).isTrue();
  }

  @Test
  void test_plusMonths_worksWithInstants() {
    Instant startDate = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(plusMonths(startDate, 4)).isEqualTo(Instant.parse("2012-06-29T00:00:00Z"));

    Instant startLeapYear = Instant.parse("2012-01-31T00:00:00Z");
    assertThat(plusMonths(startLeapYear, 1)).isEqualTo(Instant.parse("2012-02-29T00:00:00Z"));
  }

  @Test
  void test_minusMonths_worksWithInstants() {
    Instant startDate = Instant.parse("2012-06-29T00:00:00Z");
    assertThat(minusMonths(startDate, 4)).isEqualTo(Instant.parse("2012-02-29T00:00:00Z"));

    Instant startLeapYear = Instant.parse("2012-03-31T00:00:00Z");
    assertThat(minusMonths(startLeapYear, 1)).isEqualTo(Instant.parse("2012-02-29T00:00:00Z"));
  }

  @Test
  void testSuccess_plusYears() {
    Instant startDate = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(plusYears(startDate, 4)).isEqualTo(Instant.parse("2016-02-28T00:00:00Z"));
  }

  @Test
  void testSuccess_minusYears() {
    Instant startDate = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(minusYears(startDate, 4)).isEqualTo(Instant.parse("2008-02-28T00:00:00Z"));
  }

  @Test
  void testSuccess_minusYears_zeroYears() {
    Instant leapDay = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(minusYears(leapDay, 0)).isEqualTo(leapDay);
    assertThat(plusYears(leapDay, 0)).isEqualTo(leapDay);
  }

  @Test
  void testFailure_earliestOfEmpty() {
    assertThrows(IllegalArgumentException.class, () -> earliestOf(ImmutableList.of()));
  }

  @Test
  void testFailure_latestOfEmpty() {
    assertThrows(IllegalArgumentException.class, () -> latestOf(ImmutableList.of()));
  }

  @Test
  void test_formatInstant() {
    assertThat(formatInstant(Instant.parse("2024-03-27T10:15:30.105Z")))
        .isEqualTo("2024-03-27T10:15:30.105Z");
    assertThat(formatInstant(Instant.parse("2024-03-27T10:15:30Z")))
        .isEqualTo("2024-03-27T10:15:30.000Z");
  }

  @Test
  void test_plusMinusWeeksDaysHoursMinutes() {
    Instant time = Instant.parse("2024-03-27T10:15:30.000Z");

    assertThat(plusWeeks(time, 2)).isEqualTo(Instant.parse("2024-04-10T10:15:30.000Z"));
    assertThat(minusWeeks(time, 2)).isEqualTo(Instant.parse("2024-03-13T10:15:30.000Z"));

    assertThat(plusDays(time, 2)).isEqualTo(Instant.parse("2024-03-29T10:15:30.000Z"));
    assertThat(minusDays(time, 2)).isEqualTo(Instant.parse("2024-03-25T10:15:30.000Z"));

    assertThat(plusHours(time, 2)).isEqualTo(Instant.parse("2024-03-27T12:15:30.000Z"));
    assertThat(minusHours(time, 2)).isEqualTo(Instant.parse("2024-03-27T08:15:30.000Z"));

    assertThat(plusMinutes(time, 2)).isEqualTo(Instant.parse("2024-03-27T10:17:30.000Z"));
    assertThat(minusMinutes(time, 2)).isEqualTo(Instant.parse("2024-03-27T10:13:30.000Z"));
  }

}
