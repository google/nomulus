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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

public abstract class DateTimeUtils {

  /** The start of the UNIX epoch (which is defined in UTC), in a convenient constant. */
  public static final Instant START_INSTANT = Instant.ofEpochMilli(0);

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
   * <p>Note: We deliberately strip the leading {@code +} sign from the formatted year field if
   * present. While standard ISO 8601 specifies that years with more than 4 digits should be
   * prefixed with a {@code +} sign, W3C XML Schema 1.0 (which our EPP RDE XSD uses) strictly
   * forbids leading plus signs in {@code xsd:dateTime} strings. Suppressing the plus sign ensures
   * our generated XML continues to pass strict XSD validation for large years (e.g. {@code
   * 294247-01-10T04:00:54.775Z}).
   */
  private static final DateTimeFormatter ISO_8601_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** A formatter that produces lowercase, filename-safe and job-name-safe timestamps. */
  public static final DateTimeFormatter LOWERCASE_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd't'HH-mm-ss'z'").withZone(ZoneOffset.UTC);

  /** Formats an {@link Instant} to an ISO-8601 string. */
  public static String formatInstant(Instant instant) {
    String formatted = ISO_8601_FORMATTER.format(instant);
    return formatted.startsWith("+") ? formatted.substring(1) : formatted;
  }

  /**
   * Parses an ISO-8601 string to an {@link Instant}.
   *
   * <p>This method is lenient and supports both strings with and without millisecond precision
   * (e.g. {@code 2024-03-27T10:15:30Z} and {@code 2024-03-27T10:15:30.105Z}). It also supports
   * large years (e.g. {@code 294247-01-10T04:00:54.775Z}).
   */
  public static Instant parseInstant(String timestamp) {
    if (!timestamp.startsWith("+") && !timestamp.startsWith("-")) {
      int dashIndex = timestamp.indexOf('-');
      if (dashIndex > 4) {
        timestamp = "+" + timestamp;
      }
    }
    try {
      // Try the standard millisecond precision format first.
      return Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timestamp));
    } catch (DateTimeParseException e) {
      // Fall back to the standard ISO instant parser which handles varied precision.
      return Instant.parse(timestamp);
    }
  }

  /** Returns the earliest of a number of given {@link Instant} instances. */
  public static Instant earliestOf(Instant first, Instant... rest) {
    return earliestOf(Lists.asList(first, rest));
  }

  /** Returns the earliest element in an {@link Instant} iterable. */
  public static Instant earliestOf(Iterable<Instant> instants) {
    checkArgument(!Iterables.isEmpty(instants));
    return Ordering.<Instant>natural().min(instants);
  }

  /** Returns the latest of a number of given {@link Instant} instances. */
  public static Instant latestOf(Instant first, Instant... rest) {
    return latestOf(Lists.asList(first, rest));
  }

  /** Returns the latest element in an {@link Instant} iterable. */
  public static Instant latestOf(Iterable<Instant> instants) {
    checkArgument(!Iterables.isEmpty(instants));
    return Ordering.<Instant>natural().max(instants);
  }

  /** Returns whether the first {@link Instant} is equal to or earlier than the second. */
  public static boolean isBeforeOrAt(Instant timeToCheck, Instant timeToCompareTo) {
    return !timeToCheck.isAfter(timeToCompareTo);
  }

  /** Returns whether the first {@link Instant} is equal to or later than the second. */
  public static boolean isAtOrAfter(Instant timeToCheck, Instant timeToCompareTo) {
    return !timeToCheck.isBefore(timeToCompareTo);
  }

  /**
   * Adds years to a date, in the {@code Duration} sense of semantic years. Use this instead of
   * {@link java.time.OffsetDateTime#plusYears} to ensure that we never end up on February 29.
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
   * of {@link java.time.OffsetDateTime#minusYears} to ensure that we never end up on February 29.
   */
  public static Instant minusYears(Instant now, long years) {
    checkArgument(years >= 0);
    return (years == 0)
        ? now
        : now.atZone(ZoneOffset.UTC).minusYears(1).minusYears(years - 1).toInstant();
  }

  /** Converts an Instant to a java.time.LocalDate in UTC. */
  public static LocalDate toLocalDate(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).toLocalDate();
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

  public static Instant plusWeeks(Instant instant, int weeks) {
    return instant.atZone(ZoneOffset.UTC).plusWeeks(weeks).toInstant();
  }

  public static Instant minusWeeks(Instant instant, int weeks) {
    return instant.atZone(ZoneOffset.UTC).minusWeeks(weeks).toInstant();
  }

  public static Instant plusDays(Instant instant, long days) {
    return instant.atZone(ZoneOffset.UTC).plusDays(days).toInstant();
  }

  public static Instant minusDays(Instant instant, long days) {
    return instant.atZone(ZoneOffset.UTC).minusDays(days).toInstant();
  }
}
