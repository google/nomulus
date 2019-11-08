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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.annotations.VisibleForTesting;
import google.registry.persistence.HibernateSchemaExporter;
import google.registry.persistence.PersistenceXmlUtility;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Generates a schema for JPA annotated classes using Hibernate.
 *
 * <p>Note that this isn't complete yet, as all of the persistent classes have not yet been
 * converted. After converting a class, a call to "addAnnotatedClass()" for the new class must be
 * added to the code below.
 */
@Parameters(separators = " =", commandDescription = "Generate PostgreSQL schema.")
public class GenerateSqlSchemaCommand implements Command {
  private static final String DB_NAME = "postgres";
  private static final String DB_USERNAME = "postgres";
  private static final String DB_PASSWORD = "domain-registry";

  @VisibleForTesting
  public static final String DB_OPTIONS_CLASH =
      "Database host and port may not be specified along with the option to start a "
          + "PostgreSQL container.";

  @VisibleForTesting
  public static final int POSTGRESQL_PORT = 5432;

  private PostgreSQLContainer postgresContainer = null;

  @Parameter(
      names = {"-o", "--out_file"},
      description = "Name of the output file.",
      required = true)
  String outFile;

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

  @Override
  public void run() {
    // Start PostgreSQL if requested.
    if (startPostgresql) {
      // Complain if the user has also specified either --db_host or --db_port.
      if (databaseHost != null || databasePort != null) {
        System.err.println(DB_OPTIONS_CLASH);
        // TODO: it would be nice to exit(1) here, but this breaks testability.
        return;
      }

      // Start the container and store the address information.
      postgresContainer =
          new PostgreSQLContainer()
              .withDatabaseName(DB_NAME)
              .withUsername(DB_USERNAME)
              .withPassword(DB_PASSWORD);
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
              + "    -d postgres:9.6.12\n\n"
              + "Copy the container id output from the command, then run:\n\n"
              + "  docker inspect <container-id> | grep IPAddress\n\n"
              + "To obtain the value for --db-host.\n");
      // TODO(mmuller): need exit(1), see above.
      return;
    }

    // use the default port if non has been defined.
    if (databasePort == null) {
      databasePort = POSTGRESQL_PORT;
    }

    try {
      File outputFile = new File(outFile);

      // Generate the copyright header (this file gets checked for copyright).  The schema exporter
      // appends to the existing file, so this has the additional desired effect of clearing any
      // existing data in the file.
      String copyright =
          "-- Copyright 2019 The Nomulus Authors. All Rights Reserved.\n"
              + "--\n"
              + "-- Licensed under the Apache License, Version 2.0 (the \"License\");\n"
              + "-- you may not use this file except in compliance with the License.\n"
              + "-- You may obtain a copy of the License at\n"
              + "--\n"
              + "--     http://www.apache.org/licenses/LICENSE-2.0\n"
              + "--\n"
              + "-- Unless required by applicable law or agreed to in writing, software\n"
              + "-- distributed under the License is distributed on an \"AS IS\" BASIS,\n"
              + "-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n"
              + "-- See the License for the specific language governing permissions and\n"
              + "-- limitations under the License.\n";
      try {
        Files.write(outputFile.toPath(), copyright.getBytes(UTF_8));
      } catch (IOException e) {
        System.err.println("Error writing sql file: " + e);
        e.printStackTrace();
        System.exit(1);
      }

      HibernateSchemaExporter exporter =
          HibernateSchemaExporter.create(
              "jdbc:postgresql://"
                  + databaseHost
                  + ":"
                  + databasePort
                  + "/"
                  + DB_NAME
                  + "?useSSL=false",
              DB_USERNAME,
              DB_PASSWORD);
      exporter.export(PersistenceXmlUtility.getManagedClasses(), outputFile);

    } finally {
      if (postgresContainer != null) {
        postgresContainer.stop();
      }
    }
  }
}
