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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.tools.GenerateSqlErDiagramCommand.FLYWAY_FILE_ELEMENT_ID;
import static google.registry.tools.GenerateSqlErDiagramCommand.getLastFlywayFileName;

import com.google.common.base.Joiner;
import com.google.common.io.Resources;
import google.registry.util.ResourceUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GenerateSqlErDiagramCommand}. */
class GenerateSqlErDiagramCommandTest extends CommandTestCase<GenerateSqlErDiagramCommand> {

  private static final String GOLDEN_DIAGRAM_FOLDER = "sql/er_diagram";
  private static final String UPDATE_INSTRUCTIONS =
      Joiner.on('\n')
          .join(
              "",
              "-------------------------------------------------------------------------------",
              "Your changes affect SQL ER diagrams. To update the checked-in version, run the"
                  + " following command in the repository root:",
              "./gradlew devTool --args=\"-e localhost generate_sql_er_diagram -o"
                  + " ../db/src/main/resources/sql/er_diagram\"",
              "");

  @Test
  void testSchemaGeneration() throws Exception {
    runCommand("--out_dir=" + tmpDir.resolve("diagram").toString());

    Path fullDiagram = tmpDir.resolve("diagram/full_er_diagram.html");
    Document fullDiagramDoc = Jsoup.parse(fullDiagram.toFile(), StandardCharsets.UTF_8.name());
    assertThat(fullDiagramDoc.select("svg")).isNotEmpty();

    Path briefDiagram = tmpDir.resolve("diagram/full_er_diagram.html");
    Document briefDiagramDoc = Jsoup.parse(briefDiagram.toFile(), StandardCharsets.UTF_8.name());
    assertThat(briefDiagramDoc.select("svg")).isNotEmpty();
  }

  @Test
  void validateErDiagramIsUpToDate() {
    String goldenFullDiagram =
        ResourceUtils.readResourceUtf8(
            Resources.getResource(
                Paths.get(GOLDEN_DIAGRAM_FOLDER).resolve("full_er_diagram.html").toString()));
    assertWithMessage(UPDATE_INSTRUCTIONS)
        .that(Jsoup.parse(goldenFullDiagram).getElementById(FLYWAY_FILE_ELEMENT_ID).text())
        .isEqualTo(getLastFlywayFileName());

    String briefFullDiagram =
        ResourceUtils.readResourceUtf8(
            Resources.getResource(
                Paths.get(GOLDEN_DIAGRAM_FOLDER).resolve("brief_er_diagram.html").toString()));
    assertWithMessage(UPDATE_INSTRUCTIONS)
        .that(Jsoup.parse(briefFullDiagram).getElementById(FLYWAY_FILE_ELEMENT_ID).text())
        .isEqualTo(getLastFlywayFileName());
  }
}
