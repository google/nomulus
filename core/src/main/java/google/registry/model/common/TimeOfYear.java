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
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ContiguousSet;
import com.google.common.collect.Range;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Index;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import java.util.List;
import javax.persistence.Embeddable;
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
@Embed
@Embeddable
public class TimeOfYear extends ImmutableObject implements UnsafeSerializable {

  /**
   * The time as "month day millis" with all fields left-padded with zeroes so that lexographic
   * sorting will do the right thing.
   */
  @Index
  String timeString;

  /**
   * Constructs a {@link TimeOfYear} from a {@link DateTime}.
   *
   * <p>This handles leap years in an intentionally peculiar way by always treating February 29 as
   * February 28. It is impossible to construct a {@link TimeOfYear} for February 29th.
   */
  public static TimeOfYear fromDateTime(DateTime dateTime) {
    DateTime nextYear = dateTime.plusYears(1);  // This turns February 29 into February 28.
    TimeOfYear instance = new TimeOfYear();
    instance.timeString = String.format(
        "%02d %02d %08d",
        nextYear.getMonthOfYear(),
        nextYear.getDayOfMonth(),
        nextYear.getMillisOfDay());
    return instance;
  }

  /**
   * Returns an {@link Iterable} of {@link DateTime}s of every recurrence of this particular
   * time of year within a given {@link Range} (usually one spanning many years).
   *
   * <p>WARNING: This can return a potentially very large {@link Iterable} if {@code END_OF_TIME}
   * is used as the upper endpoint of the range.
   */
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

  /** Get the first {@link DateTime} with this month/day/millis that is at or after the start. */
  public DateTime getNextInstanceAtOrAfter(DateTime start) {
    DateTime withSameYear = getDateTimeWithYear(start.getYear());
    return isAtOrAfter(withSameYear, start) ? withSameYear : withSameYear.plusYears(1);
  }

  /** Get the first {@link DateTime} with this month/day/millis that is at or before the end. */
  public DateTime getLastInstanceBeforeOrAt(DateTime end) {
    DateTime withSameYear = getDateTimeWithYear(end.getYear());
    return isBeforeOrAt(withSameYear, end) ? withSameYear : withSameYear.minusYears(1);
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
