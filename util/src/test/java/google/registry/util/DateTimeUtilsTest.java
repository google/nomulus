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
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.earliestDateTimeOf;
import static google.registry.util.DateTimeUtils.earliestOf;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestDateTimeOf;
import static google.registry.util.DateTimeUtils.latestOf;
import static google.registry.util.DateTimeUtils.minusMonths;
import static google.registry.util.DateTimeUtils.minusYears;
import static google.registry.util.DateTimeUtils.parseInstant;
import static google.registry.util.DateTimeUtils.plusMonths;
import static google.registry.util.DateTimeUtils.plusYears;
import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toInstant;
import static google.registry.util.DateTimeUtils.toJodaInstant;
import static google.registry.util.DateTimeUtils.toLocalDate;
import static google.registry.util.DateTimeUtils.toSqlDate;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import java.sql.Date;
import java.time.Instant;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DateTimeUtils}. */
class DateTimeUtilsTest {

  private ImmutableList<DateTime> sampleDates =
      ImmutableList.of(START_OF_TIME, START_OF_TIME.plusDays(1), END_OF_TIME, END_OF_TIME);

  @Test
  void testSuccess_earliestOf() {
    assertThat(earliestOf(START_OF_TIME, END_OF_TIME)).isEqualTo(START_OF_TIME);
    assertThat(earliestDateTimeOf(sampleDates)).isEqualTo(START_OF_TIME);
  }

  @Test
  void testSuccess_earliestOf_instant() {
    assertThat(earliestOf(START_INSTANT, END_INSTANT)).isEqualTo(START_INSTANT);
    assertThat(earliestOf(ImmutableList.of(START_INSTANT, END_INSTANT))).isEqualTo(START_INSTANT);
  }

  @Test
  void testSuccess_latestOf() {
    assertThat(latestOf(START_OF_TIME, END_OF_TIME)).isEqualTo(END_OF_TIME);
    assertThat(latestDateTimeOf(sampleDates)).isEqualTo(END_OF_TIME);
  }

  @Test
  void testSuccess_latestOf_instant() {
    assertThat(latestOf(START_INSTANT, END_INSTANT)).isEqualTo(END_INSTANT);
    assertThat(latestOf(ImmutableList.of(START_INSTANT, END_INSTANT))).isEqualTo(END_INSTANT);
  }

  @Test
  void testSuccess_isBeforeOrAt() {
    assertThat(isBeforeOrAt(START_OF_TIME, START_OF_TIME.plusDays(1))).isTrue();
    assertThat(isBeforeOrAt(START_OF_TIME, START_OF_TIME)).isTrue();
    assertThat(isBeforeOrAt(START_OF_TIME.plusDays(1), START_OF_TIME)).isFalse();
  }

  @Test
  void testSuccess_isAtOrAfter() {
    assertThat(isAtOrAfter(START_OF_TIME, START_OF_TIME.plusDays(1))).isFalse();
    assertThat(isAtOrAfter(START_OF_TIME, START_OF_TIME)).isTrue();
    assertThat(isAtOrAfter(START_OF_TIME.plusDays(1), START_OF_TIME)).isTrue();
  }

  @Test
  void testSuccess_plusYears() {
    DateTime startDate = DateTime.parse("2012-02-29T00:00:00Z");
    assertThat(startDate.plusYears(4)).isEqualTo(DateTime.parse("2016-02-29T00:00:00Z"));
    assertThat(plusYears(startDate, 4)).isEqualTo(DateTime.parse("2016-02-28T00:00:00Z"));
  }

  @Test
  void test_plusYears_worksWithInstants() {
    Instant startDate = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(plusYears(startDate, 4)).isEqualTo(Instant.parse("2016-02-28T00:00:00Z"));
  }

  @Test
  void testSuccess_minusYears() {
    DateTime startDate = DateTime.parse("2012-02-29T00:00:00Z");
    assertThat(startDate.minusYears(4)).isEqualTo(DateTime.parse("2008-02-29T00:00:00Z"));
    assertThat(minusYears(startDate, 4)).isEqualTo(DateTime.parse("2008-02-28T00:00:00Z"));
  }

  @Test
  void test_minusYears_worksWithInstants() {
    Instant startDate = Instant.parse("2012-02-29T00:00:00Z");
    assertThat(minusYears(startDate, 4)).isEqualTo(Instant.parse("2008-02-28T00:00:00Z"));
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
  void testSuccess_minusYears_zeroYears() {
    DateTime leapDay = DateTime.parse("2012-02-29T00:00:00Z");
    assertThat(leapDay.minusYears(0)).isEqualTo(leapDay);
  }

  @Test
  void testFailure_earliestOfEmpty() {
    assertThrows(IllegalArgumentException.class, () -> earliestDateTimeOf(ImmutableList.of()));
  }

  @Test
  void testFailure_latestOfEmpty() {
    assertThrows(IllegalArgumentException.class, () -> latestDateTimeOf(ImmutableList.of()));
  }

  @Test
  void testSuccess_toSqlDate() {
    LocalDate localDate = LocalDate.parse("2020-02-29");
    assertThat(toSqlDate(localDate)).isEqualTo(Date.valueOf("2020-02-29"));
  }

  @Test
  void testSuccess_toLocalDate() {
    Date date = Date.valueOf("2020-02-29");
    assertThat(toLocalDate(date)).isEqualTo(LocalDate.parse("2020-02-29"));
  }

  @Test
  void test_startOfTimeConstants_areTheSame() {
    assertThat(toInstant(START_OF_TIME)).isEqualTo(START_INSTANT);
    assertThat(toDateTime(START_INSTANT)).isEqualTo(START_OF_TIME);
    assertThat(toInstant(toDateTime(START_INSTANT))).isEqualTo(START_INSTANT);
    assertThat(toDateTime(toInstant(START_OF_TIME))).isEqualTo(START_OF_TIME);
  }

  @Test
  void test_endOfTimeConstants_areTheSame() {
    assertThat(toInstant(END_OF_TIME)).isEqualTo(END_INSTANT);
    assertThat(toDateTime(END_INSTANT)).isEqualTo(END_OF_TIME);
    assertThat(toInstant(toDateTime(END_INSTANT))).isEqualTo(END_INSTANT);
    assertThat(toDateTime(toInstant(END_OF_TIME))).isEqualTo(END_OF_TIME);
  }

  @Test
  void test_instantConversionMethods_workCorrectly() {
    assertThat(toDateTime(Instant.parse("2024-03-27T10:15:30.105Z")))
        .isEqualTo(DateTime.parse("2024-03-27T10:15:30.105Z"));
    assertThat(toInstant(DateTime.parse("2024-03-27T10:15:30.105Z")))
        .isEqualTo(Instant.parse("2024-03-27T10:15:30.105Z"));
    assertThat(toJodaInstant(Instant.parse("2024-03-27T10:15:30.105Z")))
        .isEqualTo(org.joda.time.Instant.parse("2024-03-27T10:15:30.105Z"));
    assertThat(parseInstant("2024-03-27T10:15:30.105Z"))
        .isEqualTo(Instant.parse("2024-03-27T10:15:30.105Z"));
    assertThat(parseInstant("2024-03-27T10:15:30Z"))
        .isEqualTo(Instant.parse("2024-03-27T10:15:30Z"));
  }
}
