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

package google.registry.beam.initsql;

import com.google.auto.value.AutoValue;
import java.util.Optional;

/**
 * Information needed to connect to a database, including JDBC URL, user name, password, and in the
 * case of Cloud SQL, the database instance's name.
 */
@AutoValue
abstract class SqlAccessInfo {

  abstract String jdbcUrl();

  abstract String user();

  abstract String password();

  abstract Optional<String> cloudSqlInstanceName();

  public static SqlAccessInfo createCloudSqlAccessInfo(
      String sqlInstanceName, String username, String password) {
    return new AutoValue_SqlAccessInfo(
        "jdbc:postgresql://google/postgres", username, password, Optional.of(sqlInstanceName));
  }

  public static SqlAccessInfo createLocalSqlAccessInfo(
      String jdbcUrl, String username, String password) {
    return new AutoValue_SqlAccessInfo(jdbcUrl, username, password, Optional.empty());
  }
}
