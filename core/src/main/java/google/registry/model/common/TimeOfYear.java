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

import static com.google.common.collect.DiscreteDomain.integers;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.leapSafeAddYears;
import static google.registry.util.DateTimeUtils.leapSafeSubtractYears;
import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toInstant;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.Range;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.joda.time.DateTime;

/**
 * A time of year (month, day, millis of day) that can be stored in a sort-friendly format.
 *
 * <p>This is conceptually similar to {@code MonthDay} in Joda or more generally to Joda's {@code
 * Partial}, but the parts we need are too simple to justify a full implementation of {@code
 * Partial}.
 *
 * <p>For simplicity, the native representation of this class's data is its stored format. This
 * allows it to be embeddable with no translation needed and also delays parsing of the string on
 * load until it's actually needed.
 */
@Embeddable
public class TimeOfYear extends ImmutableObject implements UnsafeSerializable {

  /**
   * The time as "month day millis" with all fields left-padded with zeroes so that lexographic
   * sorting will do the right thing.
   */
  String timeString;

  /**
   * Constructs a {@link TimeOfYear} from a {@link DateTime}.
   *
   * <p>This handles leap years in an intentionally peculiar way by always treating February 29 as
   * February 28. It is impossible to construct a {@link TimeOfYear} for February 29th.
   *
   * @deprecated Use {@link #fromInstant(Instant)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static TimeOfYear fromDateTime(DateTime dateTime) {
    return fromInstant(toInstant(dateTime));
  }

  /**
   * Constructs a {@link TimeOfYear} from an {@link Instant}.
   *
   * <p>This handles leap years in an intentionally peculiar way by always treating February 29 as
   * February 28. It is impossible to construct a {@link TimeOfYear} for February 29th.
   */
  public static TimeOfYear fromInstant(Instant instant) {
    ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC);
    int month = zdt.getMonthValue();
    int day = zdt.getDayOfMonth();
    if (month == 2 && day == 29) {
      day = 28;
    }
    TimeOfYear instance = new TimeOfYear();
    instance.timeString =
        String.format("%02d %02d %08d", month, day, zdt.toLocalTime().toNanoOfDay() / 1000000);
    return instance;
  }

  /**
   * Returns an {@link Iterable} of {@link DateTime}s of every recurrence of this particular time of
   * year within a given {@link Range} (usually one spanning many years).
   *
   * <p>WARNING: This can return a potentially very large {@link Iterable} if {@code END_OF_TIME} is
   * used as the upper endpoint of the range.
   *
   * @deprecated Use {@link #getInstancesInRangeInstant(Range)}
   */
  @Deprecated
  public Iterable<DateTime> getInstancesInRange(Range<DateTime> range) {
    // In registry world, all dates are within START_OF_TIME and END_OF_TIME, so restrict any
    // ranges without bounds to our notion of zero-to-infinity.
    Range<DateTime> normalizedRange = range.intersection(Range.closed(START_OF_TIME, END_OF_TIME));
    Range<Integer> yearRange = Range.closed(
        normalizedRange.lowerEndpoint().getYear(),
        normalizedRange.upperEndpoint().getYear());
    return ContiguousSet.create(yearRange, integers())
        .stream()
        .map(this::getDateTimeWithYear)
        .filter(normalizedRange)
        .collect(toImmutableList());
  }

  /**
   * Returns an {@link Iterable} of {@link Instant}s of every recurrence of this particular time of
   * year within a given {@link Range} (usually one spanning many years).
   *
   * <p>WARNING: This can return a potentially very large {@link Iterable} if {@code END_INSTANT} is
   * used as the upper endpoint of the range.
   */
  public Iterable<Instant> getInstancesInRangeInstant(Range<Instant> range) {
    // In registry world, all dates are within START_INSTANT and END_INSTANT, so restrict any
    // ranges without bounds to our notion of zero-to-infinity.
    Range<Instant> normalizedRange = range.intersection(Range.closed(START_INSTANT, END_INSTANT));
    Range<Integer> yearRange =
        Range.closed(
            ZonedDateTime.ofInstant(normalizedRange.lowerEndpoint(), java.time.ZoneOffset.UTC)
                .getYear(),
            ZonedDateTime.ofInstant(normalizedRange.upperEndpoint(), java.time.ZoneOffset.UTC)
                .getYear());
    return ContiguousSet.create(yearRange, integers()).stream()
        .map(this::toInstantWithYear)
        .filter(normalizedRange)
        .collect(toImmutableList());
  }

  /**
   * Return a new instant with the same year as the parameter but projected to the month, day, and
   * time of day of this object.
   */
  private Instant toInstantWithYear(int year) {
    List<String> monthDayMillis = Splitter.on(' ').splitToList(timeString);
    int month = Integer.parseInt(monthDayMillis.get(0));
    int day = Integer.parseInt(monthDayMillis.get(1));
    int millis = Integer.parseInt(monthDayMillis.get(2));
    return LocalDate.of(year, month, day)
        .atTime(LocalTime.ofNanoOfDay(millis * 1000000L))
        .toInstant(ZoneOffset.UTC);
  }

  /**
   * Get the first {@link DateTime} with this month/day/millis that is at or after the start.
   *
   * @deprecated Use {@link #getNextInstanceAtOrAfterInstant(Instant)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public DateTime getNextInstanceAtOrAfter(DateTime start) {
    return toDateTime(getNextInstanceAtOrAfterInstant(toInstant(start)));
  }

  /** Get the first {@link Instant} with this month/day/millis that is at or after the start. */
  public Instant getNextInstanceAtOrAfterInstant(Instant start) {
    Instant withSameYear =
        toInstantWithYear(ZonedDateTime.ofInstant(start, java.time.ZoneOffset.UTC).getYear());
    return isAtOrAfter(withSameYear, start) ? withSameYear : leapSafeAddYears(withSameYear, 1);
  }

  /**
   * Get the first {@link DateTime} with this month/day/millis that is at or before the end.
   *
   * @deprecated Use {@link #getLastInstanceBeforeOrAtInstant(Instant)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public DateTime getLastInstanceBeforeOrAt(DateTime end) {
    return toDateTime(getLastInstanceBeforeOrAtInstant(toInstant(end)));
  }

  /** Get the first {@link Instant} with this month/day/millis that is at or before the end. */
  public Instant getLastInstanceBeforeOrAtInstant(Instant end) {
    Instant withSameYear =
        toInstantWithYear(ZonedDateTime.ofInstant(end, java.time.ZoneOffset.UTC).getYear());
    return isBeforeOrAt(withSameYear, end) ? withSameYear : leapSafeSubtractYears(withSameYear, 1);
  }

  /**
   * Return a new datetime with the same year as the parameter but projected to the month, day, and
   * time of day of this object.
   */
  private DateTime getDateTimeWithYear(int year) {
    List<String> monthDayMillis = Splitter.on(' ').splitToList(timeString);
    // Do not be clever and use Ints.stringConverter here. That does radix guessing, and bad things
    // will happen because of the leading zeroes.
    return new DateTime(0, UTC)
        .withYear(year)
        .withMonthOfYear(Integer.parseInt(monthDayMillis.get(0)))
        .withDayOfMonth(Integer.parseInt(monthDayMillis.get(1)))
        .withMillisOfDay(Integer.parseInt(monthDayMillis.get(2)));
  }
}
