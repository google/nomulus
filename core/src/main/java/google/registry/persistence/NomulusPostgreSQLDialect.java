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
package google.registry.persistence;

import google.registry.persistence.GenericCollectionUserType.ArrayColumnType;
import java.sql.Types;
import org.hibernate.dialect.PostgreSQL95Dialect;

/** Nomulus mapping rules for column types in Postgresql. */
public class NomulusPostgreSQLDialect extends PostgreSQL95Dialect {
  public NomulusPostgreSQLDialect() {
    super();
    registerColumnType(Types.VARCHAR, "text");
    registerColumnType(Types.TIMESTAMP_WITH_TIMEZONE, "timestamptz");
    registerColumnType(Types.TIMESTAMP, "timestamptz");
    for (ArrayColumnType arrayType : ArrayColumnType.values()) {
      registerColumnType(arrayType.getTypeCode(), arrayType.getTypeDdlName());
    }
  }
}
