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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.backup.VersionedEntity;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.initsql.Transforms.RemoveDomainBaseForeignKeys;
import google.registry.model.billing.BillingEvent;
import google.registry.model.common.Cursor;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import java.io.Serializable;
import java.util.Collection;
import java.util.Optional;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.Wait;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.joda.time.DateTime;

/**
 * A BEAM pipeline that populates a SQL database with data from a Datastore backup.
 *
 * <p>This pipeline migrates EPP resources and related entities that cross-reference each other. To
 * avoid violating foreign key constraints, writes to SQL are ordered by entity kinds. In addition,
 * the {@link DomainBase} kind is written twice (see details below). The write order is presented
 * below. Although some kinds can be written concurrently, e.g. {@code ContactResource} and {@code
 * RegistrarContact}, we do not expect any performance benefit since the limiting resource is the
 * number of JDBC connections. Google internal users may refer to <a
 * href="http://go/registry-r3-init-sql">the design doc</a> for more information.
 *
 * <ol>
 *   <li>{@link Registry}: Assumes that {@code PremiumList} and {@code ReservedList} have been set
 *       up in the SQL database.
 *   <li>{@link Cursor}: Logically can depend on {@code Registry}, but without foreign key.
 *   <li>{@link Registrar}: Logically depends on {@code Registry}, Foreign key not modeled yet.
 *   <li>{@link ContactResource}: references {@code Registrar}
 *   <li>{@link RegistrarContact}: references {@code Registrar}.
 *   <li>Cleansed {@link DomainBase}: with references to {@code BillingEvent}, {@code Recurring},
 *       {@code Cancellation} and {@code HostResource} removed, still references {@code Registrar}
 *       and {@code ContactResource}. The removal breaks circular Foreign Key references.
 *   <li>{@link HostResource}: references {@code DomainBase}.
 *   <li>{@link HistoryEntry}: maps to one of three SQL entity types and may reference {@code
 *       Registrar}, {@code ContactResource}, {@code HostResource}, and {@code DomainBase}.
 *   <li>{@link AllocationToken}: references {@code HistoryEntry}.
 *   <li>{@link BillingEvent.Recurring}: references {@code Registrar}, {@code DomainBase} and {@code
 *       HistoryEntry}.
 *   <li>{@link BillingEvent.OneTime}: references {@code Registrar}, {@code DomainBase}, {@code
 *       BillingEvent.Recurring}, {@code HistoryEntry} and {@code AllocationToken}.
 *   <li>{@link BillingEvent.Cancellation}: references {@code Registrar}, {@code DomainBase}, {@code
 *       BillingEvent.Recurring}, {@code BillingEvent.OneTime}, and {@code HistoryEntry}.
 *   <li>{@link PollMessage}: references {@code Registrar}, {@code DomainBase}, {@code
 *       ContactResource}, {@code HostResource}, and {@code HistoryEntry}.
 *   <li>{@link DomainBase}, original copy from Datastore.
 * </ol>
 *
 * <p>This pipeline expects that the source Datastore has at least one entity in each of the types
 * above. This assumption allows us to construct a simpler pipeline graph that can be visually
 * examined, and is true in all intended use cases. However, tests must not violate this assumption
 * when setting up data, otherwise they may run into foreign key constraint violations. The reason
 * is that this pipeline uses the {@link Wait} transform to order the persistence by entity type.
 * However, the wait is skipped if the target type has no data, resulting in subsequent entity types
 * starting prematurely. E.g., if a Datastore has no {@code RegistrarContact} entities, the pipeline
 * may start writing {@code DomainBase} entities before all {@code Registry}, {@code Registrar} and
 * {@code ContactResource} entities have been persisted.
 */
public class InitSqlPipeline implements Serializable {

  /**
   * Datastore kinds to be written to the SQL database before the cleansed version of {@link
   * DomainBase}.
   */
  private static final ImmutableList<Class<?>> PHASE_ONE_ORDERED =
      ImmutableList.of(
          Registry.class,
          Cursor.class,
          Registrar.class,
          ContactResource.class,
          RegistrarContact.class);

  /**
   * Datastore kinds to be written to the SQL database after the cleansed version of {@link
   * DomainBase}.
   */
  private static final ImmutableList<Class<?>> PHASE_TWO_ORDERED =
      ImmutableList.of(
          HostResource.class,
          HistoryEntry.class,
          AllocationToken.class,
          BillingEvent.Recurring.class,
          BillingEvent.OneTime.class,
          BillingEvent.Cancellation.class,
          PollMessage.class,
          DomainBase.class);

  private final InitSqlPipelineOptions options;

  InitSqlPipeline(InitSqlPipelineOptions options) {
    this.options = options;
  }

