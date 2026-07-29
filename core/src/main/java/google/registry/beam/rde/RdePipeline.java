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

package google.registry.beam.rde;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.beam.rde.RdePipeline.TupleTags.DOMAIN_FRAGMENTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.EXTERNAL_HOST_FRAGMENTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID;
import static google.registry.beam.rde.RdePipeline.TupleTags.PENDING_DEPOSIT;
import static google.registry.beam.rde.RdePipeline.TupleTags.REFERENCED_HOSTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.REVISION_ID;
import static google.registry.beam.rde.RdePipeline.TupleTags.SUPERORDINATE_DOMAINS;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.SafeSerializationUtils.safeDeserializeCollection;
import static google.registry.util.SafeSerializationUtils.serializeCollection;
import static google.registry.util.SerializeUtils.decodeBase64;
import static google.registry.util.SerializeUtils.encodeBase64;
import static org.apache.beam.sdk.values.TypeDescriptors.integers;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.io.BaseEncoding;
import dagger.BindsInstance;
import dagger.Component;
import google.registry.batch.CloudTasksUtils;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.EppResource;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.Host;
import google.registry.model.host.HostHistory;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import google.registry.rde.DepositFragment;
import google.registry.rde.PendingDeposit;
import google.registry.rde.PendingDeposit.PendingDepositCoder;
import google.registry.rde.RdeMarshaller;
import google.registry.util.UtilsModule;
import google.registry.xml.ValidationMode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.FlatMapElements;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.GroupIntoBatches;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.WithKeys;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.hibernate.jpa.AvailableHints;

