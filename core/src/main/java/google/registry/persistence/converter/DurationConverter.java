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
 * <p>Note: this only converts the values for days, hours, minutes, and seconds
 */
@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, Object> {

  @Override
  @Nullable
  public Object convertToDatabaseColumn(@Nullable Duration duration) {
    if (duration == null) {
      return new PGInterval();
    }
    PGInterval interval = new PGInterval();
    Period period = new Period(duration);
    interval.setDays(period.getDays());
    interval.setHours(period.getHours());
    interval.setMinutes(period.getMinutes());
    double millis = (double) period.getMillis() / 1000;
    interval.setSeconds(period.getSeconds() + millis);
    return interval;
  }

  @Override
  @Nullable
  public Duration convertToEntityAttribute(@Nullable Object dbData) {
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
    final double secs = interval.getSeconds();
    return new Period(0, 0, 0, days, hours, mins, (int) secs, (int) ((secs % 1) * 1000))
        .toStandardDuration();
  }
}
