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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadAtPointInTimeAsync;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import google.registry.beam.common.RegistryJpaIO;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.Type;
import google.registry.persistence.VKey;
import google.registry.rde.DepositFragment;
import google.registry.rde.PendingDeposit;
import google.registry.rde.PendingDeposit.PendingDepositCoder;
import google.registry.rde.RdeFragmenter;
import google.registry.rde.RdeMarshaller;
import google.registry.xml.ValidationMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.persistence.Entity;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.coders.SerializableCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.FlatMapElements;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.DateTime;

/**
 * Definition of a Dataflow Flex template, which generates RDE/BRDA deposits.
 *
 * <p>To stage this template locally, run the {@code stage_beam_pipeline.sh} shell script.
 *
 * <p>Then, you can run the staged template via the API client library, gCloud or a raw REST call.
 *
 * @see <a href="https://cloud.google.com/dataflow/docs/guides/templates/using-flex-templates">Using
 *     Flex Templates</a>
 */
public class RdePipeline implements Serializable {

  private final RdeMarshaller marshaller;
  private final ImmutableSetMultimap<String, PendingDeposit> pendings;

  // Registrars to be excluded from data escrow. Not including the sandbox-only OTE type so that
  // if sneaks into production we would get an extra signal.
  private static final ImmutableSet<Type> IGNORED_REGISTRAR_TYPES =
      Sets.immutableEnumSet(Registrar.Type.MONITORING, Registrar.Type.TEST);

  private static final String EPP_RESOURCE_QUERY =
      "SELECT id FROM %entity% "
          + "WHERE COALESCE(creationClientId, '') NOT LIKE 'prober-%' "
          + "AND COALESCE(currentSponsorClientId, '') NOT LIKE 'prober-%' "
          + "AND COALESCE(lastEppUpdateClientId, '') NOT LIKE 'prober-%'";

  public static String createEppResourceQuery(Class<? extends EppResource> clazz) {
    return EPP_RESOURCE_QUERY.replace("%entity%", clazz.getAnnotation(Entity.class).name())
        + (clazz.equals(DomainBase.class) ? " AND tld in (:tlds)" : "");
  }

  RdePipeline(RdePipelineOptions options) throws IOException, ClassNotFoundException {
    this.marshaller = new RdeMarshaller(ValidationMode.valueOf(options.getValidationMode()));
    this.pendings = decodePendings(options.getPendings());
  }

  @VisibleForTesting
  PipelineResult run(Pipeline pipeline) {
    createFragments(pipeline);
    return pipeline.run();
  }

  PipelineResult run() {
    return run(Pipeline.create());
  }

  PCollection<KV<PendingDeposit, DepositFragment>> createFragments(Pipeline pipeline) {
    PCollection<KV<PendingDeposit, DepositFragment>> fragments =
        PCollectionList.of(processRegistrars(pipeline))
            .and(processNonRegistrarEntities(pipeline, DomainBase.class))
            .and(processNonRegistrarEntities(pipeline, ContactResource.class))
            .and(processNonRegistrarEntities(pipeline, HostResource.class))
            .apply(Flatten.pCollections())
            .setCoder(
                KvCoder.of(PendingDepositCoder.of(), SerializableCoder.of(DepositFragment.class)));
    return fragments;
  }

  PCollection<KV<PendingDeposit, DepositFragment>> processRegistrars(Pipeline pipeline) {
    return pipeline
        .apply(
            "Read all production Registrar entities",
            RegistryJpaIO.read(
                "SELECT clientIdentifier FROM Registrar WHERE type NOT IN (:types)",
                ImmutableMap.of("types", IGNORED_REGISTRAR_TYPES),
                String.class,
                // TODO: consider adding coders for entities and pass them directly instead of using
                // VKeys.
                id -> VKey.createSql(Registrar.class, id)))
        .apply(
            "Marshal Registrar into DepositFragment",
            FlatMapElements.into(
                    TypeDescriptors.kvs(
                        TypeDescriptor.of(PendingDeposit.class),
                        TypeDescriptor.of(DepositFragment.class)))
                .via(
                    (VKey<Registrar> key) -> {
                      Registrar registrar = jpaTm().transact(() -> jpaTm().loadByKey(key));
                      DepositFragment fragment = marshaller.marshalRegistrar(registrar);
                      return pendings.values().stream()
                          .map(pending -> KV.of(pending, fragment))
                          .collect(toImmutableSet());
                    }));
  }

