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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.tools.GenerateSqlErDiagramCommand.DiagramType.ALL;
import static google.registry.tools.GenerateSqlErDiagramCommand.DiagramType.BRIEF;
import static google.registry.tools.GenerateSqlErDiagramCommand.DiagramType.FULL;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.PathConverter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import google.registry.persistence.NomulusPostgreSql;
import google.registry.util.ResourceUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.testcontainers.containers.PostgreSQLContainer;
import schemacrawler.schemacrawler.LoadOptionsBuilder;
import schemacrawler.schemacrawler.SchemaCrawlerOptions;
import schemacrawler.schemacrawler.SchemaCrawlerOptionsBuilder;
import schemacrawler.schemacrawler.SchemaInfoLevelBuilder;
import schemacrawler.tools.executable.SchemaCrawlerExecutable;
import schemacrawler.tools.integration.diagram.DiagramOutputFormat;
import schemacrawler.tools.options.OutputOptions;
import schemacrawler.tools.options.OutputOptionsBuilder;

/** Command to generate ER diagrams for SQL schema. */
@Parameters(separators = " =", commandDescription = "Generate ER diagrams for SQL schmea.")
public class GenerateSqlErDiagramCommand implements Command {

  private static final String DB_NAME = "postgres";
  private static final String DB_USER = "username";
  private static final String DB_PASSWORD = "password";
  private static final String FULL_DIAGRAM_COMMAND = "schema";
  private static final String BRIEF_DIAGRAM_COMMAND = "brief";
  private static final String FULL_DIAGRAM_FILE_NAME = "full_er_diagram.html";
  private static final String BRIEF_DIAGRAM_FILE_NAME = "brief_er_diagram.html";
  private static final String NOMULUS_GOLDEN_SCHEMA = "sql/schema/nomulus.golden.sql";
  private static final String FLYWAY_FILE = "sql/flyway.txt";
  private static final String SVG_PAN_ZOOM_LIB = "google/registry/tools/svg-pan-zoom.min.js";

  // The HTML element ID for the last flyway file name
  static final String FLYWAY_FILE_ELEMENT_ID = "lastFlywayFile";

  @Parameter(
      names = {"-o", "--out_dir"},
      description = "Name of the output directory to store ER diagrams.",
      converter = PathConverter.class,
      required = true)
  private Path outDir;

  @Parameter(
      names = "--diagram_type",
      description =
          "Type of the generated ER diagram, can be FULL, BRIEF and ALL (defaults to ALL).")
  private DiagramType diagramType = ALL;

  /** The type of ER diagram. */
  public enum DiagramType {
    /** An HTML file that has an embedded ER diagram showing the full SQL schema. */
    FULL,

    /**
     * An HTML file that has an embedded ER diagram showing only significant columns, such as
     * primary and foreign key columns, and columns that are part of unique indexes.
     */
    BRIEF,

    /** Generates all types of ER diagrams. */
    ALL
  }

  @Override
  public void run() throws Exception {
    if (!outDir.toFile().exists()) {
      checkState(outDir.toFile().mkdirs(), "Failed to create directory %s", outDir);
    }

    PostgreSQLContainer postgresContainer =
        new PostgreSQLContainer(NomulusPostgreSql.getDockerTag())
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD);
    postgresContainer.start();

    try (Connection conn = getConnection(postgresContainer)) {
      initDb(conn);
      if (diagramType == ALL || diagramType == FULL) {
        improveDiagramHtml(generateErDiagram(conn, FULL_DIAGRAM_COMMAND, FULL_DIAGRAM_FILE_NAME));
      }
      if (diagramType == ALL || diagramType == BRIEF) {
        improveDiagramHtml(generateErDiagram(conn, BRIEF_DIAGRAM_COMMAND, BRIEF_DIAGRAM_FILE_NAME));
      }
    } finally {
      postgresContainer.stop();
    }
  }

  private void improveDiagramHtml(Path diagram) {
    try {
      Document doc = Jsoup.parse(diagram.toFile(), StandardCharsets.UTF_8.name());

      // Add the last name of the flyway file to the HTML so we can have a test to verify that if
      // the generated diagram is up to date.
      doc.select("body > table > tbody")
          .first()
          .append(
              "<tr>"
                  + "<td class=\"property_name\">last flyway file</td>"
                  + "<td id=\""
                  + FLYWAY_FILE_ELEMENT_ID
                  + "\" class=\"property_value\">"
                  + getLastFlywayFileName()
                  + "</td>"
                  + "</tr>");

      // Add pan and zoom support for the embedded SVG in the HTML.
      StringBuilder svgPanZoomLib =
          new StringBuilder("<script>")
              .append(ResourceUtils.readResourceUtf8(Resources.getResource(SVG_PAN_ZOOM_LIB)))
              .append("</script>");
      doc.select("head").first().append(svgPanZoomLib.toString());
      doc.select("svg")
          .first()
          .attributes()
          .add("id", "erDiagram")
          .add("style", "overflow: hidden; width: 100%; height: 800px");
      doc.select("body")
          .first()
          .append(
              "<script>"
                  + "svgPanZoom('#erDiagram', {"
                  + "  zoomEnabled: true,"
                  + "  controlIconsEnabled: true,"
                  + "  fit: true,"
                  + "  center: true,"
                  + "  minZoom: 0.1"
                  + "});"
                  + "</script>");

      Files.write(
          diagram, doc.outerHtml().getBytes(StandardCharsets.UTF_8), StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private Path generateErDiagram(Connection connection, String command, String fileName) {
    Path outputFile = outDir.resolve(fileName);

    LoadOptionsBuilder loadOptionsBuilder =
        LoadOptionsBuilder.builder().withSchemaInfoLevel(SchemaInfoLevelBuilder.standard());
    SchemaCrawlerOptions options =
        SchemaCrawlerOptionsBuilder.newSchemaCrawlerOptions()
            .withLoadOptions(loadOptionsBuilder.toOptions());
    OutputOptions outputOptions =
        OutputOptionsBuilder.newOutputOptions(DiagramOutputFormat.htmlx, outputFile);

    SchemaCrawlerExecutable executable = new SchemaCrawlerExecutable(command);
    executable.setSchemaCrawlerOptions(options);
    executable.setOutputOptions(outputOptions);
    executable.setConnection(connection);
    try {
      executable.execute();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    return outputFile;
  }

  private static Connection getConnection(PostgreSQLContainer container) {
    Properties info = new Properties();
    info.put("user", container.getUsername());
    info.put("password", container.getPassword());
    try {
      return container.getJdbcDriverInstance().connect(container.getJdbcUrl(), info);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  private static void initDb(Connection connection) {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          ResourceUtils.readResourceUtf8(Resources.getResource(NOMULUS_GOLDEN_SCHEMA)));
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @VisibleForTesting
  static String getLastFlywayFileName() {
    try {
      return Iterables.getLast(
          Resources.readLines(Resources.getResource(FLYWAY_FILE), StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
