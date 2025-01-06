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
package google.registry.persistence;

import google.registry.persistence.converter.DurationUserType;
import google.registry.persistence.converter.JodaMoneyType;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

/** Nomulus mapping rules for column types in Postgresql. */
public class NomulusPostgreSQLDialect extends PostgreSQLDialect {

  // Custom Sql type codes that tie custom `UserType` subclasses to their desired database column
  // definitions. See `contributeTypes` below.
  // These codes may take arbitrary values as long as they do not conflict with existing codes.

  /** Represents a type backed by an `hstore` column in the database. */
  public static final int NATIVE_MAP_TYPE = Integer.MAX_VALUE - 1;

  /** Represents a type backed by an `text[]` column in the database. */
  public static final int NATIVE_ARRAY_OF_POJO_TYPE = Integer.MAX_VALUE - 2;

  /** Represents a type backed by an `interval` column in the database. */
  public static final int NATIVE_INTERVAL_TYPE = Integer.MAX_VALUE - 3;

  public NomulusPostgreSQLDialect() {
    super();
  }

  @Override
  public String getArrayTypeName(
      String javaElementTypeName, String elementTypeName, Integer maxLength) {
    return elementTypeName + "[]";
  }

  @Override
  public void contributeTypes(
      TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
    super.contributeTypes(typeContributions, serviceRegistry);
    // Register custom user types for auto conversion. For now, this only works if the Java type to
    // convert does not have generic type parameters.
    typeContributions.contributeType(new DurationUserType());
    typeContributions.contributeType(new JodaMoneyType());

    // Verify that custom codes do not conflict with built-in types.
    for (int customType :
        new int[] {NATIVE_MAP_TYPE, NATIVE_ARRAY_OF_POJO_TYPE, NATIVE_INTERVAL_TYPE}) {
      try {
        super.columnType(customType);
        throw new IllegalStateException(
            "Custom code " + customType + " conflicts with built-in type");
      } catch (IllegalArgumentException expected) {
        // OK
      }
    }

    DdlTypeRegistry ddlTypes = typeContributions.getTypeConfiguration().getDdlTypeRegistry();
    ddlTypes.addDescriptor(new DdlTypeImpl(NATIVE_MAP_TYPE, "hstore", this));
    ddlTypes.addDescriptor(new DdlTypeImpl(NATIVE_ARRAY_OF_POJO_TYPE, "text[]", this));
    ddlTypes.addDescriptor(new DdlTypeImpl(NATIVE_INTERVAL_TYPE, "interval", this));
    // Use text instead of varchar to match real schema from psql dump.
    ddlTypes.addDescriptor(new DdlTypeImpl(SqlTypes.VARCHAR, "text", this));
  }
}
