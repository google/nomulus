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

import static com.google.common.base.Preconditions.checkArgument;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.ReadableDuration;

/** Utilities methods and constants related to Joda {@link DateTime} objects. */
public abstract class DateTimeUtils {

  /** The start of the epoch, in a convenient constant. */
  @Deprecated public static final DateTime START_OF_TIME = new DateTime(0, UTC);

  /** The start of the UNIX epoch (which is defined in UTC), in a convenient constant. */
  public static final Instant START_INSTANT = Instant.ofEpochMilli(0);

  /**
   * A date in the far future that we can treat as infinity.
   *
   * <p>This value is (2^63-1)/1000 rounded down. Postgres can store dates as 64 bit microseconds,
   * but Java uses milliseconds, so this is the largest representable date that will survive a
   * round-trip through the database.
   */
  @Deprecated public static final DateTime END_OF_TIME = new DateTime(Long.MAX_VALUE / 1000, UTC);

  /**
   * An instant in the far future that we can treat as infinity.
   *
   * <p>This value is (2^63-1)/1000 rounded down. Postgres can store dates as 64 bit microseconds,
   * but Java uses milliseconds, so this is the largest representable date that will survive a
   * round-trip through the database.
   */
  public static final Instant END_INSTANT = Instant.ofEpochMilli(Long.MAX_VALUE / 1000);

  /**
   * Standard ISO 8601 formatter with millisecond precision in UTC.
   *
   * <p>Example: {@code 2024-03-27T10:15:30.105Z}
   *
   * <p>Handles large/negative years by using a sign prefix if necessary, compatible with {@link
   * Instant#parse}.
   */
  private static final DateTimeFormatter ISO_8601_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendValue(ChronoField.YEAR, 4, 10, SignStyle.NORMAL)
          .appendPattern("-MM-dd'T'HH:mm:ss.SSS'Z'")
          .toFormatter()
          .withZone(ZoneOffset.UTC);

  /** A formatter that produces lowercase, filename-safe and job-name-safe timestamps. */
  public static final DateTimeFormatter LOWERCASE_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd't'HH-mm-ss'z'").withZone(ZoneOffset.UTC);

  /** Formats an {@link Instant} to an ISO-8601 string. */
  public static String formatInstant(Instant instant) {
    return ISO_8601_FORMATTER.format(instant);
  }

  /**
   * Parses an ISO-8601 string to an {@link Instant}.
   *
   * <p>This method is lenient and supports both strings with and without millisecond precision
   * (e.g. {@code 2024-03-27T10:15:30Z} and {@code 2024-03-27T10:15:30.105Z}). It also supports
   * large years (e.g. {@code 294247-01-10T04:00:54.775Z}).
   */
  public static Instant parseInstant(String timestamp) {
    try {
      // Try the standard millisecond precision format first.
      return Instant.from(ISO_8601_FORMATTER.parse(timestamp));
    } catch (DateTimeParseException e) {
      // Fall back to the standard ISO instant parser which handles varied precision.
      return Instant.parse(timestamp);
    }
  }

  /** Returns the earliest of a number of given {@link DateTime} instances. */
  public static DateTime earliestOf(DateTime first, DateTime... rest) {
    return earliestDateTimeOf(Lists.asList(first, rest));
  }

  /** Returns the earliest of a number of given {@link Instant} instances. */
  public static Instant earliestOf(Instant first, Instant... rest) {
    return earliestOf(Lists.asList(first, rest));
  }

  /** Returns the earliest element in a {@link DateTime} iterable. */
  public static DateTime earliestDateTimeOf(Iterable<DateTime> dates) {
    checkArgument(!Iterables.isEmpty(dates));
    return Ordering.<DateTime>natural().min(dates);
  }

  /** Returns the earliest element in a {@link Instant} iterable. */
  public static Instant earliestOf(Iterable<Instant> instants) {
    checkArgument(!Iterables.isEmpty(instants));
    return Ordering.<Instant>natural().min(instants);
  }

  /** Returns the latest of a number of given {@link DateTime} instances. */
  public static DateTime latestOf(DateTime first, DateTime... rest) {
    return latestDateTimeOf(Lists.asList(first, rest));
  }

  /** Returns the latest of a number of given {@link Instant} instances. */
  public static Instant latestOf(Instant first, Instant... rest) {
    return latestOf(Lists.asList(first, rest));
  }

  /** Returns the latest element in a {@link DateTime} iterable. */
  public static DateTime latestDateTimeOf(Iterable<DateTime> dates) {
    checkArgument(!Iterables.isEmpty(dates));
    return Ordering.<DateTime>natural().max(dates);
  }

  /** Returns the latest element in a {@link Instant} iterable. */
  public static Instant latestOf(Iterable<Instant> instants) {
    checkArgument(!Iterables.isEmpty(instants));
    return Ordering.<Instant>natural().max(instants);
  }

  /** Returns whether the first {@link DateTime} is equal to or earlier than the second. */
  public static boolean isBeforeOrAt(DateTime timeToCheck, DateTime timeToCompareTo) {
    return !timeToCheck.isAfter(timeToCompareTo);
  }

  /** Converts a Joda-Time Duration to a java.time.Duration. */
  @Nullable
  public static java.time.Duration toJavaDuration(@Nullable ReadableDuration duration) {
    return duration == null ? null : java.time.Duration.ofMillis(duration.getMillis());
  }

