// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.truth.TextDiffSubject.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Files;
import com.google.common.io.Resources;
import google.registry.persistence.NomulusPostgreSql;
import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Unit tests for {@link GenerateSqlSchemaCommand}. */
@Testcontainers
class GenerateSqlSchemaCommandTest extends CommandTestCase<GenerateSqlSchemaCommand> {

  private String containerHostName;
  private int containerPort;

  @Container
  private static PostgreSQLContainer postgres =
      new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
          .withDatabaseName("postgres")
          .withUsername("postgres")
          .withPassword("domain-registry");

  @BeforeEach
  void beforeEach() {
    containerHostName = postgres.getContainerIpAddress();
    containerPort = postgres.getMappedPort(GenerateSqlSchemaCommand.POSTGRESQL_PORT);
  }

  @Test
  void testSchemaGeneration() throws Exception {
    runCommand(
        "--out_file=" + tmpDir.resolve("schema.sql").toString(),
        "--db_host=" + containerHostName,
        "--db_port=" + containerPort);

    // We don't verify the exact contents of the result SQL file because that would be too brittle,
    // but we check to make sure that a couple parts of it are named as we expect them to be
    // TODO: try running the schema against the test database.
    File schemaFile = tmpDir.resolve("schema.sql").toFile();
    assertThat(schemaFile.exists()).isTrue();
    String fileContent = Files.asCharSource(schemaFile, UTF_8).read();
    assertThat(fileContent).contains("create table \"Domain\" (");
    assertThat(fileContent).contains("repo_id text not null,");
  }

  @Test
  void testIncompatibleFlags() throws Exception {
    runCommand(
        "--out_file=" + tmpDir.resolve("schema.sql").toString(),
        "--db_host=" + containerHostName,
        "--db_port=" + containerPort,
        "--start_postgresql");
    assertInStderr(GenerateSqlSchemaCommand.DB_OPTIONS_CLASH);
  }

  @Test
  void testDockerPostgresql() throws Exception {
    Path schemaFile = tmpDir.resolve("schema.sql");
    runCommand("--start_postgresql", "--out_file=" + schemaFile.toString());
    assertThat(schemaFile.toFile().exists()).isTrue();
  }

  @Test
  void validateGeneratedSchemaIsSameAsSchemaInFile() throws Exception {
    Path schemaFile = tmpDir.resolve("schema.sql");
    runCommand("--start_postgresql", "--out_file=" + schemaFile.toString());
    assertThat(schemaFile.toFile().toURI().toURL())
        .hasSameContentAs(Resources.getResource("sql/schema/db-schema.sql.generated"));
  }
}
