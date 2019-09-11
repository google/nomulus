// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
package google.registry.hibernate;

import static com.google.common.base.MoreObjects.firstNonNull;

import google.registry.model.CreateAutoTimestamp;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

/** Custom type for CreateAutoTimestamp. */
public class CreateAutoTimestampType implements UserType {

  @Override
  public int[] sqlTypes() {
    return new int[] {Types.TIMESTAMP_WITH_TIMEZONE};
  }

  @Override
  public Class returnedClass() {
    return CreateAutoTimestamp.class;
  }

  @Override
  public boolean equals(Object x, Object y) throws HibernateException {
    return x.equals(y);
  }

  @Override
  public int hashCode(Object x) throws HibernateException {
    return x.hashCode();
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    String columnName = names[0];
    OffsetDateTime value = rs.getObject(columnName, OffsetDateTime.class);
    if (value == null) {
      return null;
    } else {
      return CreateAutoTimestamp.create(DateTimeUtils.toJodaDateTime(value.toZonedDateTime()));
    }
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    if (value == null) {
      st.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
    } else {
      // TODO(mmuller): Use the transaction time as the fallback as soon as
      // DatabaseTransactionManager is implemented.
      DateTime dateTime =
          firstNonNull(
              ((CreateAutoTimestamp) value).getTimestamp(), DateTime.now(DateTimeZone.UTC));
      ZonedDateTime ts = DateTimeUtils.toZonedDateTime(dateTime);
      st.setObject(index, ts.toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
    }
  }

  @Override
  public Object deepCopy(Object value) throws HibernateException {
    // CreateAutoTimestamp is immutable.
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(Object value) throws HibernateException {
    return ((CreateAutoTimestamp) value).getTimestamp();
  }

  @Override
  public Object assemble(Serializable cached, Object owner) throws HibernateException {
    return CreateAutoTimestamp.create((DateTime) cached);
  }

  @Override
  public Object replace(Object original, Object target, Object owner) throws HibernateException {
    return original;
  }
}
