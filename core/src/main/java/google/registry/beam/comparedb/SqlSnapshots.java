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

package google.registry.beam.comparedb;

import static com.google.common.base.Preconditions.checkState;
import static google.registry.beam.comparedb.ValidateSqlUtils.createSqlEntityTupleTag;
import static google.registry.beam.comparedb.ValidateSqlUtils.getMedianIdForHistoryTable;

import com.google.auto.value.AutoValue;
import com.google.common.base.Strings;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Streams;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryJpaIO.Read;
import google.registry.model.EppResource;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.model.annotations.DeleteAfterMigration;
import google.registry.model.billing.BillingEvent;
import google.registry.model.bulkquery.BulkQueryEntities;
import google.registry.model.bulkquery.DomainBaseLite;
import google.registry.model.bulkquery.DomainHistoryHost;
import google.registry.model.bulkquery.DomainHistoryLite;
import google.registry.model.bulkquery.DomainHost;
import google.registry.model.common.Cursor;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarContact;
import google.registry.model.replay.SqlEntity;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.tld.Registry;
import google.registry.persistence.transaction.CriteriaQueryBuilder;
import google.registry.util.DateTimeUtils;
import java.io.Serializable;
import java.util.Optional;
import javax.persistence.Entity;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.DateTime;

/**
 * Utilities for loading SQL snapshots.
 *
 * <p>For {@link DomainBase} and {@link DomainHistory}, this class assumes the presence of the
 * {@link google.registry.persistence.PersistenceModule.JpaTransactionManagerType#BULK_QUERY
 * bulk-query-capable JpaTransactionManager}, and takes advantage of it for higher throughput.
 *
 * <p>For now this class is meant for use during the database migration period only. Therefore, it
 * contains optimizations specifically for the production database at the current size, e.g.,
 * parallel queries for select tables.
 */
@DeleteAfterMigration
public final class SqlSnapshots {

  private SqlSnapshots() {}

  /**
   * SQL entity types that are eligible for validation. This set must be consistent with {@link
   * DatastoreSnapshots#ALL_DATASTORE_KINDS}.
   */
  static final ImmutableSet<Class<? extends SqlEntity>> ALL_SQL_ENTITIES =
      ImmutableSet.of(
          Registry.class,
          Cursor.class,
          Registrar.class,
          ContactResource.class,
          RegistrarContact.class,
          HostResource.class,
          AllocationToken.class,
          BillingEvent.Recurring.class,
          BillingEvent.OneTime.class,
          BillingEvent.Cancellation.class,
          PollMessage.class,
          DomainBase.class,
          ContactHistory.class,
          HostHistory.class,
          DomainHistory.class);