  /** Converts a java.time.Duration to a Joda-Time Duration. */
  @Nullable
  public static org.joda.time.Duration toJodaDuration(@Nullable java.time.Duration duration) {
    return duration == null ? null : org.joda.time.Duration.millis(duration.toMillis());
  }

  /** Returns whether the first {@link Instant} is equal to or earlier than the second. */
  public static boolean isBeforeOrAt(Instant timeToCheck, Instant timeToCompareTo) {
    return !timeToCheck.isAfter(timeToCompareTo);
  }

  /** Returns whether the first {@link DateTime} is equal to or later than the second. */
  public static boolean isAtOrAfter(DateTime timeToCheck, DateTime timeToCompareTo) {
    return !timeToCheck.isBefore(timeToCompareTo);
  }

  /** Returns whether the first {@link Instant} is equal to or later than the second. */
  public static boolean isAtOrAfter(Instant timeToCheck, Instant timeToCompareTo) {
    return !timeToCheck.isBefore(timeToCompareTo);
  }

  /**
   * Adds years to a date, in the {@code Duration} sense of semantic years. Use this instead of
   * {@link DateTime#plusYears} to ensure that we never end up on February 29.
   */
  public static DateTime plusYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.plusYears(1).plusYears(years - 1);
  }

  /**
   * Adds years to a date, in the {@code Duration} sense of semantic years. Use this instead of
   * {@link java.time.ZonedDateTime#plusYears} to ensure that we never end up on February 29.
   */
  public static Instant plusYears(Instant now, int years) {
    checkArgument(years >= 0);
    return (years == 0)
        ? now
        : now.atZone(ZoneOffset.UTC).plusYears(1).plusYears(years - 1).toInstant();
  }

  /** Adds months to a date. */
  public static Instant plusMonths(Instant now, int months) {
    checkArgument(months >= 0);
    return now.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
  }

  /** Subtracts months from a date. */
  public static Instant minusMonths(Instant now, int months) {
    checkArgument(months >= 0);
    return now.atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
  }

  /**
   * Subtracts years from a date, in the {@code Duration} sense of semantic years. Use this instead
   * of {@link DateTime#minusYears} to ensure that we never end up on February 29.
   */
  public static DateTime minusYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.minusYears(1).minusYears(years - 1);
  }

  /**
   * Subtracts years from a date, in the {@code Duration} sense of semantic years. Use this instead
   * of {@link java.time.ZonedDateTime#minusYears} to ensure that we never end up on February 29.
   */
  public static Instant minusYears(Instant now, long years) {
    checkArgument(years >= 0);
    return (years == 0)
        ? now
        : now.atZone(ZoneOffset.UTC).minusYears(1).minusYears(years - 1).toInstant();
  }

  /**
   * @deprecated Use {@link #plusYears(DateTime, int)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static DateTime leapSafeAddYears(DateTime now, int years) {
    return plusYears(now, years);
  }

  /**
   * @deprecated Use {@link #minusYears(DateTime, int)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static DateTime leapSafeSubtractYears(DateTime now, int years) {
    return minusYears(now, years);
  }

  public static Date toSqlDate(LocalDate localDate) {
    return new Date(localDate.toDateTimeAtStartOfDay().getMillis());
  }

  public static LocalDate toLocalDate(Date date) {
    return new LocalDate(date.getTime(), UTC);
  }

  /** Converts a java.time.LocalDate to a Joda-Time LocalDate. */
  public static LocalDate toJodaLocalDate(java.time.LocalDate localDate) {
    return new LocalDate(localDate.getYear(), localDate.getMonthValue(), localDate.getDayOfMonth());
  }

  /** Converts an Instant to a Joda-Time LocalDate in UTC. */
  public static LocalDate toJodaLocalDate(Instant instant) {
    return new LocalDate(instant.toEpochMilli(), UTC);
  }

  /** Convert a joda {@link DateTime} to a java.time {@link Instant}, null-safe. */
  @Nullable
  public static Instant toInstant(@Nullable DateTime dateTime) {
    return (dateTime == null) ? null : Instant.ofEpochMilli(dateTime.getMillis());
  }

  /** Convert a java.time {@link Instant} to a joda {@link DateTime}, null-safe. */
  @Nullable
  public static DateTime toDateTime(@Nullable Instant instant) {
    return (instant == null) ? null : new DateTime(instant.toEpochMilli(), UTC);
  }

  /** Convert a java.time {@link java.time.Instant} to a joda {@link org.joda.time.Instant}. */
  @Nullable
  public static org.joda.time.Instant toJodaInstant(@Nullable java.time.Instant instant) {
    return (instant == null) ? null : org.joda.time.Instant.ofEpochMilli(instant.toEpochMilli());
  }

  public static Instant plusHours(Instant instant, long hours) {
    return instant.plus(hours, ChronoUnit.HOURS);
  }

  public static Instant minusHours(Instant instant, long hours) {
    return instant.minus(hours, ChronoUnit.HOURS);
  }

  public static Instant plusMinutes(Instant instant, long minutes) {
    return instant.plus(minutes, ChronoUnit.MINUTES);
  }

  public static Instant minusMinutes(Instant instant, long minutes) {
    return instant.minus(minutes, ChronoUnit.MINUTES);
  }

  public static Instant plusDays(Instant instant, int days) {
    return instant.atZone(ZoneOffset.UTC).plusDays(days).toInstant();
  }

  public static Instant minusDays(Instant instant, int days) {
    return instant.atZone(ZoneOffset.UTC).minusDays(days).toInstant();
  }
}
