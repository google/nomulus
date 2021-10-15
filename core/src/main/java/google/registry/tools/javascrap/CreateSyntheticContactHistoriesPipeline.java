// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableMap;
import dagger.Component;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.model.contact.Contact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.inject.Singleton;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.DateTime;

/**
 * Create synthetic history entries to fix RDE due to contact history PII wipeout.
 *
 * <p>To run the pipeline:
 *
 * <p>{@code $ ./nom_build :core:cSCH --args="--region=us-central1 --runner=DataflowRunner
 * --registryEnvironment=CRASH --project={project-id} --workerMachineType=n2-standard-4" }
 */
public class CreateSyntheticContactHistoriesPipeline implements Serializable {

  private static final String HISTORY_REASON = "Backfill to fix RDE failures due to PII wipeout";

  private static final DateTime WATERMARK = DateTime.parse("2023-04-04T00:00:00Z");

  private static final Counter contactCounter = Metrics.counter("Synthetic", "number of contacts");

  static void setup(Pipeline pipeline, String registryAdminRegistrarId) {
    pipeline
        .apply(
            "Read most recent ContactHistory without PII before watermark",
            RegistryJpaIO.read(
                    "SELECT repoId FROM ContactHistory WHERE (repoId, modificationTime) IN (SELECT"
                        + " repoId, MAX(modificationTime) FROM ContactHistory WHERE"
                        + " modificationTime <= :watermark GROUP BY repoId) AND"
                        + " resource.deletionTime > :watermark AND"
                        + " COALESCE(resource.creationRegistrarId, '') NOT LIKE 'prober-%' AND"
                        + " COALESCE(resource.currentSponsorRegistrarId, '') NOT LIKE 'prober-%'"
                        + " AND COALESCE(resource.lastEppUpdateRegistrarId, '') NOT LIKE 'prober-%'"
                        + " AND email IS NULL",
                    ImmutableMap.of("watermark", WATERMARK), String.class, x -> x)
                .withCoder(StringUtf8Coder.of()))
        .apply(
            "Create a synthetic HistoryEntry for each Contact at watermark",
            MapElements.into(TypeDescriptor.of(HistoryEntry.class))
                .via(
                    (String repoId) -> {
                      contactCounter.inc();
                      Contact contact =
                          tm().transact(() -> tm().loadByKey(VKey.create(Contact.class, repoId)));
                      return HistoryEntry.createBuilderForResource(contact)
                          .setRegistrarId(registryAdminRegistrarId)
                          .setBySuperuser(true)
                          .setRequestedByRegistrar(false)
                          .setModificationTime(WATERMARK)
                          .setReason(HISTORY_REASON)
                          .setType(HistoryEntry.Type.SYNTHETIC)
                          .build();
                    }))
        .apply(RegistryJpaIO.write());
  }

  public static void main(String[] args) {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    String registryAdminRegistrarId =
        DaggerCreateSyntheticContactHistoriesPipeline_ConfigComponent.create()
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
