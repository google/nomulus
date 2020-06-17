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

  public static Builder builder() {
    return new AutoValue_SqlAccessInfo.Builder();
  }

  public static SqlAccessInfo createCloudSqlAccessInfo(
      String sqlInstanceName, String username, String password) {
    return builder()
        .cloudSqlInstanceName(sqlInstanceName)
        .user(username)
        .password(password)
        .jdbcUrl("jdbc:postgresql://google/postgres")
        .build();
  }

  public static SqlAccessInfo createLocalSqlAccessInfo(
      String jdbcUrl, String username, String password) {
    return builder().user(username).password(password).jdbcUrl(jdbcUrl).build();
  }

  /** Builder for {@link SqlAccessInfo}. */
  @AutoValue.Builder
  abstract static class Builder {

    abstract Builder jdbcUrl(String jdbcUrl);

    abstract Builder user(String user);

    abstract Builder password(String password);

    abstract Builder cloudSqlInstanceName(String cloudSqlInstanceName);

    abstract SqlAccessInfo build();
  }
}
