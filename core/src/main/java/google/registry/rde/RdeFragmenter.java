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

package google.registry.rde;

import static google.registry.model.EppResourceUtils.loadAtPointInTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableMap;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.rde.RdeMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/** Loading cache that turns a resource into XML for the various points in time and modes. */
public class RdeFragmenter {
  private final Map<WatermarkModePair, Optional<DepositFragment>> cache = new HashMap<>();
  private final ImmutableMap<DateTime, Supplier<EppResource>> resourceAtTimes;
  private final RdeMarshaller marshaller;

  long cacheHits = 0;
  long resourcesNotFound = 0;
  long resourcesFound = 0;

  public RdeFragmenter(
      ImmutableMap<DateTime, Supplier<EppResource>> resourceAtTimes, RdeMarshaller marshaller) {
    this.resourceAtTimes = resourceAtTimes;
    this.marshaller = marshaller;
  }

  public Optional<DepositFragment> marshal(DateTime watermark, RdeMode mode) {
    Optional<DepositFragment> result = cache.get(WatermarkModePair.create(watermark, mode));
    if (result != null) {
      cacheHits++;
      return result;
    }
    EppResource resource = resourceAtTimes.get(watermark).get();
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
                      Objects.requireNonNull(
                          loadAtPointInTime(
                              tm().loadByKey(host.getSuperordinateDomain()), watermark)))
                  : marshaller.marshalExternalHost(host));
      cache.put(WatermarkModePair.create(watermark, RdeMode.FULL), result);
      cache.put(WatermarkModePair.create(watermark, RdeMode.THIN), result);
      return result;
    } else {
      throw new AssertionError(resource.toString());
    }
  }

  /** Map key for {@link RdeFragmenter} cache. */
  @AutoValue
  abstract static class WatermarkModePair {
    abstract DateTime watermark();

    abstract RdeMode mode();

    static WatermarkModePair create(DateTime watermark, RdeMode mode) {
      return new AutoValue_RdeFragmenter_WatermarkModePair(watermark, mode);
    }
  }
}
