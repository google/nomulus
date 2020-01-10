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

import java.io.Serializable;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.Objects;
import org.hibernate.HibernateException;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/** Generic Hibernate user type to store/retrieve Java collection as an array in Cloud SQL. */
public abstract class GenericCollectionUserType<T extends Collection> implements UserType {

  abstract T getNewCollection();

  abstract ArrayColumnType getColumnType();

  enum ArrayColumnType {
    STRING(Types.ARRAY, "text");

    final int typeCode;
    final String typeName;

    ArrayColumnType(int typeCode, String typeName) {
      this.typeCode = typeCode;
      this.typeName = typeName;
    }

    int getTypeCode() {
      return typeCode;
    }

    String getTypeName() {
      return typeName;
    }

    String getTypeDdlName() {
      return typeName + "[]";
    }
  }

  @Override
  public int[] sqlTypes() {
    return new int[] {getColumnType().getTypeCode()};
  }

  @Override
  public boolean equals(Object x, Object y) throws HibernateException {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(Object x) throws HibernateException {
    return x == null ? 0 : x.hashCode();
  }

  @Override
  public Object nullSafeGet(
      ResultSet rs, String[] names, SharedSessionContractImplementor session, Object owner)
      throws HibernateException, SQLException {
    if (rs.getArray(names[0]) != null) {
      T result = getNewCollection();
      for (Object element : (Object[]) rs.getArray(names[0]).getArray()) {
        result.add(element);
      }
      return result;
    }
    return null;
  }

  @Override
  public void nullSafeSet(
      PreparedStatement st, Object value, int index, SharedSessionContractImplementor session)
      throws HibernateException, SQLException {
    if (value == null) {
      st.setArray(index, null);
      return;
    }
    T list = (T) value;
    Array arr = st.getConnection().createArrayOf(getColumnType().getTypeName(), list.toArray());
    st.setArray(index, arr);
  }

  // TODO(b/147489651): Investigate how to properly implement the below methods.
  @Override
  public Object deepCopy(Object value) throws HibernateException {
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(Object value) throws HibernateException {
    return (Serializable) value;
  }

  @Override
  public Object assemble(Serializable cached, Object owner) throws HibernateException {
    return cached;
  }

  @Override
  public Object replace(Object original, Object target, Object owner) throws HibernateException {
    return original;
  }
}