  @SuppressWarnings("deprecation") // Reshuffle is still recommended by Dataflow.
  <T extends EppResource>
      PCollection<KV<PendingDeposit, DepositFragment>> processNonRegistrarEntities(
          Pipeline pipeline, Class<T> clazz) {
    return createInputs(pipeline, clazz)
        .apply("Marshal " + clazz.getSimpleName() + " into DepositFragment", mapToFragments(clazz))
        .setCoder(KvCoder.of(PendingDepositCoder.of(), SerializableCoder.of(DepositFragment.class)))
        .apply(
            "Reshuffle KV<PendingDeposit, DepositFragment> of "
                + clazz.getSimpleName()
                + " to prevent fusion",
            Reshuffle.of());
  }

  <T extends EppResource> PCollection<VKey<T>> createInputs(Pipeline pipeline, Class<T> clazz) {
    return pipeline.apply(
        "Read all production " + clazz.getSimpleName() + " entities",
        RegistryJpaIO.read(
            createEppResourceQuery(clazz),
            clazz.equals(DomainBase.class)
                ? ImmutableMap.of("tlds", pendings.keySet())
                : ImmutableMap.of(),
            String.class,
            // TODO: consider adding coders for entities and pass them directly instead of using
            // VKeys.
            x -> VKey.create(clazz, x)));
  }

  <T extends EppResource>
      FlatMapElements<VKey<T>, KV<PendingDeposit, DepositFragment>> mapToFragments(Class<T> clazz) {
    return FlatMapElements.into(
            TypeDescriptors.kvs(
                TypeDescriptor.of(PendingDeposit.class), TypeDescriptor.of(DepositFragment.class)))
        .via(
            (VKey<T> key) -> {
              T resource = jpaTm().transact(() -> jpaTm().loadByKey(key));
              // The set of all TLDs to which this resource should be emitted.
              ImmutableSet<String> tlds =
                  clazz.equals(DomainBase.class)
                      ? ImmutableSet.of(((DomainBase) resource).getTld())
                      : pendings.keySet();
              // Get the set of all point-in-time watermarks we need, to minimize rewinding.
              ImmutableSet<DateTime> dates =
                  tlds.stream()
                      .map(pendings::get)
                      .flatMap(ImmutableSet::stream)
                      .map(PendingDeposit::watermark)
                      .collect(toImmutableSet());
              // Launch asynchronous fetches of point-in-time representations of resource.
              ImmutableMap<DateTime, Supplier<EppResource>> resourceAtTimes =
                  ImmutableMap.copyOf(
                      Maps.asMap(dates, input -> loadAtPointInTimeAsync(resource, input)));
              // Convert resource to an XML fragment for each watermark/mode pair lazily and cache
              // the result.
              RdeFragmenter fragmenter = new RdeFragmenter(resourceAtTimes, marshaller);
              List<KV<PendingDeposit, DepositFragment>> results = new ArrayList<>();
              for (String tld : tlds) {
                for (PendingDeposit pending : pendings.get(tld)) {
                  // Hosts and contacts don't get included in BRDA deposits.
                  if (pending.mode() == RdeMode.THIN && !clazz.equals(DomainBase.class)) {
                    continue;
                  }
                  Optional<DepositFragment> fragment =
                      fragmenter.marshal(pending.watermark(), pending.mode());
                  fragment.ifPresent(
                      depositFragment -> results.add(KV.of(pending, depositFragment)));
                }
              }
              return results;
            });
  }

  /**
   * Decodes the pipeline option extracted from the URL parameter sent by the pipeline launcher to
   * the original TLD to pending deposit map.
   */
  @SuppressWarnings("unchecked")
  static ImmutableSetMultimap<String, PendingDeposit> decodePendings(String encodedPending)
      throws IOException, ClassNotFoundException {
    try (ObjectInputStream ois =
        new ObjectInputStream(
            new ByteArrayInputStream(
                BaseEncoding.base64Url().omitPadding().decode(encodedPending)))) {
      return (ImmutableSetMultimap<String, PendingDeposit>) ois.readObject();
    }
  }

  /**
   * Encodes the TLD to pending deposit map in an URL safe string that is sent to the pipeline
   * worker by the pipeline launcher as a pipeline option.
   */
  public static String encodePendings(ImmutableSetMultimap<String, PendingDeposit> pendings)
      throws IOException {
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(pendings);
      oos.flush();
      return BaseEncoding.base64Url().omitPadding().encode(baos.toByteArray());
    }
  }

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    PipelineOptionsFactory.register(RdePipelineOptions.class);
    RdePipelineOptions options = PipelineOptionsFactory.fromArgs(args).as(RdePipelineOptions.class);
    new RdePipeline(options).run();
  }
}
