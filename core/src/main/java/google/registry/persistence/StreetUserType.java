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

import com.google.api.client.util.Lists;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

/**
 * Hibernate user type for mapping between {@link List<String>} and 3 text columns to represent
 * addresses.
 */
public class StreetUserType extends MutableUserType {

  private static final int MAX_NUM_ADDRESS_LINE = 3;
  private static final int[] SQL_TYPES = getSqlTypes();

  private static int[] getSqlTypes() {
    int[] sqlTypes = new int[MAX_NUM_ADDRESS_LINE];
    Arrays.fill(sqlTypes, Types.VARCHAR);
    return sqlTypes;
  }

  @Override
  public int[] sqlTypes() {
    return SQL_TYPES;
  }

  @Override
  public Class returnedClass() {
    return List.class;
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    List<String> streets = Lists.newArrayList();
    for (String name : names) {
      String value = rs.getString(name);
      streets.add(value == null ? "" : value);
    }
    return streets;
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    List<String> streets = (List<String>) value;
    if (streets.size() > MAX_NUM_ADDRESS_LINE) {
      throw new IllegalArgumentException(
          String.format(
              "Size of the list [%d] exceeded the allowed maximum number of address lines [%d].",
              streets.size(), MAX_NUM_ADDRESS_LINE));
    }
    for (int offset = 0; offset < MAX_NUM_ADDRESS_LINE; offset++) {
      st.setString(index + offset, offset < streets.size() ? streets.get(offset) : "");
    }
  }
}
