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

package google.registry.beam.initsql;

import static google.registry.testing.truth.TextDiffSubject.assertWithMessageAboutUrlSource;

import com.google.common.io.Resources;
import google.registry.beam.TestPipelineExtension;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import org.apache.beam.runners.core.construction.renderer.PipelineDotRenderer;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Manages visualization of {@link InitSqlPipeline}. */
class InitSqlPipelineGraphTest {

  private static final String GOLDEN_DOT_FILE = "pipeline_golden.dot";

  private static final String[] OPTIONS_ARGS =
      new String[] {
        "--commitLogStartTimestamp=2000-01-01TZ",
        "--commitLogEndTimestamp=2000-01-02TZ",
        "--datastoreExportDir=/somedir",
        "--commitLogDir=/someotherdir",
        "--environment=alpha"
      };

  private static final transient InitSqlPipelineOptions options =
      PipelineOptionsFactory.fromArgs(OPTIONS_ARGS)
          .withValidation()
          .as(InitSqlPipelineOptions.class);

  @RegisterExtension
  final transient TestPipelineExtension testPipeline =
      TestPipelineExtension.create().enableAbandonedNodeEnforcement(false);

  @Test
  public void createPipeline_compareGraph() throws IOException {
    new InitSqlPipeline(options, testPipeline).setupPipeline();
    String dotString = PipelineDotRenderer.toDotString(testPipeline);
    URL goldenDotUrl = Resources.getResource(InitSqlPipelineGraphTest.class, GOLDEN_DOT_FILE);
    File outputFile = new File(new File(goldenDotUrl.getFile()).getParent(), "pipeline_curr.dot");
    try (PrintStream ps = new PrintStream(outputFile)) {
      ps.print(dotString);
    }
    assertWithMessageAboutUrlSource(
            "InitSqlPipeline graph changed. Run :core:updateInitSqlPipelineGraph to update.")
        .that(outputFile.toURI().toURL())
        .hasSameContentAs(goldenDotUrl);
  }
}
