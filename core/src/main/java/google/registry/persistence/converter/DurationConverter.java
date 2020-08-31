// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.converter;

import java.sql.SQLException;
import javax.annotation.Nullable;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import org.joda.time.Duration;
import org.joda.time.Period;
import org.postgresql.util.PGInterval;

/**
 * JPA converter to for storing/retrieving {@link org.joda.time.Duration} objects.
 *
 * <p>The Joda Time Duration is simply a number of milliseconds representing a length of time. This
 * can be converted into a PGInterval, but only for the fields that have a standard number of
 * milliseconds. Therefore, there is no way to populate the months or years field of a PGInterval
 * and be confident that it is representing the exact number of milliseconds it was intended to
 * represent.
 */
@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, PGInterval> {

  @Override
  @Nullable
  public PGInterval convertToDatabaseColumn(@Nullable Duration duration) {
    if (duration == null) {
      return new PGInterval();
    }
    PGInterval interval = new PGInterval();
    Period period = duration.toPeriod();
    // When the period is created from duration by calling duration.toPeriod(), it shows the
    // following behaviors:
    // 1. If the duration is small, less than one day, then this method will perform
    //    as you might expect and split the fields evenly.
    // 2. If the duration is larger than one day then all the remaining duration will
    //    be stored in the largest available field, hours in this case.
    interval.setDays(period.getHours() / 24);
    interval.setHours(period.getHours() % 24);
    interval.setMinutes(period.getMinutes());
    double millis = (double) period.getMillis() / 1000;
    interval.setSeconds(period.getSeconds() + millis);
    return interval;
  }

  @Override
  @Nullable
  public Duration convertToEntityAttribute(@Nullable PGInterval dbData) {
    if (dbData == null) {
      return null;
    }
    PGInterval interval = null;
    try {
      interval = new PGInterval(dbData.toString());
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    if (interval.equals(new PGInterval())) {
      return null;
    }

    final int days = interval.getDays();
    final int hours = interval.getHours();
    final int mins = interval.getMinutes();
    final int secs = (int) interval.getSeconds();
    final int millis = interval.getMicroSeconds() / 1000;
    return new Period(0, 0, 0, days, hours, mins, secs, millis).toStandardDuration();
  }
}
