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

// import static org.mockito.Mockito.mock;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.testcontainers.containers.PostgreSQLContainer;


@RunWith(JUnit4.class)
public class GenerateSqlSchemaCommandTest extends CommandTestCase<GenerateSqlSchemaCommand> {

  private String containerHostName;
  private int containerPort;

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Rule public PostgreSQLContainer postgres =
      new PostgreSQLContainer()
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
        "--out-file=" + tmp.getRoot() + File.separatorChar + "schema.sql",
        "--db-host=" + containerHostName,
        "--db-port=" + containerPort);

    // We're just interested in verifying that there is a schema file generated, we don't do any
    // checks on the contents, this would make the test too brittle and serves no real purpose.
    // TODO: try running the schema against the test database.
    assertThat(new File(tmp.getRoot(), "schema.sql").exists()).isTrue();
  }
}
