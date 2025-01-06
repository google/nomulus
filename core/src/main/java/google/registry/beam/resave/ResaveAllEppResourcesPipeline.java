// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.beam.resave;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.EppResource;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.Host;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.joda.time.DateTime;

/**
 * A Dataflow Flex pipeline that resaves changed EPP resources in SQL.
 *
 * <p>Due to the way that Hibernate works, if an entity is unchanged by {@link
 * EppResource#cloneProjectedAtTime(DateTime)} it will not actually be re-persisted to the database.
 * Thus, the only actual changes occur when objects are changed by projecting them to now, such as
 * when a pending transfer is resolved.
 */
public class ResaveAllEppResourcesPipeline implements Serializable {

  private static final ImmutableSet<Class<? extends EppResource>> EPP_RESOURCE_CLASSES =
      ImmutableSet.of(Contact.class, Domain.class, Host.class);

  /**
   * There exist three possible situations where we know we'll want to project domains to the
   * current point in time:
   *
   * <ul>
   *   <li>A pending domain transfer has expired.
   *   <li>A domain is past its expiration time without being deleted (this means it autorenewed).
   *   <li>A domain has expired grace periods.
   * </ul>
   *
   * <p>This command contains all three scenarios so that we can avoid querying the Domain table
   * multiple times, and to avoid projecting and resaving the same domain multiple times.
   */
  private static final String DOMAINS_TO_PROJECT_QUERY =
      "SELECT repoId FROM Domain d WHERE (d.transferData.transferStatus = 'PENDING' AND"
          + " d.transferData.pendingTransferExpirationTime < current_timestamp()) OR"
          + " (d.registrationExpirationTime < current_timestamp() AND d.deletionTime ="
          + " (:END_OF_TIME)) OR (EXISTS (SELECT 1 FROM GracePeriod gp WHERE gp.domainRepoId ="
          + " d.repoId AND gp.expirationTime < current_timestamp()))";

  private final ResaveAllEppResourcesPipelineOptions options;

  ResaveAllEppResourcesPipeline(ResaveAllEppResourcesPipelineOptions options) {
    this.options = options;
  }

  PipelineResult run() {
    Pipeline pipeline = Pipeline.create(options);
    setupPipeline(pipeline);
    return pipeline.run();
  }

  void setupPipeline(Pipeline pipeline) {
    if (options.getFast()) {
      fastResaveContacts(pipeline);
      fastResaveDomains(pipeline);
    } else {
      EPP_RESOURCE_CLASSES.forEach(clazz -> forceResaveAllResources(pipeline, clazz));
    }
  }

  /** Projects to the current time and saves any contacts with expired transfers. */
  private void fastResaveContacts(Pipeline pipeline) {
    Read<String, String> repoIdRead =
        RegistryJpaIO.read(
                "SELECT repoId FROM Contact WHERE transferData.transferStatus = 'PENDING' AND"
                    + " transferData.pendingTransferExpirationTime < current_timestamp()",
                String.class,
                r -> r)
            .withCoder(StringUtf8Coder.of());
    projectAndResaveResources(pipeline, Contact.class, repoIdRead);
  }

  /**
   * Projects to the current time and saves any domains with expired pending actions (e.g.
   * transfers, grace periods).
   *
   * <p>The logic of what might have changed is paraphrased from {@link
   * DomainBase#cloneProjectedAtTime(DateTime)}.
   */
  private void fastResaveDomains(Pipeline pipeline) {
    Read<String, String> repoIdRead =
        RegistryJpaIO.read(
                DOMAINS_TO_PROJECT_QUERY,
                ImmutableMap.of("END_OF_TIME", DateTimeUtils.END_OF_TIME),
                String.class,
                r -> r)
            .withCoder(StringUtf8Coder.of());
    projectAndResaveResources(pipeline, Domain.class, repoIdRead);
  }

  /** Projects all resources to the current time and saves them. */
  private <T extends EppResource> void forceResaveAllResources(Pipeline pipeline, Class<T> clazz) {
    Read<String, String> repoIdRead =
        RegistryJpaIO.read(
                // Note: cannot use SQL parameters for the table name
                String.format("SELECT repoId FROM %s", clazz.getSimpleName()), String.class, r -> r)
            .withCoder(StringUtf8Coder.of());
    projectAndResaveResources(pipeline, clazz, repoIdRead);
  }

  /** Projects and re-saves all resources with repo IDs provided by the {@link Read}. */
  private <T extends EppResource> void projectAndResaveResources(
      Pipeline pipeline, Class<T> clazz, Read<?, String> repoIdRead) {
    int batchSize = options.getSqlWriteBatchSize();
    String className = clazz.getSimpleName();
    pipeline
        .apply("Read " + className, repoIdRead)
        .apply(
            "Shard data for class" + className,
            WithKeys.<Integer, String>of(0).withKeyType(integers()))
        .apply(
            "Group into batches for class" + className,
            GroupIntoBatches.<Integer, String>ofSize(batchSize).withShardedKey())
        .apply(
            "Load, map, and save " + className,
            ParDo.of(new BatchedLoadProjectAndSaveFunction(clazz)));
  }

  /** Function that loads, projects, and saves resources all in the same transaction. */
  private static class BatchedLoadProjectAndSaveFunction
      extends DoFn<KV<ShardedKey<Integer>, Iterable<String>>, Void> {

    private final Class<? extends EppResource> clazz;

    private BatchedLoadProjectAndSaveFunction(Class<? extends EppResource> clazz) {
      this.clazz = clazz;
    }

    @ProcessElement
    public void processElement(
        @Element KV<ShardedKey<Integer>, Iterable<String>> element,
        OutputReceiver<Void> outputReceiver) {
      tm().transact(
              () -> {
                DateTime now = tm().getTransactionTime();
                ImmutableList<VKey<? extends EppResource>> keys =
                    Streams.stream(element.getValue())
                        .map(repoId -> VKey.create(clazz, repoId))
                        .collect(toImmutableList());
                ImmutableList<EppResource> mappedResources =
                    tm().loadByKeys(keys).values().stream()
                        .map(r -> r.cloneProjectedAtTime(now))
                        .collect(toImmutableList());
                tm().putAll(mappedResources);
              });
    }
  }

  public static void main(String[] args) {
    PipelineOptionsFactory.register(ResaveAllEppResourcesPipelineOptions.class);
    ResaveAllEppResourcesPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args)
            .withValidation()
            .as(ResaveAllEppResourcesPipelineOptions.class);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ);
    new ResaveAllEppResourcesPipeline(options).run();
  }
}
