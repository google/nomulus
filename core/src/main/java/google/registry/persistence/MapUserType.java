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

package google.registry.persistence;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * A custom {@link UserType} used to convert a Java {@link Map<String, String>} to/from PostgreSQL
 * hstore type. Per this <a href="https://www.postgresql.org/docs/current/hstore.html">doc</a>, as
 * hstore keys and values are simply text strings, the type of key and value in the Java map has to
 * be {@link String} as well.
 */
public class MapUserType extends MutableUserType {

  @Override
  public int[] sqlTypes() {
    return new int[] {Types.OTHER};
  }

  @Override
  public Class returnedClass() {
    return Map.class;
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    return rs.getObject(names[0]);
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    st.setObject(index, value);
  }
}
