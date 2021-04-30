// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import google.registry.persistence.NomulusPostgreSql;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Tests the database wipeout mechanism used by {@link WipeOutCloudSqlAction}. */
@Testcontainers
public class WipeOutCloudSqlIntegrationTest {

  @Container
  PostgreSQLContainer container = new PostgreSQLContainer(NomulusPostgreSql.getDockerTag());

  private Connection getJdbcConnection() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("user", container.getUsername());
    properties.setProperty("password", container.getPassword());
    return container.getJdbcDriverInstance().connect(container.getJdbcUrl(), properties);
  }

  @BeforeEach
  void beforeEach() throws Exception {
    try (Connection conn = getJdbcConnection();
        Statement statement = conn.createStatement()) {
      statement.addBatch("CREATE TABLE public.\"Domain\" (value int);");
      statement.addBatch("CREATE SEQUENCE public.\"Domain_seq\"");
      statement.executeBatch();
    }
  }

  @Test
  void listTables() throws Exception {
    try (Connection conn = getJdbcConnection()) {
      ImmutableList<String> tables = WipeOutCloudSqlAction.listTables(conn);
      assertThat(tables).containsExactly("public.\"Domain\"");
    }
  }

  @Test
  void dropAllTables() throws Exception {
    try (Connection conn = getJdbcConnection()) {
      ImmutableList<String> tables = WipeOutCloudSqlAction.listTables(conn);
      assertThat(tables).isNotEmpty();
      WipeOutCloudSqlAction.dropAllTables(conn, tables);
      assertThat(WipeOutCloudSqlAction.listTables(conn)).isEmpty();
    }
  }

  @Test
  void listAllSequences() throws Exception {
    try (Connection conn = getJdbcConnection()) {
      ImmutableList<String> sequences = WipeOutCloudSqlAction.listSequences(conn);
      assertThat(sequences).containsExactly("\"Domain_seq\"");
    }
  }

  @Test
  void dropAllSequences() throws Exception {
    try (Connection conn = getJdbcConnection()) {
      ImmutableList<String> sequences = WipeOutCloudSqlAction.listSequences(conn);
      assertThat(sequences).isNotEmpty();
      WipeOutCloudSqlAction.dropAllSequences(conn, sequences);
      assertThat(WipeOutCloudSqlAction.listSequences(conn)).isEmpty();
    }
  }
}
