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
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.beam.rde.RdePipeline.TupleTags.DOMAIN_FRAGMENTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.EXTERNAL_HOST_FRAGMENTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.HOST_TO_PENDING_DEPOSIT;
import static google.registry.beam.rde.RdePipeline.TupleTags.PENDING_DEPOSIT;
import static google.registry.beam.rde.RdePipeline.TupleTags.REFERENCED_CONTACTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.REFERENCED_HOSTS;
import static google.registry.beam.rde.RdePipeline.TupleTags.REVISION_ID;
import static google.registry.beam.rde.RdePipeline.TupleTags.SUPERORDINATE_DOMAINS;
import static google.registry.model.reporting.HistoryEntryDao.RESOURCE_TYPES_TO_HISTORY_TYPES;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.apache.beam.sdk.values.TypeDescriptors.kvs;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.io.BaseEncoding;
import dagger.BindsInstance;
import dagger.Component;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.beam.common.RegistryPipelineOptions;
import google.registry.config.CloudTasksUtilsModule;
import google.registry.config.CredentialModule;
import google.registry.config.RegistryConfig.ConfigModule;
import google.registry.gcs.GcsUtils;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import google.registry.rde.DepositFragment;
import google.registry.rde.PendingDeposit;
import google.registry.rde.PendingDeposit.PendingDepositCoder;
import google.registry.rde.RdeMarshaller;
import google.registry.util.CloudTasksUtils;
import google.registry.util.UtilsModule;
import google.registry.xml.ValidationMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.IdClass;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.coders.VarLongCoder;
import org.apache.beam.sdk.metrics.Counter;
import org.apache.beam.sdk.metrics.Metrics;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Filter;
import org.apache.beam.sdk.transforms.FlatMapElements;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.join.CoGbkResult;
import org.apache.beam.sdk.transforms.join.CoGroupByKey;
import org.apache.beam.sdk.transforms.join.KeyedPCollectionTuple;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.sdk.values.TupleTagList;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.DateTime;

