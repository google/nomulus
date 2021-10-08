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

package google.registry.beam.common;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import dagger.Component;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.io.Serializable;
import javax.inject.Singleton;
import javax.persistence.Entity;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * Pipeline that creates a synthetic history entry for every {@link EppResource} in SQL at the
 * current time.
 *
 * <p>The history entries in Datastore does not have the EPP resource embedded in them. Therefore
 * after {@link google.registry.beam.initsql.InitSqlPipeline} runs, these fields will all be empty.
 * This pipeline load all EPP resources and for each of them creates a synthetic history entry that
 * contains the resource and saves them back to SQL, so that they can be used in the RDE pipeline.
 *
 * <p>To run the pipeline:
 *
 * <p><code>
 * $ ./nom_build :core:jpaDemo --args="--region=us-central1
 *   --runner=DataflowRunner
 *   --registryEnvironment=CRASH
 *   --project={oroject-id}
 *   --workerMachineType=n2-standard-4"
 * </code>
 *
 * @see google.registry.tools.javascrap.CreateSyntheticHistoryEntriesAction
 */
public class CreateSyntheticHistoryEntriesPipeline implements Serializable {

  private static final ImmutableList<Class<? extends EppResource>> EPP_RESOURCE_CLASSES =
      ImmutableList.of(DomainBase.class, ContactResource.class, HostResource.class);

  private static final String HISTORY_REASON =
      "Backfill EppResource history objects after initial backup to SQL";

  public static void main(String[] args) {
    RegistryPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RegistryPipelineOptions.class);
    RegistryPipelineOptions.validateRegistryPipelineOptions(options);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    String registryAdminRegistrarId =
        DaggerCreateSyntheticHistoryEntriesPipeline_ConfigComponent.create()
            .getRegistryAdminRegistrarId();

    Pipeline pipeline = Pipeline.create(options);
    for (Class<? extends EppResource> clazz : EPP_RESOURCE_CLASSES) {
      pipeline
          .apply(
              String.format("Read all %s", clazz.getSimpleName()),
              RegistryJpaIO.read(
                  "SELECT id FROM %entity%"
                      .replace("%entity%", clazz.getAnnotation(Entity.class).name()),
                  String.class,
                  repoId -> VKey.createSql(clazz, repoId)))
          .apply(
              String.format("Save a synthetic HistoryEntry for each %s", clazz),
              MapElements.into(TypeDescriptor.of(Void.class))
                  .via(
                      (VKey<? extends EppResource> key) -> {
                        jpaTm()
                            .transact(
                                () -> {
                                  EppResource eppResource = jpaTm().loadByKey(key);
                                  jpaTm()
                                      .put(
                                          HistoryEntry.createBuilderForResource(eppResource)
                                              .setRegistrarId(registryAdminRegistrarId)
                                              .setBySuperuser(true)
                                              .setRequestedByRegistrar(false)
                                              .setModificationTime(jpaTm().getTransactionTime())
                                              .setReason(HISTORY_REASON)
                                              .setType(HistoryEntry.Type.SYNTHETIC)
                                              .build());
                                });
                        return null;
                      }));
    }
    pipeline.run();
  }

  @Singleton
  @Component(modules = ConfigModule.class)
  interface ConfigComponent {

    @Config("registryAdminClientId")
    String getRegistryAdminRegistrarId();
  }
}
