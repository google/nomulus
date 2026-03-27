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
import java.sql.Date;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;

/** Utilities methods and constants related to Joda {@link DateTime} objects. */
public abstract class DateTimeUtils {

  /** The start of the epoch, in a convenient constant. */
  @Deprecated public static final DateTime START_OF_TIME = new DateTime(0, DateTimeZone.UTC);

  /** The start of the UNIX epoch (which is defined in UTC), in a convenient constant. */
  public static final Instant START_INSTANT = Instant.ofEpochMilli(0);

  /**
   * A date in the far future that we can treat as infinity.
   *
   * <p>This value is (2^63-1)/1000 rounded down. Postgres can store dates as 64 bit microseconds,
   * but Java uses milliseconds, so this is the largest representable date that will survive a
   * round-trip through the database.
   */
  @Deprecated
  public static final DateTime END_OF_TIME = new DateTime(Long.MAX_VALUE / 1000, DateTimeZone.UTC);

  /**
   * An instant in the far future that we can treat as infinity.
   *
   * <p>This value is (2^63-1)/1000 rounded down. Postgres can store dates as 64 bit microseconds,
   * but Java uses milliseconds, so this is the largest representable date that will survive a
   * round-trip through the database.
   */
  public static final Instant END_INSTANT = Instant.ofEpochMilli(Long.MAX_VALUE / 1000);

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
    return latestOf(Lists.asList(first, rest));
  }

  /** Returns the latest element in a {@link DateTime} iterable. */
  public static DateTime latestOf(Iterable<DateTime> dates) {
    checkArgument(!Iterables.isEmpty(dates));
    return Ordering.<DateTime>natural().max(dates);
  }

  /** Returns whether the first {@link DateTime} is equal to or earlier than the second. */
  public static boolean isBeforeOrAt(DateTime timeToCheck, DateTime timeToCompareTo) {
    return !timeToCheck.isAfter(timeToCompareTo);
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
  @Deprecated
  public static DateTime leapSafeAddYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.plusYears(1).plusYears(years - 1);
  }

  /**
   * Adds years to a date, in the {@code Duration} sense of semantic years. Use this instead of
   * {@link java.time.ZonedDateTime#plusYears} to ensure that we never end up on February 29.
   */
  public static Instant leapSafeAddYears(Instant now, long years) {
    checkArgument(years >= 0);
    return (years == 0)
        ? now
        : now.atZone(ZoneOffset.UTC).plusYears(1).plusYears(years - 1).toInstant();
  }

  /**
   * Subtracts years from a date, in the {@code Duration} sense of semantic years. Use this instead
   * of {@link DateTime#minusYears} to ensure that we never end up on February 29.
   */
  @Deprecated
  public static DateTime leapSafeSubtractYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.minusYears(1).minusYears(years - 1);
  }

  /**
   * Subtracts years from a date, in the {@code Duration} sense of semantic years. Use this instead
   * of {@link java.time.ZonedDateTime#minusYears} to ensure that we never end up on February 29.
   */
  public static Instant leapSafeSubtractYears(Instant now, int years) {
    checkArgument(years >= 0);
    return (years == 0)
        ? now
        : now.atZone(ZoneOffset.UTC).minusYears(1).minusYears(years - 1).toInstant();
  }

  public static Date toSqlDate(LocalDate localDate) {
    return new Date(localDate.toDateTimeAtStartOfDay().getMillis());
  }

  public static LocalDate toLocalDate(Date date) {
    return new LocalDate(date.getTime(), DateTimeZone.UTC);
  }

  /** Convert a joda {@link DateTime} to a java.time {@link Instant}, null-safe. */
  @Nullable
  public static Instant toInstant(@Nullable DateTime dateTime) {
    return (dateTime == null) ? null : Instant.ofEpochMilli(dateTime.getMillis());
  }

  /** Convert a java.time {@link Instant} to a joda {@link DateTime}, null-safe. */
  @Nullable
  public static DateTime toDateTime(@Nullable Instant instant) {
    return (instant == null) ? null : new DateTime(instant.toEpochMilli(), DateTimeZone.UTC);
  }

  public static Instant plusYears(Instant instant, int years) {
    return instant.atZone(ZoneOffset.UTC).plusYears(years).toInstant();
  }

  public static Instant plusDays(Instant instant, int days) {
    return instant.atZone(ZoneOffset.UTC).plusDays(days).toInstant();
  }

  public static Instant minusDays(Instant instant, int days) {
    return instant.atZone(ZoneOffset.UTC).minusDays(days).toInstant();
  }
}
