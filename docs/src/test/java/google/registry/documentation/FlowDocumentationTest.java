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

package google.registry.documentation;

import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.util.BuildPathUtils.getProjectRoot;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Joiner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Tests to ensure that generated flow documentation matches the expected documentation. */
class FlowDocumentationTest {
  private static final Path GOLDEN_MARKDOWN_FILEPATH = getProjectRoot().resolve("docs/flows.md");

  private static final String UPDATE_COMMAND = "./gradlew :docs:flowDocsTool";
  private static final String UPDATE_INSTRUCTIONS =
      Joiner.on('\n')
          .join(
              "",
              "-----------------------------------------------------------------------------------",
              "Your changes affect the flow API documentation output. To update the golden version "
                  + "of the documentation, run:",
              UPDATE_COMMAND,
              "");

  @Test
  void testGeneratedMatchesGolden() throws IOException {
    // Read the markdown file.
    Path goldenMarkdownPath =
        GOLDEN_MARKDOWN_FILEPATH;

    String goldenMarkdown = new String(Files.readAllBytes(goldenMarkdownPath), UTF_8);

    // Don't use Truth's isEqualTo() because the output is huge and unreadable for large files.
    DocumentationGenerator generator = new DocumentationGenerator();
    if (!generator.generateMarkdown().equals(goldenMarkdown)) {
      assertWithMessage(UPDATE_INSTRUCTIONS).fail();
    }
  }
}
