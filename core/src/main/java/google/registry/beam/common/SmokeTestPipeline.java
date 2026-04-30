// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.common;

import static com.google.common.base.Verify.verify;

import com.google.common.flogger.FluentLogger;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;

/**
 * For smoke test in the build/deployment process.
 *
 * <p>There two coverage gaps in unit tests for BEAM pipelines:
 *
 * <ul>
 *   <li>The compatibility of the JVM and SDK in the pipeline image
 *   <li>The JPA setup, which is performed by the {@link RegistryPipelineWorkerInitializer}
 * </ul>
 *
 * <p>This classes defines a pipeline that performs one quick database query. The pipeline is
 * expected to complete quickly, and the build or deployment process may launch it on GCP and wait
 * for its completion to be certain that all aspects are tested for Nomulus pipelines.
 */
public class SmokeTestPipeline implements Serializable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static void main(String[] args) {
    PipelineOptionsFactory.register(RegistryPipelineOptions.class);
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    runPipeline(options);
  }

  static PipelineResult runPipeline(RegistryPipelineOptions options) {
    Pipeline pipeline = Pipeline.create(options);
    pipeline
        .apply(
            "Read Tlds",
            RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(Tld.class).build(), Tld::getTldStr)
                .withCoder(StringUtf8Coder.of()))
        .apply("Count Tlds", Count.globally())
        .apply(
            "Verify Count",
            ParDo.of(
                new DoFn<Long, Void>() {
                  @DoFn.ProcessElement
                  public void processElement(@Element Long count) {
                    logger.atInfo().log("Tld count: %s", count);
                    verify(count > 0, "Expecting 1 or more, got %s.", count);
                  }
                }));

    return pipeline.run();
  }
}
