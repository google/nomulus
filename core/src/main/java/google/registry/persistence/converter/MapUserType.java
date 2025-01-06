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

import static google.registry.persistence.NomulusPostgreSQLDialect.NATIVE_MAP_TYPE;

import java.io.Serializable;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.Objects;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

/**
 * Base Hibernate custom type for a logical map backed by a string map column (e.g., hstore in
 * Postgresql).
 *
 * @param <M> Type parameter for the logical map. It might not implement the {@link Map} interface.
 */
@SuppressWarnings({"raw", "unchecked"})
abstract class MapUserType<M> implements UserType<M> {

  abstract Map<String, String> toStringMap(M map);

  abstract M toEntity(Map<String, String> map);

  @Override
  public int getSqlType() {
    return NATIVE_MAP_TYPE;
  }

  @Override
  public boolean equals(M map, M other) {
    return Objects.equals(map, other);
  }

  @Override
  public int hashCode(M map) {
    return Objects.hashCode(map);
  }

  @Override
  public M nullSafeGet(
      ResultSet resultSet,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor,
      Object o)
      throws SQLException {
    Object object = resultSet.getObject(i);
    if (resultSet.wasNull()) {
      return null;
    }
    return toEntity((Map<String, String>) object);
  }

  @Override
  public void nullSafeSet(
      PreparedStatement preparedStatement,
      M map,
      int i,
      SharedSessionContractImplementor sharedSessionContractImplementor)
      throws SQLException {
    if (map == null) {
      preparedStatement.setNull(i, Types.OTHER);
    } else {
      preparedStatement.setObject(i, toStringMap(map), Types.OTHER);
    }
  }

  @Override
  public M deepCopy(M map) {
    return map;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(M stringStringMap) {
    return (Serializable) stringStringMap;
  }

  @Override
  public M assemble(Serializable serializable, Object o) {
    return (M) serializable;
  }
}
