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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;

/**
 * Tool to generate documentation for the EPP flows and corresponding external API.
 *
 * <p>Mostly responsible for producing standalone documentation files (HTML
 * and Markdown) from flow information objects; those call into javadoc to
 * extract documentation information from the flows package source files.
 * See the {@link FlowDocumentation} class for more details.
 */
@Parameters(separators = " =", commandDescription = "Tool to generate EPP API documentation")
public class FlowDocumentationTool {

  @Parameter(names = {"-o", "--output_file"},
      description = "file where generated documentation will be written (use '-' for stdout)")
  private String outputFileName;

  @Parameter(names = {"--help", "--helpshort"}, description = "print this help", help = true)
  private boolean displayHelp = false;

  /** Parses command line flags and then runs the documentation tool. */
  public static void main(String[] args) {
    FlowDocumentationTool docTool = new FlowDocumentationTool();
    JCommander jcommander = new JCommander(docTool);
    jcommander.setProgramName("flow_docs_tool");

    try {
      jcommander.parse(args);
    } catch (ParameterException e) {
      jcommander.usage();
      throw e;
    }

    if (docTool.displayHelp) {
      jcommander.usage();
      return;
    }

    docTool.run();
  }

  /** Generates flow documentation and then outputs it to the specified file. */
  public void run() {
    DocumentationGenerator docGenerator;
    try {
      docGenerator = new DocumentationGenerator();
    } catch (IOException e) {
      throw new RuntimeException("IO error while running Javadoc tool", e);
    }
    String output = docGenerator.generateMarkdown();
    if (outputFileName.equals("-")) {
      System.out.println(output);
    } else {
      if (outputFileName == null) {
        outputFileName = "doclet.html";
      }
      try {
        Files.asCharSink(new File(outputFileName), UTF_8).write(output);
      } catch (IOException e) {
        throw new RuntimeException("Could not write to specified output file", e);
      }
    }
  }
}