/**
 * Definition of a Dataflow Flex template, which generates RDE/BRDA deposits.
 *
 * <p>To stage this template locally, run {@code ./gradlew :core:sBP -Penvironment=alpha
 * -Ppipeline=rde}.
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
 * <h1>Creating {@link DepositFragment}</h1>
 *
 * <h2>{@link Registrar}</h2>
 *
 * Non-test registrar entities are loaded from Cloud SQL and marshalled into deposit fragment. They
 * are <b>NOT</b> rewound to the watermark.
 *
 * <h2>{@link EppResource}</h2>
 *
 * All EPP resources are loaded from the corresponding {@link HistoryEntry}, which has the resource
 * embedded. In general we find most recent history entry before watermark and filter out the ones
 * that are soft-deleted by watermark. The history is emitted as pairs of (resource repo ID: history
 * revision ID) from the SQL query.
 *
 * <h3>{@link DomainBase}</h3>
 *
 * After the most recent (live) domain resources are loaded from the corresponding history objects,
 * we marshall them to deposit fragments and emit the (pending deposit: deposit fragment) pairs for
 * further processing. We also find all the contacts and hosts referenced by a given domain and emit
 * pairs of (contact/host repo ID: pending deposit) for all RDE pending deposits for further
 * processing.
 *
 * <h3>{@link ContactResource}</h3>
 *
 * We first join most recent contact histories, represented by (contact repo ID: contact history
 * revision ID) pairs, with referenced contacts, represented by (contact repo ID: pending deposit)
 * pairs, on the contact repo ID, to remove unreferenced contact histories. Contact resources are
 * then loaded from the remaining referenced contact histories, and marshalled into (pending
 * deposit: deposit fragment) pairs.
 *
 * <h3>{@link HostResource}</h3>
 *
 * Similar to {@link ContactResource}, we join the most recent host history with referenced hosts to
 * find most recent referenced hosts. For external hosts we do the same treatment as we did on
 * contacts and obtain the (pending deposit: deposit fragment) pairs. For subordinate hosts, we need
 * to find the superordinate domain in order to properly handle pending transfer in the deposit as
 * well. So we first find the superordinate domain repo ID from the host and join the (superordinate
 * domain repo ID: (subordinate host repo ID: (pending deposit: revision ID))) pair with the (domain
 * repo ID: revision ID) pair obtained from the domain history query in order to map the host at
 * watermark to the domain at watermark. We then proceed to create the (pending deposit: deposit
 * fragment) pair for subordinate hosts using the added domain information.
 *
 * <h1>Processing {@link DepositFragment}</h1>
 *
 * The (pending deposit: deposit fragment) pairs from different resources are combined and grouped
 * by pending deposit. For each pending deposit, all the relevant deposit fragments are written into
 * a encrypted file stored on GCS. The filename is uniquely determined by the Beam job ID so there
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
  private final DateTime watermark;
  private final String rdeBucket;
  private final byte[] stagingKeyBytes;
  private final GcsUtils gcsUtils;
  private final CloudTasksUtils cloudTasksUtils;
  private final RdeMarshaller marshaller;

  // Registrars to be excluded from data escrow. Not including the sandbox-only OTE type so that
  // if sneaks into production we would get an extra signal.
  private static final ImmutableSet<Type> IGNORED_REGISTRAR_TYPES =
      Sets.immutableEnumSet(Registrar.Type.MONITORING, Registrar.Type.TEST);

  // The field name of the EPP resource embedded in its corresponding history entry.
  private static final ImmutableMap<Class<? extends HistoryEntry>, String> EPP_RESOURCE_FIELD_NAME =
      ImmutableMap.of(
          DomainHistory.class,
          "domainContent",
          ContactHistory.class,
          "contactBase",
          HostHistory.class,
          "hostBase");

  @Inject
  RdePipeline(RdePipelineOptions options, GcsUtils gcsUtils, CloudTasksUtils cloudTasksUtils) {
    this.options = options;
    this.mode = ValidationMode.valueOf(options.getValidationMode());
    this.pendingDeposits = decodePendingDeposits(options.getPendings());
    ImmutableSet<DateTime> potentialWatermarks =
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

    PCollection<KV<String, Long>> contactHistories =
        getMostRecentHistoryEntries(pipeline, ContactHistory.class);

    PCollection<KV<String, Long>> hostHistories =
        getMostRecentHistoryEntries(pipeline, HostHistory.class);

    PCollectionTuple processedDomainHistories = processDomainHistories(domainHistories);

    PCollection<KV<PendingDeposit, DepositFragment>> domainFragments =
        processedDomainHistories.get(DOMAIN_FRAGMENTS);

    PCollection<KV<PendingDeposit, DepositFragment>> contactFragments =
        processContactHistories(
            processedDomainHistories.get(REFERENCED_CONTACTS), contactHistories);

    PCollectionTuple processedHosts =
        processHostHistories(processedDomainHistories.get(REFERENCED_HOSTS), hostHistories);

    PCollection<KV<PendingDeposit, DepositFragment>> externalHostFragments =
        processedHosts.get(EXTERNAL_HOST_FRAGMENTS);

    PCollection<KV<PendingDeposit, DepositFragment>> subordinateHostFragments =
        processSubordinateHosts(processedHosts.get(SUPERORDINATE_DOMAINS), domainHistories);

    return PCollectionList.of(registrarFragments)
        .and(domainFragments)
        .and(contactFragments)
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
    // Note that the namespace in the metric is not being used by Stackdriver, it just has to be
    // non-empty.
    // See:
    // https://stackoverflow.com/questions/48530496/google-dataflow-custom-metrics-not-showing-on-stackdriver
    Counter includedRegistrarCounter = Metrics.counter("RDE", "IncludedRegistrar");
    Counter registrarFragmentCounter = Metrics.counter("RDE", "RegistrarFragment");
    return pipeline
        .apply(
            "Read all production Registrars",
            RegistryJpaIO.read(
                "SELECT clientIdentifier FROM Registrar WHERE type NOT IN (:types)",
                ImmutableMap.of("types", IGNORED_REGISTRAR_TYPES),
                String.class,
                id -> VKey.createSql(Registrar.class, id)))
        .apply(
            "Marshall Registrar into DepositFragment",
            FlatMapElements.into(
                    kvs(
                        TypeDescriptor.of(PendingDeposit.class),
                        TypeDescriptor.of(DepositFragment.class)))
                .via(
                    (VKey<Registrar> key) -> {
                      includedRegistrarCounter.inc();
                      Registrar registrar = jpaTm().transact(() -> jpaTm().loadByKey(key));
                      DepositFragment fragment = marshaller.marshalRegistrar(registrar);
                      ImmutableSet<KV<PendingDeposit, DepositFragment>> fragments =
                          pendingDeposits.stream()
                              .map(pending -> KV.of(pending, fragment))
                              .collect(toImmutableSet());
                      registrarFragmentCounter.inc(fragments.size());
                      return fragments;
                    }));
  }

  /**
   * Load the most recent history entry before the watermark for a given history entry type.
   *
   * <p>Note that deleted and non-production resources are not included.
   *
   * @return A KV pair of (repoId, revisionId), used to reconstruct the composite key for the
   *     history entry.
   */
  private <T extends HistoryEntry> PCollection<KV<String, Long>> getMostRecentHistoryEntries(
      Pipeline pipeline, Class<T> historyClass) {
    String repoIdFieldName = HistoryEntryDao.REPO_ID_FIELD_NAMES.get(historyClass);
    String resourceFieldName = EPP_RESOURCE_FIELD_NAME.get(historyClass);
    return pipeline
        .apply(
            String.format("Load most recent %s", historyClass.getSimpleName()),
            RegistryJpaIO.read(
                ("SELECT %repoIdField%, id FROM %entity% WHERE (%repoIdField%, modificationTime)"
                     + " IN (SELECT %repoIdField%, MAX(modificationTime) FROM %entity% WHERE"
                     + " modificationTime <= :watermark GROUP BY %repoIdField%) AND"
                     + " %resourceField%.deletionTime > :watermark AND"
                     + " COALESCE(%resourceField%.creationClientId, '') NOT LIKE 'prober-%' AND"
                     + " COALESCE(%resourceField%.currentSponsorClientId, '') NOT LIKE 'prober-%'"
                     + " AND COALESCE(%resourceField%.lastEppUpdateClientId, '') NOT LIKE"
                     + " 'prober-%' "
                        + (historyClass == DomainHistory.class
                            ? "AND %resourceField%.tld IN "
                                + "(SELECT id FROM Tld WHERE tldType = 'REAL')"
                            : ""))
                    .replace("%entity%", historyClass.getSimpleName())
                    .replace("%repoIdField%", repoIdFieldName)
                    .replace("%resourceField%", resourceFieldName),
                ImmutableMap.of("watermark", watermark),
                Object[].class,
                row -> KV.of((String) row[0], (long) row[1])))
        .setCoder(KvCoder.of(StringUtf8Coder.of(), VarLongCoder.of()));
  }

  private <T extends HistoryEntry> EppResource loadResourceByHistoryEntryId(
      Class<T> historyEntryClazz, String repoId, long revisionId) {
    try {
      Class<?> idClazz = historyEntryClazz.getAnnotation(IdClass.class).value();
      Serializable idObject =
          (Serializable)
              idClazz.getConstructor(String.class, long.class).newInstance(repoId, revisionId);
      return jpaTm()
          .transact(() -> jpaTm().loadByKey(VKey.createSql(historyEntryClazz, idObject)))
          .getResourceAtPointInTime()
          .map(resource -> resource.cloneProjectedAtTime(watermark))
          .get();
    } catch (NoSuchMethodException
        | InvocationTargetException
        | InstantiationException
        | IllegalAccessException e) {
      throw new RuntimeException(
          String.format(
              "Cannot load resource from %s with repoId %s and revisionId %s",
              historyEntryClazz.getSimpleName(), repoId, revisionId),
          e);
    }
  }

  /**
   * Remove unreferenced resources by joining the (repoId, pendingDeposit) pair with the (repoId,
   * revisionId) on the repoId.
   *
   * <p>The (repoId, pendingDeposit) pairs denote resources (contact, host) that are referenced from
   * a domain, that are to be included in the corresponding pending deposit.
   *
   * <p>The (repoId, revisionId) paris come from the most recent history entry query, which can be
   * used to load the embedded resources themselves.
   *
   * @return a pair of (repoId, (pendingDeposit, revisionId)) where both
   */
  private static PCollection<KV<String, CoGbkResult>> removeUnreferencedResource(
      PCollection<KV<String, PendingDeposit>> referencedResources,
      PCollection<KV<String, Long>> historyEntries,
      Class<? extends EppResource> resourceClazz) {
    String resourceName = resourceClazz.getSimpleName();
    Class<? extends HistoryEntry> historyEntryClazz =
        RESOURCE_TYPES_TO_HISTORY_TYPES.get(resourceClazz);
    String historyEntryName = historyEntryClazz.getSimpleName();
    Counter referencedResourceCounter = Metrics.counter("RDE", "Referenced" + resourceName);
    return KeyedPCollectionTuple.of(PENDING_DEPOSIT, referencedResources)
        .and(REVISION_ID, historyEntries)
        .apply(
            String.format(
                "Join PendingDeposit with %s revision ID on %s", historyEntryName, resourceName),
            CoGroupByKey.create())
        .apply(
            String.format("Remove unreferenced %s", resourceName),
            Filter.by(
                (KV<String, CoGbkResult> kv) -> {
                  boolean toInclude =
                      // If a resource does not have corresponding pending deposit, it is not
                      // referenced and should not be included.
                      kv.getValue().getAll(PENDING_DEPOSIT).iterator().hasNext()
                          // If a resource does not have revision id (this should not happen, as
                          // every referenced resource must be valid at watermark time, therefore
                          // be embedded in a history entry valid at watermark time, otherwise
                          // the domain cannot reference it), there is no way for us to find the
                          // history entry and load the embedded resource. So we ignore the resource
                          // to keep the downstream process simple.
                          && kv.getValue().getAll(REVISION_ID).iterator().hasNext();
                  if (toInclude) {
                    referencedResourceCounter.inc();
                  }
                  return toInclude;
                }));
  }

  private PCollectionTuple processDomainHistories(PCollection<KV<String, Long>> domainHistories) {
    Counter activeDomainCounter = Metrics.counter("RDE", "ActiveDomainBase");
    Counter domainFragmentCounter = Metrics.counter("RDE", "DomainFragment");
    Counter referencedContactCounter = Metrics.counter("RDE", "ReferencedContactResource");
    Counter referencedHostCounter = Metrics.counter("RDE", "ReferencedHostResource");
    return domainHistories.apply(
        "Map DomainHistory to DepositFragment "
            + "and emit referenced ContactResource and HostResource",
        ParDo.of(
                new DoFn<KV<String, Long>, KV<PendingDeposit, DepositFragment>>() {
                  @ProcessElement
                  public void processElement(
                      @Element KV<String, Long> kv, MultiOutputReceiver receiver) {
                    activeDomainCounter.inc();
                    DomainBase domain =
                        (DomainBase)
                            loadResourceByHistoryEntryId(
                                DomainHistory.class, kv.getKey(), kv.getValue());
                    pendingDeposits.stream()
                        .filter(pendingDeposit -> pendingDeposit.tld().equals(domain.getTld()))
                        .forEach(
                            pendingDeposit -> {
                              // Domains are always deposited in both modes.
                              domainFragmentCounter.inc();
                              receiver
                                  .get(DOMAIN_FRAGMENTS)
                                  .output(
                                      KV.of(
                                          pendingDeposit,
                                          marshaller.marshalDomain(domain, pendingDeposit.mode())));
                              // Contacts and hosts are only deposited in RDE, not BRDA.
                              if (pendingDeposit.mode() == RdeMode.FULL) {
                                HashSet<Serializable> contacts = new HashSet<>();
                                contacts.add(domain.getAdminContact().getSqlKey());
                                contacts.add(domain.getTechContact().getSqlKey());
                                contacts.add(domain.getRegistrant().getSqlKey());
                                // Billing contact is not mandatory.
                                if (domain.getBillingContact() != null) {
                                  contacts.add(domain.getBillingContact().getSqlKey());
                                }
                                referencedContactCounter.inc(contacts.size());
                                contacts.forEach(
                                    contactRepoId ->
                                        receiver
                                            .get(REFERENCED_CONTACTS)
                                            .output(KV.of((String) contactRepoId, pendingDeposit)));
                                if (domain.getNsHosts() != null) {
                                  referencedHostCounter.inc(domain.getNsHosts().size());
                                  domain
                                      .getNsHosts()
                                      .forEach(
                                          hostKey ->
                                              receiver
                                                  .get(REFERENCED_HOSTS)
                                                  .output(
                                                      KV.of(
                                                          (String) hostKey.getSqlKey(),
                                                          pendingDeposit)));
                                }
                              }
                            });
                  }
                })
            .withOutputTags(
                DOMAIN_FRAGMENTS, TupleTagList.of(REFERENCED_CONTACTS).and(REFERENCED_HOSTS)));
  }

  private PCollection<KV<PendingDeposit, DepositFragment>> processContactHistories(
      PCollection<KV<String, PendingDeposit>> referencedContacts,
      PCollection<KV<String, Long>> contactHistories) {
    Counter contactFragmentCounter = Metrics.counter("RDE", "ContactFragment");
    return removeUnreferencedResource(referencedContacts, contactHistories, ContactResource.class)
        .apply(
            "Map ContactResource to DepositFragment",
            FlatMapElements.into(
                    kvs(
                        TypeDescriptor.of(PendingDeposit.class),
                        TypeDescriptor.of(DepositFragment.class)))
                .via(
                    (KV<String, CoGbkResult> kv) -> {
                      ContactResource contact =
                          (ContactResource)
                              loadResourceByHistoryEntryId(
                                  ContactHistory.class,
                                  kv.getKey(),
                                  kv.getValue().getOnly(REVISION_ID));
                      DepositFragment fragment = marshaller.marshalContact(contact);
                      ImmutableSet<KV<PendingDeposit, DepositFragment>> fragments =
                          Streams.stream(kv.getValue().getAll(PENDING_DEPOSIT))
                              // The same contact could be used by multiple domains, therefore
                              // matched to the same pending deposit multiple times.
                              .distinct()
                              .map(pendingDeposit -> KV.of(pendingDeposit, fragment))
                              .collect(toImmutableSet());
                      contactFragmentCounter.inc(fragments.size());
                      return fragments;
                    }));
  }

  private PCollectionTuple processHostHistories(
      PCollection<KV<String, PendingDeposit>> referencedHosts,
      PCollection<KV<String, Long>> hostHistories) {
    Counter subordinateHostCounter = Metrics.counter("RDE", "SubordinateHostResource");
    Counter externalHostCounter = Metrics.counter("RDE", "ExternalHostResource");
    Counter externalHostFragmentCounter = Metrics.counter("RDE", "ExternalHostFragment");
    return removeUnreferencedResource(referencedHosts, hostHistories, HostResource.class)
        .apply(
            "Map external DomainResource to DepositFragment and process subordinate domains",
            ParDo.of(
                    new DoFn<KV<String, CoGbkResult>, KV<PendingDeposit, DepositFragment>>() {
                      @ProcessElement
                      public void processElement(
                          @Element KV<String, CoGbkResult> kv, MultiOutputReceiver receiver) {
                        HostResource host =
                            (HostResource)
                                loadResourceByHistoryEntryId(
                                    HostHistory.class,
                                    kv.getKey(),
                                    kv.getValue().getOnly(REVISION_ID));
                        // When a host is subordinate, we need to find it's superordinate domain and
                        // include it in the deposit as well.
                        if (host.isSubordinate()) {
                          subordinateHostCounter.inc();
                          receiver
                              .get(SUPERORDINATE_DOMAINS)
                              .output(
                                  // The output are pairs of
                                  // (superordinateDomainRepoId,
                                  //   (subordinateHostRepoId, (pendingDeposit, revisionId))).
                                  KV.of((String) host.getSuperordinateDomain().getSqlKey(), kv));
                        } else {
                          externalHostCounter.inc();
                          DepositFragment fragment = marshaller.marshalExternalHost(host);
                          Streams.stream(kv.getValue().getAll(PENDING_DEPOSIT))
                              // The same host could be used by multiple domains, therefore
                              // matched to the same pending deposit multiple times.
                              .distinct()
                              .forEach(
                                  pendingDeposit -> {
                                    externalHostFragmentCounter.inc();
                                    receiver
                                        .get(EXTERNAL_HOST_FRAGMENTS)
                                        .output(KV.of(pendingDeposit, fragment));
                                  });
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
   *     (pendingDeposit, revisionId))). This collection maps the subordinate host and the pending
   *     deposit to include it to its superordinate domain.
   * @param domainHistories Pairs of (domainRepoId, revisionId). This collection helps us find the
   *     historical superordinate domain from its history entry and is obtained from calling {@link
   *     #getMostRecentHistoryEntries} for domains.
   */
  private PCollection<KV<PendingDeposit, DepositFragment>> processSubordinateHosts(
      PCollection<KV<String, KV<String, CoGbkResult>>> superordinateDomains,
      PCollection<KV<String, Long>> domainHistories) {
    Counter subordinateHostFragmentCounter = Metrics.counter("RDE", "SubordinateHostFragment");
    Counter referencedSubordinateHostCounter = Metrics.counter("RDE", "ReferencedSubordinateHost");
    return KeyedPCollectionTuple.of(HOST_TO_PENDING_DEPOSIT, superordinateDomains)
        .and(REVISION_ID, domainHistories)
        .apply(
            "Join HostResource:PendingDeposits with DomainHistory on DomainResource",
            CoGroupByKey.create())
        .apply(
            " Remove unreferenced DomainResource",
            Filter.by(
                kv -> {
                  boolean toInclude =
                      kv.getValue().getAll(HOST_TO_PENDING_DEPOSIT).iterator().hasNext()
                          && kv.getValue().getAll(REVISION_ID).iterator().hasNext();
                  if (toInclude) {
                    referencedSubordinateHostCounter.inc();
                  }
                  return toInclude;
                }))
        .apply(
            "Map subordinate HostResource to DepositFragment",
            FlatMapElements.into(
                    kvs(
                        TypeDescriptor.of(PendingDeposit.class),
                        TypeDescriptor.of(DepositFragment.class)))
                .via(
                    (KV<String, CoGbkResult> kv) -> {
                      DomainBase superordinateDomain =
                          (DomainBase)
                              loadResourceByHistoryEntryId(
                                  DomainHistory.class,
                                  kv.getKey(),
                                  kv.getValue().getOnly(REVISION_ID));
                      ImmutableSet.Builder<KV<PendingDeposit, DepositFragment>> results =
                          new ImmutableSet.Builder<>();
                      for (KV<String, CoGbkResult> hostToPendingDeposits :
                          kv.getValue().getAll(HOST_TO_PENDING_DEPOSIT)) {
                        HostResource host =
                            (HostResource)
                                loadResourceByHistoryEntryId(
                                    HostHistory.class,
                                    hostToPendingDeposits.getKey(),
                                    hostToPendingDeposits.getValue().getOnly(REVISION_ID));
                        DepositFragment fragment =
                            marshaller.marshalSubordinateHost(host, superordinateDomain);
                        Streams.stream(hostToPendingDeposits.getValue().getAll(PENDING_DEPOSIT))
                            .distinct()
                            .forEach(
                                pendingDeposit -> {
                                  subordinateHostFragmentCounter.inc();
                                  results.add(KV.of(pendingDeposit, fragment));
                                });
                      }
                      return results.build();
                    }));
  }

  /**
   * Decodes the pipeline option extracted from the URL parameter sent by the pipeline launcher to
   * the original pending deposit set.
   */
  @SuppressWarnings("unchecked")
  static ImmutableSet<PendingDeposit> decodePendingDeposits(String encodedPendingDeposits) {
    try (ObjectInputStream ois =
        new ObjectInputStream(
            new ByteArrayInputStream(
                BaseEncoding.base64Url().omitPadding().decode(encodedPendingDeposits)))) {
      return (ImmutableSet<PendingDeposit>) ois.readObject();
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalArgumentException("Unable to parse encoded pending deposit map.", e);
    }
  }

  /**
   * Encodes the pending deposit set in an URL safe string that is sent to the pipeline worker by
   * the pipeline launcher as a pipeline option.
   */
  public static String encodePendingDeposits(ImmutableSet<PendingDeposit> pendingDeposits)
      throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(pendingDeposits);
      oos.flush();
      return BaseEncoding.base64Url().omitPadding().encode(baos.toByteArray());
    }
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
        new TupleTag<KV<PendingDeposit, DepositFragment>>() {};

    protected static final TupleTag<KV<String, PendingDeposit>> REFERENCED_CONTACTS =
        new TupleTag<KV<String, PendingDeposit>>() {};

    protected static final TupleTag<KV<String, PendingDeposit>> REFERENCED_HOSTS =
        new TupleTag<KV<String, PendingDeposit>>() {};

    protected static final TupleTag<KV<String, KV<String, CoGbkResult>>> SUPERORDINATE_DOMAINS =
        new TupleTag<KV<String, KV<String, CoGbkResult>>>() {};

    protected static final TupleTag<KV<PendingDeposit, DepositFragment>> EXTERNAL_HOST_FRAGMENTS =
        new TupleTag<KV<PendingDeposit, DepositFragment>>() {};

    protected static final TupleTag<PendingDeposit> PENDING_DEPOSIT =
        new TupleTag<PendingDeposit>() {};

    protected static final TupleTag<KV<String, CoGbkResult>> HOST_TO_PENDING_DEPOSIT =
        new TupleTag<KV<String, CoGbkResult>>() {};

    protected static final TupleTag<Long> REVISION_ID = new TupleTag<Long>() {};
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
