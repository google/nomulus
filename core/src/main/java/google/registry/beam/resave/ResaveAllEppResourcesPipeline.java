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

package google.registry.beam.resave;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.Host;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
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
      ImmutableSet.of(ContactResource.class, Domain.class, Host.class);

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
      "FROM Domain d WHERE (d.transferData.transferStatus = 'PENDING' AND"
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
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    if (options.getFast()) {
      fastResaveContacts(pipeline);
      fastResaveDomains(pipeline);
    } else {
      EPP_RESOURCE_CLASSES.forEach(clazz -> forceResaveAllResources(pipeline, clazz));
    }
  }

  /** Projects to the current time and saves any contacts with expired transfers. */
  private void fastResaveContacts(Pipeline pipeline) {
    Read<ContactResource, ContactResource> read =
        RegistryJpaIO.read(
            "FROM Contact WHERE transferData.transferStatus = 'PENDING' AND"
                + " transferData.pendingTransferExpirationTime < current_timestamp()",
            ContactResource.class,
            c -> c);
    projectAndResaveResources(pipeline, ContactResource.class, read);
  }

  /**
   * Projects to the current time and saves any domains with expired pending actions (e.g.
   * transfers, grace periods).
   *
   * <p>The logic of what might have changed is paraphrased from {@link
   * DomainBase#cloneProjectedAtTime(DateTime)}.
   */
  private void fastResaveDomains(Pipeline pipeline) {
    Read<Domain, Domain> read =
        RegistryJpaIO.read(
            DOMAINS_TO_PROJECT_QUERY,
            ImmutableMap.of("END_OF_TIME", DateTimeUtils.END_OF_TIME),
            Domain.class,
            d -> d);
    projectAndResaveResources(pipeline, Domain.class, read);
  }

  /** Projects all resources to the current time and saves them. */
  private <T extends EppResource> void forceResaveAllResources(Pipeline pipeline, Class<T> clazz) {
    Read<T, T> read = RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(clazz).build());
    projectAndResaveResources(pipeline, clazz, read);
  }

  /** Projects and re-saves the result of the provided {@link Read}. */
  private <T extends EppResource> void projectAndResaveResources(
      Pipeline pipeline, Class<T> clazz, Read<?, T> read) {
    int numShards = options.getSqlWriteShards();
    int batchSize = options.getSqlWriteBatchSize();
    String className = clazz.getSimpleName();
    pipeline
        .apply("Read " + className, read)
        .apply(
            "Shard data for class" + className,
            WithKeys.<Integer, T>of(e -> ThreadLocalRandom.current().nextInt(numShards))
                .withKeyType(integers()))
        .apply(
            "Group into batches for class" + className,
            GroupIntoBatches.<Integer, T>ofSize(batchSize).withShardedKey())
        .apply("Map " + className + " to now", ParDo.of(new BatchedProjectionFunction<>()))
        .apply(
            "Write transformed " + className,
            RegistryJpaIO.<EppResource>write()
                .withName("Write transformed " + className)
                .withBatchSize(batchSize)
                .withShards(numShards));
  }

  private static class BatchedProjectionFunction<T extends EppResource>
      extends DoFn<KV<ShardedKey<Integer>, Iterable<T>>, EppResource> {

    @ProcessElement
    public void processElement(
        @Element KV<ShardedKey<Integer>, Iterable<T>> element,
        OutputReceiver<EppResource> outputReceiver) {
      jpaTm()
          .transact(
              () ->
                  element
                      .getValue()
                      .forEach(
                          resource ->
                              outputReceiver.output(
                                  resource.cloneProjectedAtTime(jpaTm().getTransactionTime()))));
    }
  }
}