/**
 * Definition of a Dataflow Flex template, which generates RDE/BRDA deposits.
 *
 * <p>To stage this template locally, run {@code ./nom_build :core:sBP --environment=alpha
 * --pipeline=rde}.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * <p>This pipeline only works for pending deposits with the same watermark, the {@link
 * google.registry.rde.RdeStagingAction} will batch such pending deposits together and launch
 * multiple pipelines if multiple watermarks exist.
 *
 * <p>The pipeline is broadly divided into two parts -- creating the {@link DepositFragment}s, and
 * processing them.
 *
 * <h2>Creating {@link DepositFragment}</h2>
 *
 * <h3>{@link Registrar}</h3>
 *
 * Non-test registrar entities are loaded from Cloud SQL and marshalled into deposit fragments. They
 * are <b>NOT</b> rewound to the watermark.
 *
 * <h3>{@link EppResource}</h3>
 *
 * All EPP resources are loaded from the corresponding {@link HistoryEntry}, which has the resource
 * embedded. In general, we find most recent history entry before watermark and filter out the ones
 * that are soft-deleted by watermark. The history is emitted as pairs of (resource repo ID: history
 * revision ID) from the SQL query.
 *
 * <h3>{@link Domain}</h3>
 *
 * After the most recent (live) domain resources are loaded from the corresponding history objects,
 * we marshal them to deposit fragments and emit the (pending deposit: deposit fragment) pairs for
 * further processing. We also find all the hosts referenced by a given domain and emit pairs of
 * (host repo ID: pending deposit) for all RDE pending deposits for further processing.
 *
 * <h3>{@link Host}</h3>
 *
 * <p>We first join most recent host histories, represented by (host repo ID: host history revision
 * ID) pairs, with referenced hosts, represented by (host repo ID: pending deposit) pairs, on the
 * host repo ID, to remove unreferenced host histories. Host resources are then loaded from the
 * remaining referenced host histories, and marshalled into (pending deposit: deposit fragment)
 * pairs.
 *
 * <p>For subordinate hosts, we need to find the superordinate domain in order to properly handle
 * pending transfer in the deposit as well. So we first find the superordinate domain repo ID from
 * the host and join the (superordinate domain repo ID: (subordinate host repo ID: (pending
 * deposits: revision IDs))) pair with the (domain repo ID: revision ID) pair obtained from the
 * domain history query in order to map the host at watermark to the domain at watermark. We then
 * proceed to create the (pending deposit: deposit fragment) pair for subordinate hosts using the
 * added domain information.
 *
 * <h2>Processing {@link DepositFragment}</h2>
 *
 * The (pending deposit: deposit fragment) pairs from different resources are combined and grouped
 * by pending deposit. For each pending deposit, all the relevant deposit fragments are written into
 * an encrypted file stored on GCS. The filename is uniquely determined by the Beam job ID so there
 * is no need to lock the GCS write operation to prevent stomping. The cursor for staging the
 * pending deposit is then rolled forward, and the next action is enqueued. The latter two
 * operations are performed in a transaction so the cursor is rolled back if enqueueing failed.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
@Singleton
public class RdePipeline implements Serializable {

  private static final long serialVersionUID = -4866795928854754666L;
  private final transient RdePipelineOptions options;
  private final ValidationMode mode;
  private final ImmutableSet<PendingDeposit> pendingDeposits;
  private final Instant watermark;
  private final String rdeBucket;
  private final byte[] stagingKeyBytes;
  private final GcsUtils gcsUtils;
  private final CloudTasksUtils cloudTasksUtils;
  private final RdeMarshaller marshaller;

  // Registrars to be excluded from data escrow (i.e. all registrar types that have a null IANA
  // identifier and thus would not be valid according to the RDE schema).
  private static final ImmutableSet<Type> IGNORED_REGISTRAR_TYPES =
      Sets.immutableEnumSet(Registrar.Type.MONITORING, Registrar.Type.OTE, Registrar.Type.TEST);

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Metrics counters for tracking pipeline execution.
  private static final Counter INCLUDED_REGISTRAR_COUNTER =
      Metrics.counter("RDE", "IncludedRegistrar");
  private static final Counter REGISTRAR_FRAGMENT_COUNTER =
      Metrics.counter("RDE", "RegistrarFragment");
  private static final Counter REFERENCED_HOST_COGBK_COUNTER =
      Metrics.counter("RDE", "ReferencedHost");
  private static final Counter SUBORDINATE_HOST_COUNTER = Metrics.counter("RDE", "SubordinateHost");
  private static final Counter EXTERNAL_HOST_COUNTER = Metrics.counter("RDE", "ExternalHost");
  private static final Counter EXTERNAL_HOST_FRAGMENT_COUNTER =
      Metrics.counter("RDE", "ExternalHostFragment");
  private static final Counter SUBORDINATE_HOST_FRAGMENT_COUNTER =
      Metrics.counter("RDE", "SubordinateHostFragment");
  private static final Counter REFERENCED_SUBORDINATE_HOST_COUNTER =
      Metrics.counter("RDE", "ReferencedSubordinateHost");
  private static final Counter ACTIVE_DOMAIN_COUNTER = Metrics.counter("RDE", "ActiveDomain");
  private static final Counter DOMAIN_FRAGMENT_COUNTER = Metrics.counter("RDE", "DomainFragment");
  private static final Counter REFERENCED_HOST_COUNTER = Metrics.counter("RDE", "ReferencedHost");

  @Inject
  RdePipeline(RdePipelineOptions options, GcsUtils gcsUtils, CloudTasksUtils cloudTasksUtils) {
    this.options = options;
    this.mode = ValidationMode.valueOf(options.getValidationMode());
    this.pendingDeposits = decodePendingDeposits(options.getPendings());
    ImmutableSet<Instant> potentialWatermarks =
        pendingDeposits.stream()
            .map(PendingDeposit::watermark)
            .distinct()
            .collect(toImmutableSet());
    checkArgument(
        potentialWatermarks.size() == 1,
        String.format(
            "RDE pipeline should only work on pending deposits "
                + "with the same watermark, but %d were given: %s",
            potentialWatermarks.size(), potentialWatermarks));
    this.watermark = potentialWatermarks.asList().get(0);
    this.rdeBucket = options.getRdeStagingBucket();
    this.stagingKeyBytes = BaseEncoding.base64Url().decode(options.getStagingKey());
    this.gcsUtils = gcsUtils;
    this.cloudTasksUtils = cloudTasksUtils;
    this.marshaller = new RdeMarshaller(mode);
  }

  PipelineResult run() {
    Pipeline pipeline = Pipeline.create(options);
    PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> fragments =
        createFragments(pipeline);
    persistData(fragments);
    return pipeline.run();
  }

  PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> createFragments(Pipeline pipeline) {
    PCollection<KV<PendingDeposit, DepositFragment>> registrarFragments =
        processRegistrars(pipeline);

    PCollection<KV<String, Long>> domainHistories =
        getMostRecentHistoryEntries(pipeline, DomainHistory.class);

    PCollection<KV<String, Long>> hostHistories =
        getMostRecentHistoryEntries(pipeline, HostHistory.class);

    PCollectionTuple processedDomainHistories = processDomainHistories(domainHistories);

    PCollection<KV<PendingDeposit, DepositFragment>> domainFragments =
        processedDomainHistories.get(DOMAIN_FRAGMENTS);

    PCollectionTuple processedHosts =
        processHostHistories(processedDomainHistories.get(REFERENCED_HOSTS), hostHistories);

    PCollection<KV<PendingDeposit, DepositFragment>> externalHostFragments =
        processedHosts.get(EXTERNAL_HOST_FRAGMENTS);

    PCollection<KV<PendingDeposit, DepositFragment>> subordinateHostFragments =
        processSubordinateHosts(processedHosts.get(SUPERORDINATE_DOMAINS), domainHistories);

    return PCollectionList.of(registrarFragments)
        .and(domainFragments)
        .and(externalHostFragments)
        .and(subordinateHostFragments)
        .apply(
            "Combine PendingDeposit:DepositFragment pairs from all entities",
            Flatten.pCollections())
        .setCoder(KvCoder.of(PendingDepositCoder.of(), SerializableCoder.of(DepositFragment.class)))
        .apply("Group DepositFragment by PendingDeposit", GroupByKey.create());
  }

  void persistData(PCollection<KV<PendingDeposit, Iterable<DepositFragment>>> input) {
    input.apply(
        "Write to GCS, update cursors, and enqueue upload tasks",
        RdeIO.Write.builder()
            .setRdeBucket(rdeBucket)
            .setGcsUtils(gcsUtils)
            .setCloudTasksUtils(cloudTasksUtils)
            .setValidationMode(mode)
            .setStagingKeyBytes(stagingKeyBytes)
            .build());
  }

  private PCollection<KV<PendingDeposit, DepositFragment>> processRegistrars(Pipeline pipeline) {
    return pipeline
        .apply(
            "Read all production Registrars",
            RegistryJpaIO.read(
                    "SELECT registrarId FROM Registrar WHERE type NOT IN (:types)",
                    ImmutableMap.of("types", IGNORED_REGISTRAR_TYPES),
                    String.class,
                    x -> x)
                .withCoder(StringUtf8Coder.of()))
        .apply(
            "Marshall Registrar into DepositFragment",
            FlatMapElements.into(
                    kvs(
                        TypeDescriptor.of(PendingDeposit.class),
                        TypeDescriptor.of(DepositFragment.class)))
                .via(
                    (String registrarRepoId) -> {
                      VKey<Registrar> key = VKey.create(Registrar.class, registrarRepoId);
                      INCLUDED_REGISTRAR_COUNTER.inc();
                      Registrar registrar = tm().transact(() -> tm().loadByKey(key));
                      DepositFragment fragment = marshaller.marshalRegistrar(registrar);
                      ImmutableSet<KV<PendingDeposit, DepositFragment>> fragments =
                          pendingDeposits.stream()
                              .map(pending -> KV.of(pending, fragment))
                              .collect(toImmutableSet());
                      REGISTRAR_FRAGMENT_COUNTER.inc(fragments.size());
                      return fragments;
                    }));
  }

  /**
   * Load the most recent history entry before the watermark for a given history entry type.
   *
   * <p>Note that deleted and non-production resources are not included.
   *
   * @return A collection of (repoId -> revisionId) used to reconstruct the composite key for the
   *     history entry.
   */
  private <T extends HistoryEntry> PCollection<KV<String, Long>> getMostRecentHistoryEntries(
      Pipeline pipeline, Class<T> historyClass) {
    String tldFilter =
        historyClass == DomainHistory.class
            ? " AND sub.resource.tld IN (SELECT id FROM Tld WHERE tldType = 'REAL')"
            : "";
    String jpql =
        String.format(
            """
            SELECT repoId, revisionId FROM %1$s WHERE (repoId, modificationTime) IN (
                SELECT sub.repoId, MAX(sub.modificationTime) FROM %1$s sub
                WHERE sub.modificationTime <= :watermark%2$s
                GROUP BY sub.repoId
            )
            AND resource.deletionTime > :watermark
            AND COALESCE(resource.creationRegistrarId, '') NOT LIKE 'prober-%%'
            AND COALESCE(resource.currentSponsorRegistrarId, '') NOT LIKE 'prober-%%'
            AND COALESCE(resource.lastEppUpdateRegistrarId, '') NOT LIKE 'prober-%%'
            """,
            historyClass.getSimpleName(), tldFilter);
    return pipeline.apply(
        String.format("Load most recent %s", historyClass.getSimpleName()),
        RegistryJpaIO.read(
                jpql,
                ImmutableMap.of("watermark", watermark),
                Object[].class,
                row -> KV.of((String) row[0], (long) row[1]))
            .withCoder(KvCoder.of(StringUtf8Coder.of(), VarLongCoder.of())));
  }

  private static long getSingleRevisionId(
      Class<? extends HistoryEntry> historyEntryClazz, String repoId, Iterable<Long> revisionIds) {
    ImmutableList<Long> ids = ImmutableList.copyOf(revisionIds);
    // The SQL query in getMostRecentHistoryEntries guarantees exactly one (repoId, revisionId) pair
    // per entity. However, after multi-way joins via CoGroupByKey (e.g. when joining pending
    // deposits or subordinate hosts on repoId), duplicate identical revision IDs can appear in
    // the resulting Iterable<Long>.
    //
    // We deduplicate the iterable here. If it contains multiple revision IDs that are NOT
    // identical, we have an illegal state because we cannot determine which historical revision is
    // authoritative at the watermark. In that case, we abort and require manual intervention.
    checkArgument(
        !ids.isEmpty(),
        "No revision IDs found for %s repo ID %s",
        historyEntryClazz.getSimpleName(),
        repoId);
    if (ids.size() != 1) {
      ImmutableSet<Long> dedupedIds = ImmutableSet.copyOf(ids);
      checkState(
          dedupedIds.size() == 1,
          "Multiple unique revision IDs detected for %s repo ID %s: %s",
          historyEntryClazz.getSimpleName(),
          repoId,
          ids);
      logger.atInfo().log(
          "Duplicate revision IDs detected for %s repo ID %s: %s",
          historyEntryClazz.getSimpleName(), repoId, ids);
    }
    return ids.getFirst();
  }

  static <E extends EppResource, H extends HistoryEntry>
      ImmutableMap<String, E> loadResourcesByHistoryEntryIds(
          Iterable<KV<String, Long>> repoAndRevisionIds,
          Class<E> resourceClass,
          Class<H> historyEntryClass,
          Instant watermark) {
    ImmutableList<KV<String, Long>> ids = ImmutableList.copyOf(repoAndRevisionIds);
    if (ids.isEmpty()) {
      return ImmutableMap.of();
    }
    String[] repoIdArray = ids.stream().map(KV::getKey).toArray(String[]::new);
    Long[] revisionIdArray = ids.stream().map(KV::getValue).toArray(Long[]::new);
    String repoIdColumnName =
        historyEntryClass.equals(DomainHistory.class) ? "domain_repo_id" : "host_repo_id";
    // Unfortunately Hibernate doesn't play nice with selecting by composite primary keys. We cannot
    // directly say "WHERE (repoId, revisionId) IN (repoIdAndRevisionIdPairs)" in any way in HQL.
    // As a result, we must use the native query format to quickly select against the (repoId,
    // revisionId) primary key index. Just make sure not to use batch sizes in the tens of thousands
    // (default is 500), otherwise the query could get too long.
    String nativeQuerySql =
        String.format(
            """
            SELECT * FROM "%s" WHERE (%s, history_revision_id) IN (
              SELECT * FROM UNNEST(:repoIds\\:\\:text[], :revisionIds\\:\\:bigint[]))
            """,
            historyEntryClass.getSimpleName(), repoIdColumnName);
    ImmutableMap<String, E> result =
        tm().transact(
                () -> {
                  @SuppressWarnings("unchecked")
                  List<H> queryResult =
                      tm().getEntityManager()
                          .createNativeQuery(nativeQuerySql, historyEntryClass)
                          .setParameter("repoIds", repoIdArray)
                          .setParameter("revisionIds", revisionIdArray)
                          .setHint(AvailableHints.HINT_READ_ONLY, true)
                          .getResultList();
                  // Flush the context so we can GC aggressively
                  tm().getEntityManager().clear();
                  return queryResult.stream()
                      .collect(
                          toImmutableMap(
                              HistoryEntry::getRepoId,
                              entry ->
                                  entry
                                      .getResourceAtPointInTime()
                                      .map(r -> r.cloneProjectedAtTime(watermark))
                                      .map(resourceClass::cast)
                                      .get()));
                });
    // Fail fast on items being missing unexpectedly
    if (result.size() != ids.size()) {
      ImmutableSet<String> expectedRepoIds = ids.stream().map(KV::getKey).collect(toImmutableSet());
      throw new NoSuchElementException(
          String.format(
              "Expected to find the following %s history entries but they were missing: %s",
              historyEntryClass.getSimpleName(),
              Sets.difference(expectedRepoIds, result.keySet())));
    }
    return result;
  }

  /**
   * Remove unreferenced hosts by joining the (repoId, pendingDeposit) pair with the (repoId,
   * revisionId) on the repoId.
   *
   * <p>The (repoId, pendingDeposit) pairs denote hosts that are referenced from a domain (built up
   * when processing domains earlier). We essentially want to filter out the hostHistories to only
   * contain these hosts.
   *
   * @return a collection of (repoId -> (pending deposits, revisionId)) where neither the
   *     pendingDeposit nor the revisionId list is empty.
   */
  private static PCollection<KV<String, CoGbkResult>> removeUnreferencedHosts(
      PCollection<KV<String, PendingDeposit>> referencedHosts,
      PCollection<KV<String, Long>> hostHistories) {
    PCollection<KV<String, PendingDeposit>> uniqueHosts =
        referencedHosts
            .setCoder(KvCoder.of(StringUtf8Coder.of(), PendingDepositCoder.of()))
            .apply("Deduplicate hosts for grouping", Distinct.create());
    return KeyedPCollectionTuple.of(PENDING_DEPOSIT, uniqueHosts)
        .and(REVISION_ID, hostHistories)
        .apply("Join PendingDeposit with HostHistory revision ID on Host", CoGroupByKey.create())
        .apply(
            "Remove unreferenced Hosts",
            Filter.by(
                (KV<String, CoGbkResult> kv) -> {
                  boolean toInclude =
                      // If a host does not have corresponding pending deposit, it is not referenced
                      // and should not be included.
                      !Iterables.isEmpty(kv.getValue().getAll(PENDING_DEPOSIT))
                          // If a host does not have revision id (this should not happen, as
                          // every referenced host must be valid at watermark time, therefore
                          // be embedded in a history entry valid at watermark time, otherwise
                          // the domain cannot reference it), there is no way for us to find the
                          // history entry and load the embedded host. So we ignore the host
                          // to keep the downstream process simple.
                          && !Iterables.isEmpty(kv.getValue().getAll(REVISION_ID));
                  if (toInclude) {
                    REFERENCED_HOST_COGBK_COUNTER.inc();
                  }
                  return toInclude;
                }));
  }

  private PCollectionTuple processDomainHistories(PCollection<KV<String, Long>> domainHistories) {
    int batchSize = options.getHistoryEntryLoadBatchSize();
    int numShards = options.getNumHistoryEntryShards();
    return domainHistories
        .apply(
            // Batching only combines elements with the same key, so we need to shard
            "Split domain histories across shards for batched retrieval",
            WithKeys.<Integer, KV<String, Long>>of(
                    kv -> Math.floorMod(kv.getKey().hashCode(), numShards))
                .withKeyType(integers()))
        .apply(
            "Group domain histories into batches",
            GroupIntoBatches.<Integer, KV<String, Long>>ofSize(batchSize).withShardedKey())
        .apply(
            "Map DomainHistory to DepositFragment and emit referenced Host",
            ParDo.of(
                    new DoFn<
                        KV<ShardedKey<Integer>, Iterable<KV<String, Long>>>,
                        KV<PendingDeposit, DepositFragment>>() {
                      @ProcessElement
                      public void processElement(
                          @Element KV<ShardedKey<Integer>, Iterable<KV<String, Long>>> element,
                          MultiOutputReceiver receiver) {
                        loadResourcesByHistoryEntryIds(
                                element.getValue(), Domain.class, DomainHistory.class, watermark)
                            .values()
                            .forEach(d -> processSingleDomain(d, receiver));
                      }
                    })
                .withOutputTags(DOMAIN_FRAGMENTS, TupleTagList.of(REFERENCED_HOSTS)));
  }

  private void processSingleDomain(Domain domain, DoFn.MultiOutputReceiver receiver) {
    ACTIVE_DOMAIN_COUNTER.inc();
    pendingDeposits.stream()
        .filter(pendingDeposit -> pendingDeposit.tld().equals(domain.getTld()))
        .forEach(
            pendingDeposit -> {
              DOMAIN_FRAGMENT_COUNTER.inc();
              receiver
                  .get(DOMAIN_FRAGMENTS)
                  .output(
                      KV.of(
                          pendingDeposit, marshaller.marshalDomain(domain, pendingDeposit.mode())));

              if (pendingDeposit.mode() == RdeMode.FULL && domain.getNsHosts() != null) {
                REFERENCED_HOST_COUNTER.inc(domain.getNsHosts().size());
                domain
                    .getNsHosts()
                    .forEach(
                        hostKey ->
                            receiver
                                .get(REFERENCED_HOSTS)
                                .output(KV.of((String) hostKey.getKey(), pendingDeposit)));
              }
            });
  }

  private PCollectionTuple processHostHistories(
      PCollection<KV<String, PendingDeposit>> referencedHosts,
      PCollection<KV<String, Long>> hostHistories) {
    int batchSize = options.getHistoryEntryLoadBatchSize();
    int numShards = options.getNumHistoryEntryShards();
    return removeUnreferencedHosts(referencedHosts, hostHistories)
        .apply(
            // Batching only combines elements with the same key, so we need to shard
            "Split host histories across shards for batched retrieval",
            WithKeys.<Integer, KV<String, CoGbkResult>>of(
                    kv -> Math.floorMod(kv.getKey().hashCode(), numShards))
                .withKeyType(integers()))
        .apply(
            "Group referenced hosts into batches",
            GroupIntoBatches.<Integer, KV<String, CoGbkResult>>ofSize(batchSize).withShardedKey())
        .apply(
            "Map external Host to DepositFragment and route subordinate hosts",
            ParDo.of(
                    new DoFn<
                        KV<ShardedKey<Integer>, Iterable<KV<String, CoGbkResult>>>,
                        KV<PendingDeposit, DepositFragment>>() {
                      @ProcessElement
                      public void processElement(
                          @Element
                              KV<ShardedKey<Integer>, Iterable<KV<String, CoGbkResult>>> element,
                          MultiOutputReceiver receiver) {
                        ImmutableList<KV<String, CoGbkResult>> batchElements =
                            ImmutableList.copyOf(element.getValue());
                        ImmutableSet<KV<String, Long>> hostKeys =
                            batchElements.stream()
                                .map(
                                    kv ->
                                        KV.of(
                                            kv.getKey(),
                                            getSingleRevisionId(
                                                HostHistory.class,
                                                kv.getKey(),
                                                kv.getValue().getAll(REVISION_ID))))
                                .collect(toImmutableSet());
                        ImmutableMap<String, Host> loadedHosts =
                            loadResourcesByHistoryEntryIds(
                                hostKeys, Host.class, HostHistory.class, watermark);
                        for (KV<String, CoGbkResult> kv : batchElements) {
                          Host host = loadedHosts.get(kv.getKey());
                          // When a host is subordinate, we need to find its superordinate domain
                          // and include it in the deposit as well.
                          if (host.isSubordinate()) {
                            SUBORDINATE_HOST_COUNTER.inc();
                            receiver
                                .get(SUPERORDINATE_DOMAINS)
                                .output(
                                    // The output are pairs of (superordinateDomainRepoId,
                                    // (subordinateHostRepoId, (pendingDeposits, revisionIds))).
                                    KV.of((String) host.getSuperordinateDomain().getKey(), kv));
                          } else {
                            // We can just directly marshal and send out external hosts
                            EXTERNAL_HOST_COUNTER.inc();
                            DepositFragment fragment = marshaller.marshalExternalHost(host);
                            Streams.stream(kv.getValue().getAll(PENDING_DEPOSIT))
                                // The same host could be used by multiple domains, therefore
                                // matched to the same pending deposit multiple times.
                                .distinct()
                                .forEach(
                                    pendingDeposit -> {
                                      EXTERNAL_HOST_FRAGMENT_COUNTER.inc();
                                      receiver
                                          .get(EXTERNAL_HOST_FRAGMENTS)
                                          .output(KV.of(pendingDeposit, fragment));
                                    });
                          }
                        }
                      }
                    })
                .withOutputTags(EXTERNAL_HOST_FRAGMENTS, TupleTagList.of(SUPERORDINATE_DOMAINS)));
  }

  /**
   * Process subordinate hosts by making a deposit fragment with pending transfer information
   * obtained from its superordinate domain.
   *
   * @param superordinateDomains Pairs of (superordinateDomainRepoId, (subordinateHostRepoId,
   *     (pendingDeposits, revisionIds))). This collection maps the subordinate host and the pending
   *     deposit to include it to its superordinate domain.
   * @param domainHistories Pairs of (domainRepoId, revisionId). This collection helps us find the
   *     historical superordinate domain from its history entry and is obtained from calling {@link
   *     #getMostRecentHistoryEntries} for domains.
   */
  private PCollection<KV<PendingDeposit, DepositFragment>> processSubordinateHosts(
      PCollection<KV<String, KV<String, CoGbkResult>>> superordinateDomains,
      PCollection<KV<String, Long>> domainHistories) {
    int batchSize = options.getHistoryEntryLoadBatchSize();
    int numShards = options.getNumHistoryEntryShards();
    return KeyedPCollectionTuple.of(HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID, superordinateDomains)
        .and(REVISION_ID, domainHistories)
        .apply("Join Host:PendingDeposits with DomainHistory on Domain", CoGroupByKey.create())
        .apply(
            "Remove Domains without subordinate hosts",
            Filter.by(
                kv -> {
                  boolean toInclude =
                      !Iterables.isEmpty(
                              kv.getValue().getAll(HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID))
                          && !Iterables.isEmpty(kv.getValue().getAll(REVISION_ID));
                  if (toInclude) {
                    REFERENCED_SUBORDINATE_HOST_COUNTER.inc();
                  }
                  return toInclude;
                }))
        .apply(
            // Batching only combines elements with the same key, so we need to shard
            "Split superordinate domains across shards for batched retrieval",
            WithKeys.<Integer, KV<String, CoGbkResult>>of(
                    kv -> Math.floorMod(kv.getKey().hashCode(), numShards))
                .withKeyType(integers()))
        .apply(
            "Group superordinate domains into batches",
            GroupIntoBatches.<Integer, KV<String, CoGbkResult>>ofSize(batchSize).withShardedKey())
        .apply(
            "Map subordinate Host to DepositFragment",
            ParDo.of(
                new DoFn<
                    KV<ShardedKey<Integer>, Iterable<KV<String, CoGbkResult>>>,
                    KV<PendingDeposit, DepositFragment>>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<ShardedKey<Integer>, Iterable<KV<String, CoGbkResult>>> element,
                      OutputReceiver<KV<PendingDeposit, DepositFragment>> receiver) {
                    ImmutableList<KV<String, CoGbkResult>> batchElements =
                        ImmutableList.copyOf(element.getValue());
                    ImmutableSet<KV<String, Long>> domainKeys =
                        batchElements.stream()
                            .map(
                                kv ->
                                    KV.of(
                                        kv.getKey(),
                                        getSingleRevisionId(
                                            DomainHistory.class,
                                            kv.getKey(),
                                            kv.getValue().getAll(REVISION_ID))))
                            .collect(toImmutableSet());
                    ImmutableSet<KV<String, Long>> hostKeys =
                        batchElements.stream()
                            .flatMap(
                                kv ->
                                    Streams.stream(
                                            kv.getValue()
                                                .getAll(HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID))
                                        .map(
                                            hostToPendingDeposits ->
                                                KV.of(
                                                    hostToPendingDeposits.getKey(),
                                                    getSingleRevisionId(
                                                        HostHistory.class,
                                                        hostToPendingDeposits.getKey(),
                                                        hostToPendingDeposits
                                                            .getValue()
                                                            .getAll(REVISION_ID)))))
                            .collect(toImmutableSet());
                    ImmutableMap<String, Domain> loadedDomains =
                        loadResourcesByHistoryEntryIds(
                            domainKeys, Domain.class, DomainHistory.class, watermark);
                    ImmutableMap<String, Host> loadedHosts =
                        loadResourcesByHistoryEntryIds(
                            hostKeys, Host.class, HostHistory.class, watermark);
                    for (KV<String, CoGbkResult> kv : batchElements) {
                      Domain superordinateDomain = loadedDomains.get(kv.getKey());
                      for (KV<String, CoGbkResult> hostToPendingDeposits :
                          kv.getValue().getAll(HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID)) {
                        Host host = loadedHosts.get(hostToPendingDeposits.getKey());
                        DepositFragment fragment =
                            marshaller.marshalSubordinateHost(host, superordinateDomain);
                        Streams.stream(hostToPendingDeposits.getValue().getAll(PENDING_DEPOSIT))
                            .distinct()
                            .forEach(
                                pendingDeposit -> {
                                  SUBORDINATE_HOST_FRAGMENT_COUNTER.inc();
                                  receiver.output(KV.of(pendingDeposit, fragment));
                                });
                      }
                    }
                  }
                }));
  }

  /**
   * Decodes the pipeline option extracted from the URL parameter sent by the pipeline launcher to
   * the original pending deposit set.
   */
  @SuppressWarnings("unchecked")
  static ImmutableSet<PendingDeposit> decodePendingDeposits(String encodedPendingDeposits) {
    return ImmutableSet.copyOf(
        safeDeserializeCollection(PendingDeposit.class, decodeBase64(encodedPendingDeposits)));
  }

  /**
   * Encodes the pending deposit set in a URL safe string that is sent to the pipeline worker by the
   * pipeline launcher as a pipeline option.
   */
  public static String encodePendingDeposits(ImmutableSet<PendingDeposit> pendingDeposits)
      throws IOException {
    return encodeBase64(serializeCollection(pendingDeposits));
  }

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    PipelineOptionsFactory.register(RdePipelineOptions.class);
    RdePipelineOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(RdePipelineOptions.class);

    RegistryPipelineOptions.validateRegistryPipelineOptions(options);
    options.setIsolationOverride(TransactionIsolationLevel.TRANSACTION_READ_COMMITTED);
    DaggerRdePipeline_RdePipelineComponent.builder().options(options).build().rdePipeline().run();
  }

  /**
   * A utility class that contains {@link TupleTag}s when {@link PCollectionTuple}s and {@link
   * CoGbkResult}s are used.
   */
  protected abstract static class TupleTags {

    protected static final TupleTag<KV<PendingDeposit, DepositFragment>> DOMAIN_FRAGMENTS =
        new TupleTag<>() {};

    protected static final TupleTag<KV<String, PendingDeposit>> REFERENCED_HOSTS =
        new TupleTag<>() {};

    protected static final TupleTag<KV<String, KV<String, CoGbkResult>>> SUPERORDINATE_DOMAINS =
        new TupleTag<>() {};

    protected static final TupleTag<KV<PendingDeposit, DepositFragment>> EXTERNAL_HOST_FRAGMENTS =
        new TupleTag<>() {};

    protected static final TupleTag<PendingDeposit> PENDING_DEPOSIT = new TupleTag<>() {};

    protected static final TupleTag<KV<String, CoGbkResult>>
        HOST_TO_PENDING_DEPOSIT_AND_REVISION_ID = new TupleTag<>() {};

    protected static final TupleTag<Long> REVISION_ID = new TupleTag<>() {};
  }

  @Singleton
  @Component(
      modules = {
        CredentialModule.class,
        ConfigModule.class,
        CloudTasksUtilsModule.class,
        UtilsModule.class
      })
  interface RdePipelineComponent {

    RdePipeline rdePipeline();

    @Component.Builder
    interface Builder {

      @BindsInstance
      Builder options(RdePipelineOptions options);

      RdePipelineComponent build();
    }
  }
}