  /**
   * Loads a SQL snapshot for the given {@code sqlEntityTypes}.
   *
   * <p>If {@code snapshotId} is present, all queries use the specified database snapshot,
   * guaranteeing a consistent result.
   */
  public static PCollectionTuple loadCloudSqlSnapshotByType(
      Pipeline pipeline,
      ImmutableSet<Class<? extends SqlEntity>> sqlEntityTypes,
      Optional<String> snapshotId,
      Optional<DateTime> compareStartTime) {
    PCollectionTuple perTypeSnapshots = PCollectionTuple.empty(pipeline);
    for (Class<? extends SqlEntity> clazz : sqlEntityTypes) {
      if (clazz == DomainBase.class) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag(DomainBase.class),
                loadAndAssembleDomainBase(pipeline, snapshotId, compareStartTime));
        continue;
      }
      if (clazz == DomainHistory.class) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag(DomainHistory.class),
                loadAndAssembleDomainHistory(pipeline, snapshotId, compareStartTime));
        continue;
      }
      if (clazz == ContactHistory.class) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag(ContactHistory.class),
                loadContactHistory(pipeline, snapshotId, compareStartTime));
        continue;
      }
      if (clazz == HostHistory.class) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag(HostHistory.class),
                loadHostHistory(
                    pipeline, snapshotId, compareStartTime.orElse(DateTimeUtils.START_OF_TIME)));
        continue;
      }
      if (EppResource.class.isAssignableFrom(clazz) && compareStartTime.isPresent()) {
        perTypeSnapshots =
            perTypeSnapshots.and(
                createSqlEntityTupleTag(clazz),
                pipeline.apply(
                    "SQL Load " + clazz.getSimpleName(),
                    buildEppResourceQueryWithTimeFilter(
                            clazz, SqlEntity.class, snapshotId, compareStartTime.get())
                        .withSnapshot(snapshotId.orElse(null))));
        continue;
      }
      perTypeSnapshots =
          perTypeSnapshots.and(
              createSqlEntityTupleTag(clazz),
              pipeline.apply(
                  "SQL Load " + clazz.getSimpleName(),
                  RegistryJpaIO.read(
                          () -> CriteriaQueryBuilder.create(clazz).build(), SqlEntity.class::cast)
                      .withSnapshot(snapshotId.orElse(null))));
    }
    return perTypeSnapshots;
  }

  /**
   * Bulk-loads parts of {@link DomainBase} and assembles them in the pipeline.
   *
   * @see BulkQueryEntities
   */
  public static PCollection<SqlEntity> loadAndAssembleDomainBase(
      Pipeline pipeline, Optional<String> snapshotId, Optional<DateTime> compareStartTime) {
    PCollection<KV<String, Serializable>> baseObjects =
        readAllAndAssignKey(
            pipeline,
            DomainBaseLite.class,
            DomainBaseLite::getRepoId,
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> gracePeriods =
        readAllAndAssignKey(
            pipeline,
            GracePeriod.class,
            GracePeriod::getDomainRepoId,
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> delegationSigners =
        readAllAndAssignKey(
            pipeline,
            DelegationSignerData.class,
            DelegationSignerData::getDomainRepoId,
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> domainHosts =
        readAllAndAssignKey(
            pipeline, DomainHost.class, DomainHost::getDomainRepoId, snapshotId, compareStartTime);

    DateTime nullableCompareStartTime = compareStartTime.orElse(null);
    return PCollectionList.of(
            ImmutableList.of(baseObjects, gracePeriods, delegationSigners, domainHosts))
        .apply("SQL Merge DomainBase parts", Flatten.pCollections())
        .apply("Group by Domain Parts by RepoId", GroupByKey.create())
        .apply(
            "Assemble DomainBase",
            ParDo.of(
                new DoFn<KV<String, Iterable<Serializable>>, SqlEntity>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<String, Iterable<Serializable>> kv,
                      OutputReceiver<SqlEntity> outputReceiver) {
                    TypedClassifier partsByType = new TypedClassifier(kv.getValue());
                    ImmutableSet<DomainBaseLite> baseObjects =
                        partsByType.getAllOf(DomainBaseLite.class);
                    if (nullableCompareStartTime != null) {
                      Verify.verify(
                          baseObjects.size() <= 1,
                          "Found duplicate DomainBaseLite object per repoId: " + kv.getKey());
                      if (baseObjects.isEmpty()) {
                        return;
                      }
                    }
                    Verify.verify(
                        baseObjects.size() == 1,
                        "Expecting one DomainBaseLite object per repoId: " + kv.getKey());
                    outputReceiver.output(
                        BulkQueryEntities.assembleDomainBase(
                            baseObjects.iterator().next(),
                            partsByType.getAllOf(GracePeriod.class),
                            partsByType.getAllOf(DelegationSignerData.class),
                            partsByType.getAllOf(DomainHost.class).stream()
                                .map(DomainHost::getHostVKey)
                                .collect(ImmutableSet.toImmutableSet())));
                  }
                }));
  }

  /**
   * Loads all {@link ContactHistory} entities from the database.
   *
   * <p>This method uses two queries to load data in parallel. This is a performance optimization
   * specifically for the production database.
   */
  static PCollection<SqlEntity> loadContactHistory(
      Pipeline pipeline, Optional<String> snapshotId, Optional<DateTime> compareStartTime) {
    PartitionedQuery partitionedQuery =
        buildPartitonedHistoryQuery(ContactHistory.class, compareStartTime);
    PCollection<SqlEntity> part1 =
        pipeline.apply(
            "SQL Load ContactHistory first half",
            RegistryJpaIO.read(
                    partitionedQuery.firstHalfQuery(),
                    partitionedQuery.parameters(),
                    false,
                    SqlEntity.class::cast)
                .withSnapshot(snapshotId.orElse(null)));
    PCollection<SqlEntity> part2 =
        pipeline.apply(
            "SQL Load ContactHistory second half",
            RegistryJpaIO.read(
                    partitionedQuery.secondHalfQuery(),
                    partitionedQuery.parameters(),
                    false,
                    SqlEntity.class::cast)
                .withSnapshot(snapshotId.orElse(null)));
    return PCollectionList.of(part1)
        .and(part2)
        .apply("Combine ContactHistory parts", Flatten.pCollections());
  }

  /** Loads all {@link HostHistory} entities from the database. */
  static PCollection<SqlEntity> loadHostHistory(
      Pipeline pipeline, Optional<String> snapshotId, DateTime compareStartTime) {
    return pipeline.apply(
        "SQL Load HostHistory",
        RegistryJpaIO.read(
                "select c from HostHistory c where :compareStartTime  < modificationTime",
                ImmutableMap.of("compareStartTime", compareStartTime),
                false,
                SqlEntity.class::cast)
            .withSnapshot(snapshotId.orElse(null)));
  }

  /**
   * Bulk-loads all parts of {@link DomainHistory} and assembles them in the pipeline.
   *
   * <p>This method uses two queries to load {@link DomainBaseLite} in parallel. This is a
   * performance optimization specifically for the production database.
   *
   * @see BulkQueryEntities
   */
  static PCollection<SqlEntity> loadAndAssembleDomainHistory(
      Pipeline pipeline, Optional<String> snapshotId, Optional<DateTime> compareStartTime) {
    PartitionedQuery partitionedQuery =
        buildPartitonedHistoryQuery(DomainHistoryLite.class, compareStartTime);
    PCollection<KV<String, Serializable>> baseObjectsPart1 =
        queryAndAssignKey(
            pipeline,
            "first half",
            partitionedQuery.firstHalfQuery(),
            partitionedQuery.parameters(),
            DomainHistoryLite.class,
            compose(DomainHistoryLite::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId);
    PCollection<KV<String, Serializable>> baseObjectsPart2 =
        queryAndAssignKey(
            pipeline,
            "second half",
            partitionedQuery.secondHalfQuery(),
            partitionedQuery.parameters(),
            DomainHistoryLite.class,
            compose(DomainHistoryLite::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId);
    PCollection<KV<String, Serializable>> gracePeriods =
        readAllAndAssignKey(
            pipeline,
            GracePeriodHistory.class,
            compose(GracePeriodHistory::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> delegationSigners =
        readAllAndAssignKey(
            pipeline,
            DomainDsDataHistory.class,
            compose(DomainDsDataHistory::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> domainHosts =
        readAllAndAssignKey(
            pipeline,
            DomainHistoryHost.class,
            compose(DomainHistoryHost::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId,
            compareStartTime);
    PCollection<KV<String, Serializable>> transactionRecords =
        readAllAndAssignKey(
            pipeline,
            DomainTransactionRecord.class,
            compose(DomainTransactionRecord::getDomainHistoryId, DomainHistoryId::toString),
            snapshotId,
            compareStartTime);

    DateTime nullableCompareStartTime = compareStartTime.orElse(null);
    return PCollectionList.of(
            ImmutableList.of(
                baseObjectsPart1,
                baseObjectsPart2,
                gracePeriods,
                delegationSigners,
                domainHosts,
                transactionRecords))
        .apply("Merge DomainHistory parts", Flatten.pCollections())
        .apply("Group by DomainHistory Parts by DomainHistoryId string", GroupByKey.create())
        .apply(
            "Assemble DomainHistory",
            ParDo.of(
                new DoFn<KV<String, Iterable<Serializable>>, SqlEntity>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<String, Iterable<Serializable>> kv,
                      OutputReceiver<SqlEntity> outputReceiver) {
                    TypedClassifier partsByType = new TypedClassifier(kv.getValue());
                    ImmutableSet<DomainHistoryLite> baseObjects =
                        partsByType.getAllOf(DomainHistoryLite.class);
                    if (nullableCompareStartTime != null) {
                      Verify.verify(
                          baseObjects.size() <= 1,
                          "Found duplicate DomainHistoryLite object per domainHistoryId: "
                              + kv.getKey());
                      if (baseObjects.isEmpty()) {
                        return;
                      }
                    }
                    Verify.verify(
                        baseObjects.size() == 1,
                        "Expecting one DomainHistoryLite object per domainHistoryId: "
                            + kv.getKey());
                    outputReceiver.output(
                        BulkQueryEntities.assembleDomainHistory(
                            baseObjects.iterator().next(),
                            partsByType.getAllOf(DomainDsDataHistory.class),
                            partsByType.getAllOf(DomainHistoryHost.class).stream()
                                .map(DomainHistoryHost::getHostVKey)
                                .collect(ImmutableSet.toImmutableSet()),
                            partsByType.getAllOf(GracePeriodHistory.class),
                            partsByType.getAllOf(DomainTransactionRecord.class)));
                  }
                }));
  }

  static <R, T> PCollection<KV<String, Serializable>> readAllAndAssignKey(
      Pipeline pipeline,
      Class<R> type,
      SerializableFunction<R, String> keyFunction,
      Optional<String> snapshotId,
      Optional<DateTime> compareStartTime) {
    Read<R, R> queryObject;
    if (compareStartTime.isPresent() && EppResource.class.isAssignableFrom(type)) {
      queryObject =
          buildEppResourceQueryWithTimeFilter(type, type, snapshotId, compareStartTime.get());
    } else {
      queryObject =
          RegistryJpaIO.read(() -> CriteriaQueryBuilder.create(type).build())
              .withSnapshot(snapshotId.orElse(null));
    }
    return pipeline
        .apply("SQL Load " + type.getSimpleName(), queryObject)
        .apply(
            "Assign Key to " + type.getSimpleName(),
            MapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(), TypeDescriptor.of(Serializable.class)))
                .via(obj -> KV.of(keyFunction.apply(obj), (Serializable) obj)));
  }

  static <R, T> PCollection<KV<String, Serializable>> queryAndAssignKey(
      Pipeline pipeline,
      String diffrentiator,
      String jplQuery,
      ImmutableMap<String, Object> queryParameters,
      Class<R> type,
      SerializableFunction<R, String> keyFunction,
      Optional<String> snapshotId) {
    return pipeline
        .apply(
            "SQL Load " + type.getSimpleName() + " " + diffrentiator,
            RegistryJpaIO.read(jplQuery, queryParameters, false, type::cast)
                .withSnapshot(snapshotId.orElse(null)))
        .apply(
            "Assign Key to " + type.getSimpleName() + " " + diffrentiator,
            MapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptors.strings(), TypeDescriptor.of(Serializable.class)))
                .via(obj -> KV.of(keyFunction.apply(obj), (Serializable) obj)));
  }

  // TODO(b/205988530): don't use beam serializablefunction, make one that extends Java's Function.
  private static <R, I, T> SerializableFunction<R, T> compose(
      SerializableFunction<R, I> f1, SerializableFunction<I, T> f2) {
    return r -> f2.apply(f1.apply(r));
  }

  static <R, T> Read<R, T> buildEppResourceQueryWithTimeFilter(
      Class<R> entityType,
      Class<T> castOutputAsType,
      Optional<String> snapshotId,
      DateTime compareStartTime) {
    String tableName = getJpaEntityName(entityType);
    String jpql =
        String.format("select c from %s c where :compareStartTime < updateTimestamp", tableName);
    return RegistryJpaIO.read(
            jpql,
            ImmutableMap.of("compareStartTime", UpdateAutoTimestamp.create(compareStartTime)),
            false,
            (R x) -> castOutputAsType.cast(x))
        .withSnapshot(snapshotId.orElse(null));
  }

  static PartitionedQuery buildPartitonedHistoryQuery(
      Class<?> entityType, Optional<DateTime> compareStartTime) {
    String tableName = getJpaEntityName(entityType);
    Verify.verify(
        !Strings.isNullOrEmpty(tableName), "Invalid entity type %s", entityType.getSimpleName());
    long medianId =
        getMedianIdForHistoryTable(tableName)
            .orElseThrow(() -> new IllegalStateException("Not a valid database: no " + tableName));
    String firstHalfQuery = String.format("select c from %s c where id <= :historyId", tableName);
    String secondHalfQuery = String.format("select c from %s c where id > :historyId", tableName);
    if (compareStartTime.isPresent()) {
      String timeFilter = "  and :compareStartTime  < modificationTime";
      firstHalfQuery += timeFilter;
      secondHalfQuery += timeFilter;
      return PartitionedQuery.createPartitionedQuery(
          firstHalfQuery,
          secondHalfQuery,
          ImmutableMap.of("historyId", medianId, "compareStartTime", compareStartTime.get()));
    } else {
      return PartitionedQuery.createPartitionedQuery(
          firstHalfQuery, secondHalfQuery, ImmutableMap.of("historyId", medianId));
    }
  }

  private static String getJpaEntityName(Class entityType) {
    Entity entityAnnotation = (Entity) entityType.getAnnotation(Entity.class);
    checkState(
        entityAnnotation != null, "Unexpected non-entity type %s", entityType.getSimpleName());
    return Strings.isNullOrEmpty(entityAnnotation.name())
        ? entityType.getSimpleName()
        : entityAnnotation.name();
  }

  /** Contains two queries that partition the target table in two. */
  @AutoValue
  abstract static class PartitionedQuery {
    abstract String firstHalfQuery();

    abstract String secondHalfQuery();

    abstract ImmutableMap<String, Object> parameters();

    public static PartitionedQuery createPartitionedQuery(
        String firstHalfQuery, String secondHalfQuery, ImmutableMap<String, Object> parameters) {
      return new AutoValue_SqlSnapshots_PartitionedQuery(
          firstHalfQuery, secondHalfQuery, parameters);
    }
  }

  /** Container that receives mixed-typed data and groups them by {@link Class}. */
  static class TypedClassifier {
    private final ImmutableSetMultimap<Class<?>, Object> classifiedEntities;

    TypedClassifier(Iterable<Serializable> inputs) {
      this.classifiedEntities =
          Streams.stream(inputs)
              .collect(ImmutableSetMultimap.toImmutableSetMultimap(Object::getClass, x -> x));
    }

    <T> ImmutableSet<T> getAllOf(Class<T> clazz) {
      return classifiedEntities.get(clazz).stream()
          .map(clazz::cast)
          .collect(ImmutableSet.toImmutableSet());
    }
  }
}
