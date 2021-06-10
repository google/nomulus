// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rde;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.EppResourceUtils.loadResultAtPointInTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.appengine.tools.mapreduce.Mapper;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Result;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import google.registry.model.registrar.Registrar;
import google.registry.xml.ValidationMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;

/** Mapper for {@link RdeStagingAction}. */
public final class RdeStagingMapper extends Mapper<EppResource, PendingDeposit, DepositFragment> {

  private static final long serialVersionUID = -1518185703789372524L;

  // Registrars to be excluded from data escrow. Not including the sandbox-only OTE type so that
  // if sneaks into production we would get an extra signal.
  private static final ImmutableSet<Registrar.Type> IGNORED_REGISTRAR_TYPES =
      Sets.immutableEnumSet(Registrar.Type.MONITORING, Registrar.Type.TEST);

  private final RdeMarshaller marshaller;
  private final ImmutableSetMultimap<String, PendingDeposit> pendings;

  RdeStagingMapper(
      ValidationMode validationMode, ImmutableSetMultimap<String, PendingDeposit> pendings) {
    this.marshaller = new RdeMarshaller(validationMode);
    this.pendings = pendings;
  }

  @Override
  public final void map(final EppResource resource) {
    // The mapreduce has one special input that provides a null resource. This is used as a sentinel
    // to indicate that we should emit the Registrar objects on this map shard, as these need to be
    // added to every deposit. It is important that these be emitted as part of the mapreduce and
    // not added in a separate stage, because the reducer only runs if there is at least one value
    // emitted from the mapper. Without this, a cursor might never advance because no EppResource
    // entity exists at the watermark.
    if (resource == null) {
      long registrarsEmitted = 0;
      for (Registrar registrar : Registrar.loadAllCached()) {
        if (IGNORED_REGISTRAR_TYPES.contains(registrar.getType())) {
          continue;
        }
        DepositFragment fragment = marshaller.marshalRegistrar(registrar);
        for (PendingDeposit pending : pendings.values()) {
          emit(pending, fragment);
          registrarsEmitted++;
        }
      }
      getContext().incrementCounter("registrars emitted", registrarsEmitted);
      return;
    }

    // Skip polymorphic entities that share Datastore kind.
    if (!(resource instanceof ContactResource
        || resource instanceof DomainBase
        || resource instanceof HostResource)) {
      getContext().incrementCounter("polymorphic entities skipped");
      return;
    }

    // Skip prober data.
    if (nullToEmpty(resource.getCreationClientId()).startsWith("prober-")
        || nullToEmpty(resource.getPersistedCurrentSponsorClientId()).startsWith("prober-")
        || nullToEmpty(resource.getLastEppUpdateClientId()).startsWith("prober-")) {
      getContext().incrementCounter("prober data skipped");
      return;
    }

    // The set of all TLDs to which this resource should be emitted.
    ImmutableSet<String> tlds;
    if (resource instanceof DomainBase) {
      String tld = ((DomainBase) resource).getTld();
      if (!pendings.containsKey(tld)) {
        getContext().incrementCounter("DomainBase of an unneeded TLD skipped");
        return;
      }
      getContext().incrementCounter("DomainBase instances");
      tlds = ImmutableSet.of(tld);
    } else {
      getContext().incrementCounter("non-DomainBase instances");
      // Contacts and hosts get emitted on all TLDs, even if domains don't reference them.
      tlds = pendings.keySet();
    }

    // Get the set of all point-in-time watermarks we need, to minimize rewinding.
    ImmutableSet<DateTime> dates =
        tlds.stream()
            .map(pendings::get)
            .flatMap(ImmutableSet::stream)
            .map(PendingDeposit::watermark)
            .collect(toImmutableSet());

    // Launch asynchronous fetches of point-in-time representations of resource.
    ImmutableMap<DateTime, Result<EppResource>> resourceAtTimes =
        ImmutableMap.copyOf(Maps.asMap(dates, input -> loadResultAtPointInTime(resource, input)));

    // Convert resource to an XML fragment for each watermark/mode pair lazily and cache the result.
    Fragmenter fragmenter = new Fragmenter(resourceAtTimes);

    // Emit resource as an XML fragment for all TLDs and modes pending deposit.
    long resourcesEmitted = 0;
    for (String tld : tlds) {
      for (PendingDeposit pending : pendings.get(tld)) {
        // Hosts and contacts don't get included in BRDA deposits.
        if (pending.mode() == RdeMode.THIN
            && (resource instanceof ContactResource
                || resource instanceof HostResource)) {
          continue;
        }
        Optional<DepositFragment> fragment =
            fragmenter.marshal(pending.watermark(), pending.mode());
        if (fragment.isPresent()) {
          emit(pending, fragment.get());
          resourcesEmitted++;
        }
      }
    }
    getContext().incrementCounter("resources emitted", resourcesEmitted);
    getContext().incrementCounter("fragmenter cache hits", fragmenter.cacheHits);
    getContext().incrementCounter("fragmenter resources not found", fragmenter.resourcesNotFound);
    getContext().incrementCounter("fragmenter resources found", fragmenter.resourcesFound);

    // Avoid running out of memory.
    tm().clearSessionCache();
  }

