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

import static google.registry.persistence.NomulusPostgreSQLDialect.NATIVE_ARRAY_OF_POJO_TYPE;

import java.io.Serializable;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Base Hibernate custom type for {@link Collection} of entities backed by a postgresql
 * one-dimension text array.
 *
 * <p>Note that Hibernate 6 and later have builtin support for collections of primitive, Enum and
 * String types. Use this class on collection of user-defined entities.
 *
 * @param <V> Type parameter for collection values.
 * @param <C> Type parameter for collection.
 */
public abstract class StringCollectionUserType<V, C extends Collection<V>> implements UserType<C> {

  private static final String DB_ARRAY_ELEMENT_TYPE = "text";

  abstract String[] toJdbcObject(C collection);

  abstract C toEntity(String[] data);

  @Override
  public int getSqlType() {
    return NATIVE_ARRAY_OF_POJO_TYPE;
  }

  @Override
  public boolean equals(C one, C other) {
    if (one == other) {
      return true;
    }
    if (one == null || other == null) {
      return false;
    }
    if (one instanceof Set o1 && other instanceof Set o2) {
      return Objects.equals(o1, o2);
    }
    if (one instanceof List o1 && other instanceof List o2) {
      return Objects.equals(o1, o2);
    }
    throw new IllegalStateException("Unexpected types: " + one.getClass() + " " + other.getClass());
  }

  @Override
  public int hashCode(C collection) {
    return Objects.hashCode(collection);
  }

  @Override
  public C nullSafeGet(
      ResultSet resultSet,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor,
      Object o)
      throws SQLException {
    Array rawArray = resultSet.getArray(i);
    if (resultSet.wasNull()) {
      return null;
    } else {
      return toEntity((String[]) rawArray.getArray());
    }
  }

  @Override
  public void nullSafeSet(
      PreparedStatement preparedStatement,
      C collection,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor)
      throws SQLException {
    if (collection == null) {
      preparedStatement.setArray(i, null);
    } else {
      preparedStatement.setArray(
          i,
          preparedStatement
              .getConnection()
              .createArrayOf(DB_ARRAY_ELEMENT_TYPE, toJdbcObject(collection)));
    }
  }

  @Override
  public C deepCopy(C collection) {
    return collection;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(C collection) {
    return (Serializable) collection;
  }

  @Override
  public C assemble(Serializable serializable, Object o) {
    return (C) serializable;
  }
}
