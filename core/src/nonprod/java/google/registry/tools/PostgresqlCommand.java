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

package google.registry.tools;

import com.beust.jcommander.Parameter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.persistence.NomulusPostgreSql;
import org.testcontainers.containers.PostgreSQLContainer;

/** Base class for commands that need a PostgreSQL database. */
public abstract class PostgresqlCommand implements Command {
  static final FluentLogger logger = FluentLogger.forEnclosingClass();
  protected static final String DB_NAME = "postgres";
  protected static final String DB_USERNAME = "postgres";
  protected static final String DB_PASSWORD = "domain-registry";

  @VisibleForTesting
  public static final String DB_OPTIONS_CLASH =
      "Database host and port may not be specified along with the option to start a "
          + "PostgreSQL container.";

  @VisibleForTesting public static final int POSTGRESQL_PORT = 5432;

  protected PostgreSQLContainer postgresContainer = null;

  @Parameter(
      names = {"-s", "--start_postgresql"},
      description = "If specified, start PostgreSQL in a Docker container.")
  boolean startPostgresql = false;

  @Parameter(
      names = {"-a", "--db_host"},
      description = "Database host name.")
  String databaseHost;

  @Parameter(
      names = {"-p", "--db_port"},
      description = "Database port number.  This defaults to the PostgreSQL default port.")
  Integer databasePort;

  /**
   * Starts the database if appropriate.
   *
   * <p>Returns true if the database was successfully initialized, false if not.
   */
  private boolean initializeDatabase() {
    // Start PostgreSQL if requested.
    if (startPostgresql) {
      // Complain if the user has also specified either --db_host or --db_port.
      if (databaseHost != null || databasePort != null) {
        System.err.println(DB_OPTIONS_CLASH);
        // TODO: it would be nice to exit(1) here, but this breaks testability.
        return false;
      }

      // Start the container and store the address information.
      postgresContainer =
          new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
              .withDatabaseName(DB_NAME)
              .withUsername(DB_USERNAME)
              .withPassword(DB_PASSWORD);
      try {
        onContainerCreate();
      } catch (Exception e) {
        logger.atSevere().withCause(e).log("Error in container callback hook.");
        return false;
      }
      postgresContainer.start();
      databaseHost = postgresContainer.getContainerIpAddress();
      databasePort = postgresContainer.getMappedPort(POSTGRESQL_PORT);
    } else if (databaseHost == null) {
      System.err.println(
          "You must specify either --start_postgresql to start a PostgreSQL database in a\n"
              + "docker instance, or specify --db_host (and, optionally, --db_port) to identify\n"
              + "the location of a running instance.  To start a long-lived instance (suitable\n"
              + "for running this command multiple times) run this:\n\n"
              + "  docker run --rm --name some-postgres -e POSTGRES_PASSWORD=domain-registry \\\n"
              + "    -d "
              + NomulusPostgreSql.getDockerTag()
              + "\n\nCopy the container id output from the command, then run:\n\n"
              + "  docker inspect <container-id> | grep IPAddress\n\n"
              + "To obtain the value for --db-host.\n");
      // TODO(mmuller): need exit(1), see above.
      return false;
    }

    // use the default port if non has been defined.
    if (databasePort == null) {
      databasePort = POSTGRESQL_PORT;
    }

    return true;
  }

  @Override
  public void run() throws Exception {
    if (!initializeDatabase()) {
      return;
    }

    try {
      runCommand();
    } finally {
      if (postgresContainer != null) {
        postgresContainer.stop();
      }
    }
  }

  /** Called after the container has been created but before it has been started. */
  protected void onContainerCreate() throws Exception {}

  /** Command to be run while the database is running. */
  abstract void runCommand() throws Exception;
}