  PipelineResult run() {
    return run(Pipeline.create(options));
  }

  @VisibleForTesting
  PipelineResult run(Pipeline pipeline) {
    setupPipeline(pipeline);
    return pipeline.run();
  }

  @VisibleForTesting
  void setupPipeline(Pipeline pipeline) {
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_UNCOMMITTED);
    PCollectionTuple datastoreSnapshot =
        pipeline.apply(
            "Load Datastore snapshot",
            Transforms.loadDatastoreSnapshot(
                options.getDatastoreExportDir(),
                options.getCommitLogDir(),
                DateTime.parse(options.getCommitLogStartTimestamp()),
                DateTime.parse(options.getCommitLogEndTimestamp()),
                ImmutableSet.<String>builder()
                    .add("DomainBase")
                    .addAll(toKindStrings(PHASE_ONE_ORDERED))
                    .addAll(toKindStrings(PHASE_TWO_ORDERED))
                    .build()));

    // Set up the pipeline to write entity kinds from PHASE_ONE_ORDERED to SQL. Return a object
    // that signals the completion of the phase.
    PCollection<Void> blocker =
        scheduleOnePhaseWrites(datastoreSnapshot, PHASE_ONE_ORDERED, Optional.empty(), null);
    blocker =
        writeToSql(
            "DomainBase without circular foreign keys",
            removeDomainBaseForeignKeys(datastoreSnapshot)
                .apply("Wait on phase one", Wait.on(blocker)));
    // Set up the pipeline to write entity kinds from PHASE_TWO_ORDERED to SQL. This phase won't
    // start until all cleansed DomainBases have been written (started by line above).
    scheduleOnePhaseWrites(
        datastoreSnapshot, PHASE_TWO_ORDERED, Optional.of(blocker), "DomainBaseNoFkeys");
  }

  private PCollection<VersionedEntity> removeDomainBaseForeignKeys(
      PCollectionTuple datastoreSnapshot) {
    PCollection<VersionedEntity> domainBases =
        datastoreSnapshot.get(Transforms.createTagForKind("DomainBase"));
    return domainBases.apply(
        "Remove circular foreign keys from DomainBase",
        ParDo.of(new RemoveDomainBaseForeignKeys()));
  }

  /**
   * Sets up the pipeline to write entities in {@code entityClasses} to SQL. Entities are written
   * one kind at a time based on each kind's position in {@code entityClasses}. Concurrency exists
   * within each kind.
   *
   * @param datastoreSnapshot the Datastore snapshot of all data to be migrated to SQL
   * @param entityClasses the entity types in write order
   * @param blockingPCollection the pipeline stage that blocks this phase
   * @param blockingTag description of the stage (if exists) that blocks this phase. Needed for
   *     generating unique transform ids
   * @return the output {@code PCollection} from the writing of the last entity kind. Other parts of
   *     the pipeline can {@link Wait} on this object
   */
  private PCollection<Void> scheduleOnePhaseWrites(
      PCollectionTuple datastoreSnapshot,
      Collection<Class<?>> entityClasses,
      Optional<PCollection<Void>> blockingPCollection,
      String blockingTag) {
    checkArgument(!entityClasses.isEmpty(), "Each phase must have at least one kind.");
    ImmutableList<TupleTag<VersionedEntity>> tags =
        toKindStrings(entityClasses).stream()
            .map(Transforms::createTagForKind)
            .collect(ImmutableList.toImmutableList());

    PCollection<Void> prev = blockingPCollection.orElse(null);
    String prevTag = blockingTag;
    for (TupleTag<VersionedEntity> tag : tags) {
      PCollection<VersionedEntity> curr = datastoreSnapshot.get(tag);
      if (prev != null) {
        curr = curr.apply("Wait on " + prevTag, Wait.on(prev));
      }
      prev = writeToSql(tag.getId(), curr);
      prevTag = tag.getId();
    }
    return prev;
  }

  private PCollection<Void> writeToSql(String transformId, PCollection<VersionedEntity> data) {
    return data.apply(
        "Write to Sql: " + transformId,
        RegistryJpaIO.<VersionedEntity>write()
            .withName(transformId)
            .withBatchSize(options.getSqlWriteBatchSize())
            .withShards(options.getSqlWriteShards())
            .withJpaConverter(Transforms::convertVersionedEntityToSqlEntity)
            .disableUpdateAutoTimestamp());
  }

  private static ImmutableList<String> toKindStrings(Collection<Class<?>> entityClasses) {
    return entityClasses.stream().map(Key::getKind).collect(ImmutableList.toImmutableList());
  }

  public static void main(String[] args) {
    InitSqlPipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(InitSqlPipelineOptions.class);
    new InitSqlPipeline(options).run();
  }
}