  /** Loading cache that turns a resource into XML for the various points in time and modes. */
  private class Fragmenter {
    private final Map<WatermarkModePair, Optional<DepositFragment>> cache = new HashMap<>();
    private final ImmutableMap<DateTime, Result<EppResource>> resourceAtTimes;

    long cacheHits = 0;
    long resourcesNotFound = 0;
    long resourcesFound = 0;

    Fragmenter(ImmutableMap<DateTime, Result<EppResource>> resourceAtTimes) {
      this.resourceAtTimes = resourceAtTimes;
    }

    Optional<DepositFragment> marshal(DateTime watermark, RdeMode mode) {
      Optional<DepositFragment> result = cache.get(WatermarkModePair.create(watermark, mode));
      if (result != null) {
        cacheHits++;
        return result;
      }
      EppResource resource = resourceAtTimes.get(watermark).now();
      if (resource == null) {
        result = Optional.empty();
        cache.put(WatermarkModePair.create(watermark, RdeMode.FULL), result);
        cache.put(WatermarkModePair.create(watermark, RdeMode.THIN), result);
        resourcesNotFound++;
        return result;
      }
      resourcesFound++;
      if (resource instanceof DomainBase) {
        result = Optional.of(marshaller.marshalDomain((DomainBase) resource, mode));
        cache.put(WatermarkModePair.create(watermark, mode), result);
        return result;
      } else if (resource instanceof ContactResource) {
        result = Optional.of(marshaller.marshalContact((ContactResource) resource));
        cache.put(WatermarkModePair.create(watermark, RdeMode.FULL), result);
        cache.put(WatermarkModePair.create(watermark, RdeMode.THIN), result);
        return result;
      } else if (resource instanceof HostResource) {
        HostResource host = (HostResource) resource;
        result =
            Optional.of(
                host.isSubordinate()
                    ? marshaller.marshalSubordinateHost(
                        host,
                        // Note that loadAtPointInTime() does cloneProjectedAtTime(watermark) for
                        // us.
                        loadResultAtPointInTime(
                                tm().loadByKey(host.getSuperordinateDomain()), watermark)
                            .now())
                    : marshaller.marshalExternalHost(host));
        cache.put(WatermarkModePair.create(watermark, RdeMode.FULL), result);
        cache.put(WatermarkModePair.create(watermark, RdeMode.THIN), result);
        return result;
      } else {
        throw new AssertionError(resource.toString());
      }
    }
  }

  /** Map key for {@link Fragmenter} cache. */
  @AutoValue
  abstract static class WatermarkModePair {
    abstract DateTime watermark();
    abstract RdeMode mode();

    static WatermarkModePair create(DateTime watermark, RdeMode mode) {
      return new AutoValue_RdeStagingMapper_WatermarkModePair(watermark, mode);
    }
  }
}
