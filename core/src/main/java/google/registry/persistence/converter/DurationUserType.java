// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.NomulusPostgreSQLDialect.NATIVE_INTERVAL_TYPE;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Objects;
import javax.annotation.Nullable;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.joda.time.Duration;
import org.joda.time.Period;
import org.postgresql.util.PGInterval;

/**
 * Hibernate custom type for {@link Duration}.
 *
 * <p>Conversion of {@code Duration} is automatic. See {@link
 * google.registry.persistence.NomulusPostgreSQLDialect} for more information.
 */
public class DurationUserType implements UserType<Duration> {

  @Override
  public int getSqlType() {
    return NATIVE_INTERVAL_TYPE;
  }

  @Override
  public Class<Duration> returnedClass() {
    return Duration.class;
  }

  @Override
  public boolean equals(Duration duration, Duration other) {
    return Objects.equals(duration, other);
  }

  @Override
  public int hashCode(Duration duration) {
    return Objects.hashCode(duration);
  }

  @Override
  public Duration nullSafeGet(
      ResultSet resultSet,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor,
      Object o)
      throws SQLException {
    PGInterval interval = resultSet.getObject(i, PGInterval.class);
    if (resultSet.wasNull()) {
      return null;
    }
    return convertToDuration(interval);
  }

  @Override
  public void nullSafeSet(
      PreparedStatement preparedStatement,
      Duration duration,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor)
      throws SQLException {
    if (duration == null) {
      preparedStatement.setNull(i, Types.OTHER);
    } else {
      preparedStatement.setObject(i, convertToPGInterval(duration), Types.OTHER);
    }
  }

  @Override
  public Duration deepCopy(Duration duration) {
    return duration;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(Duration duration) {
    return duration;
  }

  @Override
  public Duration assemble(Serializable serializable, Object o) {
    return (Duration) serializable;
  }

  public static PGInterval convertToPGInterval(Duration duration) {
    // When the period is created from duration by calling duration.toPeriod(), only precise fields
    // in the period type will be used. Thus, only the hour, minute, second and millisecond fields
    // on the period will be used. The year, month, week and day fields will not be populated:
    //   1. If the duration is small, less than one day, then this method will just set
    //      hours/minutes/seconds correctly.
    //   2. If the duration is larger than one day then all the remaining duration will
    //      be stored in the largest available field, hours in this case.
    // So, when we convert the period to a PGInterval instance, we set the days field by extracting
    // it from period's hours field.
    Period period = duration.toPeriod();
    PGInterval interval = new PGInterval();
    interval.setDays(period.getHours() / 24);
    interval.setHours(period.getHours() % 24);
    interval.setMinutes(period.getMinutes());
    double millis = (double) period.getMillis() / 1000;
    interval.setSeconds(period.getSeconds() + millis);
    return interval;
  }

  @Nullable
  public static Duration convertToDuration(PGInterval dbData) {
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
