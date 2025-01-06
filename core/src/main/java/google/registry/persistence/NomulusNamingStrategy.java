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

import com.google.common.base.CaseFormat;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Nomulus naming strategy for Hibernate ORM.
 *
 * <p>This class applies the following naming strategies to protect readability of SQL statements:
 *
 * <ul>
 *   <li>Table names are quoted UpperCamelCases.
 *   <li>Column names are in lower_underscore format.
 * </ul>
 *
 * These strategies ensure that both types of names are readable on case-insensitive platforms, and
 * are easily distinguishable from each other.
 *
 * <p>The use of quoted names may introduce portability problems with hand-crafted SQL queries,
 * e.g., between postgresql (which only accepts double-quotes) and MySQL(which only accepts
 * back-quotes). This gives us incentives for adopting SQL query builders such as JOOQ for reporting
 * etc.
 */
public class NomulusNamingStrategy implements PhysicalNamingStrategy {

  @Override
  public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    if (name.isQuoted()) {
      return name;
    }
    return jdbcEnvironment.getIdentifierHelper().toIdentifier(name.getText(), /* quoted= */ true);
  }

  @Override
  public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    return name;
  }

  @Override
  public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment jdbcEnvironment) {
    if (name.isQuoted()) {
      return name;
    }
    // Convert the lowerCamelCase field name into the snake_case column name
    return jdbcEnvironment
        .getIdentifierHelper()
        .toIdentifier(
            CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, name.getText()),
            /* quoted= */ false);
  }
}
