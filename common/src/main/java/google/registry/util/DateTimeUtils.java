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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.TimeZone;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/** Utilities methods and constants related to Joda {@link DateTime} objects. */
public class DateTimeUtils {

  /** The start of the epoch, in a convenient constant. */
  public static final DateTime START_OF_TIME = new DateTime(0, DateTimeZone.UTC);

  /**
   * A date in the far future that we can treat as infinity.
   *
   * <p>This value is (2^63-1)/1000 rounded down. AppEngine stores dates as 64 bit microseconds, but
   * Java uses milliseconds, so this is the largest representable date that will survive a
   * round-trip through Datastore.
   */
  public static final DateTime END_OF_TIME = new DateTime(Long.MAX_VALUE / 1000, DateTimeZone.UTC);

  /** Returns the earliest of a number of given {@link DateTime} instances. */
  public static DateTime earliestOf(DateTime first, DateTime... rest) {
    return earliestOf(Lists.asList(first, rest));
  }

  /** Returns the earliest element in a {@link DateTime} iterable. */
  public static DateTime earliestOf(Iterable<DateTime> dates) {
    checkArgument(!Iterables.isEmpty(dates));
    return Ordering.<DateTime>natural().min(dates);
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

  /** Returns whether the first {@link DateTime} is equal to or later than the second. */
  public static boolean isAtOrAfter(DateTime timeToCheck, DateTime timeToCompareTo) {
    return !timeToCheck.isBefore(timeToCompareTo);
  }

  /**
   * Adds years to a date, in the {@code Duration} sense of semantic years. Use this instead of
   * {@link DateTime#plusYears} to ensure that we never end up on February 29.
   */
  public static DateTime leapSafeAddYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.plusYears(1).plusYears(years - 1);
  }

  /**
   * Subtracts years from a date, in the {@code Duration} sense of semantic years. Use this instead
   * of {@link DateTime#minusYears} to ensure that we never end up on February 29.
   */
  public static DateTime leapSafeSubtractYears(DateTime now, int years) {
    checkArgument(years >= 0);
    return years == 0 ? now : now.minusYears(1).minusYears(years - 1);
  }

  /**
   * Converts a Joda {@link DateTime} object to an equivalent java.time {@link ZonedDateTime}
   * object.
   */
  public static ZonedDateTime toZonedDateTime(DateTime dateTime) {
    java.time.Instant instant = java.time.Instant.ofEpochMilli(dateTime.getMillis());
    return ZonedDateTime.ofInstant(instant, ZoneId.of(dateTime.getZone().getID()).normalized());
  }

  /**
   * Converts a java.time {@link ZonedDateTime} object to an equivalent Joda {@link DateTime}
   * object.
   */
  public static DateTime toJodaDateTime(ZonedDateTime zonedDateTime) {
    return new DateTime(
        zonedDateTime.toInstant().toEpochMilli(),
        DateTimeZone.forTimeZone(TimeZone.getTimeZone(zonedDateTime.getZone())));
  }
}
