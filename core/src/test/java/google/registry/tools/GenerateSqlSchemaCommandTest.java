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
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Files;
import google.registry.persistence.NomulusPostgreSql;
import java.io.File;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;

/** Unit tests for {@link google.registry.tools.GenerateSqlSchemaCommand}. */
@RunWith(JUnit4.class)
public class GenerateSqlSchemaCommandTest extends CommandTestCase<GenerateSqlSchemaCommand> {

  private String containerHostName;
  private int containerPort;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @ClassRule
  public static PostgreSQLContainer postgres =
      new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
          .withDatabaseName("postgres")
          .withUsername("postgres")
          .withPassword("domain-registry");

  public GenerateSqlSchemaCommandTest() {}

  @Before
  public void setUp() {
    containerHostName = postgres.getContainerIpAddress();
    containerPort = postgres.getMappedPort(GenerateSqlSchemaCommand.POSTGRESQL_PORT);
  }

  @Test
  public void testSchemaGeneration() throws Exception {
    runCommand(
        "--out_file=" + tmp.getRoot() + File.separatorChar + "schema.sql",
        "--db_host=" + containerHostName,
        "--db_port=" + containerPort);

    // We don't verify the exact contents of the result SQL file because that would be too brittle,
    // but we check to make sure that a couple parts of it are named as we expect them to be
    // TODO: try running the schema against the test database.
    File sqlFile = new File(tmp.getRoot(), "schema.sql");
    assertThat(sqlFile.exists()).isTrue();
    String fileContent = Files.asCharSource(sqlFile, UTF_8).read();
    assertThat(fileContent).contains("create table \"Domain\" (");
    assertThat(fileContent).contains("repo_id text not null,");
  }

  @Test
  public void testIncompatibleFlags() throws Exception {
    runCommand(
        "--out_file=" + tmp.getRoot() + File.separatorChar + "schema.sql",
        "--db_host=" + containerHostName,
        "--db_port=" + containerPort,
        "--start_postgresql");
    assertInStderr(GenerateSqlSchemaCommand.DB_OPTIONS_CLASH);
  }

  @Test
  public void testDockerPostgresql() throws Exception {
    runCommand(
        "--start_postgresql", "--out_file=" + tmp.getRoot() + File.separatorChar + "schema.sql");
    assertThat(new File(tmp.getRoot(), "schema.sql").exists()).isTrue();
  }
}
