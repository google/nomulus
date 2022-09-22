// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools.javascrap;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import dagger.Component;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.model.domain.Domain;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.inject.Singleton;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.joda.time.DateTime;

/**
 * Pipeline that creates a synthetic history for every non-deleted {@link Domain} in SQL.
 *
 * <p>This is created to fix the issue identified in b/248112997. After b/245940594, there were some
 * domains where the most recent history object did not represent the state of the domain as it
 * exists in the world. Because RDE loads only from DomainHistory objects, this means that RDE was
 * producing wrong data. This pipeline mitigates that issue by creating synthetic history events for
 * every domain that was not deleted as of the start of the pipeline -- then, we can guarantee that
 * this new history object represents the state of the domain as far as we know.
 *
 * <p>To run the pipeline (replace the environment as appropriate):
 *
 * <p><code>
 * $ ./nom_build :core:createSyntheticDomainHistories --args="--region=us-central1
 *   --runner=DataflowRunner
 *   --registryEnvironment=CRASH
 *   --project={project-id}
 *   --workerMachineType=n2-standard-4"
 * </code>
 */
public class CreateSyntheticDomainHistoriesPipeline implements Serializable {

  private static final String HISTORY_REASON =
      "Create synthetic domain histories to fix RDE for b/248112997";
  private static final DateTime BAD_PIPELINE_START_TIME =
      DateTime.parse("2022-09-05T00:00:00.000Z");

  static void setup(Pipeline pipeline, String registryAdminRegistrarId) {
    pipeline
        .apply(
            "Read all domain repo IDs",
            RegistryJpaIO.read(
                "SELECT repoId FROM Domain WHERE deletionTime > :badPipelineStartTime",
                ImmutableMap.of("badPipelineStartTime", BAD_PIPELINE_START_TIME),
                String.class,
                repoId -> VKey.createSql(Domain.class, repoId)))
        .apply(
            "Save a synthetic DomainHistory for each domain",
            ParDo.of(new DomainHistoryCreator(registryAdminRegistrarId)));
  }

  private static class DomainHistoryCreator extends DoFn<VKey<Domain>, Void> {

    private final String registryAdminRegistrarId;

    private DomainHistoryCreator(String registryAdminRegistrarId) {
      this.registryAdminRegistrarId = registryAdminRegistrarId;
    }

    @ProcessElement
    public void processElement(
        @Element VKey<Domain> key, PipelineOptions options, OutputReceiver<Void> outputReceiver) {
      jpaTm()
          .transact(
              () -> {
                Domain domain = jpaTm().loadByKey(key);
                jpaTm()
                    .put(
                        HistoryEntry.createBuilderForResource(domain)
                            .setRegistrarId(registryAdminRegistrarId)
                            .setBySuperuser(true)
                            .setRequestedByRegistrar(false)
                            .setModificationTime(jpaTm().getTransactionTime())
                            .setReason(HISTORY_REASON)
                            .setType(HistoryEntry.Type.SYNTHETIC)
                            .build());
                outputReceiver.output(null);
              });
    }
  }

  public static void main(String[] args) {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    String registryAdminRegistrarId =
        DaggerCreateSyntheticDomainHistoriesPipeline_ConfigComponent.create()
            .getRegistryAdminRegistrarId();

    Pipeline pipeline = Pipeline.create(options);
    setup(pipeline, registryAdminRegistrarId);
    pipeline.run();
  }

  @Singleton
  @Component(modules = ConfigModule.class)
  interface ConfigComponent {

    @Config("registryAdminClientId")
    String getRegistryAdminRegistrarId();
  }
}
