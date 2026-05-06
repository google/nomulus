// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import java.time.Duration;
import java.util.Objects;
import javax.annotation.Nullable;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
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
  @SuppressWarnings("removal")
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
  @SuppressWarnings("removal")
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
    PGInterval interval = new PGInterval();
    long seconds = duration.getSeconds();
    int nanos = duration.getNano();
    interval.setDays((int) (seconds / 86400));
    seconds %= 86400;
    interval.setHours((int) (seconds / 3600));
    seconds %= 3600;
    interval.setMinutes((int) (seconds / 60));
    seconds %= 60;
    interval.setSeconds(seconds + (double) nanos / 1_000_000_000);
    return interval;
  }

  @Nullable
  public static Duration convertToDuration(PGInterval dbData) {
    if (dbData == null) {
      return null;
    }
    double seconds = dbData.getSeconds();
    long fullSeconds = (long) seconds;
    int nanos = (int) Math.round((seconds - fullSeconds) * 1_000_000_000);
    return Duration.ofDays(dbData.getDays())
        .plusHours(dbData.getHours())
        .plusMinutes(dbData.getMinutes())
        .plusSeconds(fullSeconds)
        .plusNanos(nanos);
  }
}
