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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import java.io.IOException;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.testcontainers.containers.Container;

/**
 * Generates a schema for JPA annotated classes using Hibernate.
 *
 * <p>Note that this isn't complete yet, as all of the persistent classes have not yet been
 * converted. After converting a class, a call to "addAnnotatedClass()" for the new class must be
 * added to the code below.
 */
@Parameters(separators = " =", commandDescription = "Dump golden schema.")
public class DumpGoldenSchemaCommand extends PostgresqlCommand {

  // The mount point in the container.
  private static final String CONTAINER_MOUNT_POINT = "/tmp/pg_dump.out";

  @Parameter(
      names = {"--output", "-o"},
      description = "Output file",
      required = true)
  Path output;

  @Override
  void runCommand() throws IOException, InterruptedException {
    Flyway flyway =
        Flyway.configure()
            .locations("sql/flyway")
            .dataSource(
                postgresContainer.getJdbcUrl(),
                postgresContainer.getUsername(),
                postgresContainer.getPassword())
            .load();

    PostgreSQLConfigurationExtension configurationExtension =
        flyway.getConfigurationExtension(PostgreSQLConfigurationExtension.class);
    configurationExtension.setTransactionalLock(false);
    flyway.migrate();

    String userName = postgresContainer.getUsername();
    String databaseName = postgresContainer.getDatabaseName();
    Container.ExecResult result =
        postgresContainer.execInContainer(getSchemaDumpCommand(userName, databaseName));
    if (result.getExitCode() != 0) {
      throw new RuntimeException(result.toString());
    }
    postgresContainer.copyFileFromContainer(CONTAINER_MOUNT_POINT, output.toString());
  }

  private static String[] getSchemaDumpCommand(String username, String dbName) {
    return new String[] {
      "pg_dump",
      "-h",
      "localhost",
      "-U",
      username,
      "-f",
      CONTAINER_MOUNT_POINT,
      "--schema-only",
      "--no-owner",
      "--no-privileges",
      "--exclude-table",
      "flyway_schema_history",
      dbName
    };
  }
}
