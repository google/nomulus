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

package google.registry.rde.imports;

import static com.google.common.base.Predicates.equalTo;
import static com.google.common.base.Predicates.not;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.rde.imports.RdeImportUtils.generateTridForImport;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;

import com.googlecode.objectify.Key;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.xjc.host.XjcHostAddrType;
import google.registry.xjc.host.XjcHostStatusType;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import org.joda.time.DateTime;

import java.net.InetAddress;

/** Utility class that converts an {@link XjcRdeHost} into a {@link HostResource}. */
public class XjcToHostResourceConverter extends XjcToEppResourceConverter {

  private static final Function<XjcHostStatusType, StatusValue> STATUS_VALUE_CONVERTER =
      new Function<XjcHostStatusType, StatusValue>() {
        @Override
        public StatusValue apply(XjcHostStatusType status) {
          return convertStatusType(status);
        }
      };

  private static final Function<XjcHostAddrType, InetAddress> ADDR_CONVERTER =
      new Function<XjcHostAddrType, InetAddress>() {
        @Override
        public InetAddress apply(XjcHostAddrType addr) {
          return convertAddrType(addr);
        }
      };

  static HostResource convert(XjcRdeHost host) {
    // First create and save history entry
    ofy().save().entity(
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.RDE_IMPORT)
            .setClientId(host.getClID())
            .setTrid(generateTridForImport())
            .setModificationTime(DateTime.now())
            .setXmlBytes(getObjectXml(new XjcRdeHostElement(host)))
            .setBySuperuser(true)
            .setReason("RDE Import")
            .setRequestedByRegistrar(false)
            .setParent(Key.create(null, HostResource.class, host.getRoid()))
            .build());
    return new HostResource.Builder()
        .setFullyQualifiedHostName(canonicalizeDomainName(host.getName()))
        .setRepoId(host.getRoid())
        .setCurrentSponsorClientId(host.getClID())
        .setLastTransferTime(host.getTrDate())
        .setCreationTime(host.getCrDate())
        .setLastEppUpdateTime(host.getUpDate())
        .setCreationClientId(host.getCrRr().getValue())
        .setLastEppUpdateClientId(host.getUpRr() == null ? null : host.getUpRr().getValue())
        .setStatusValues(
            FluentIterable.from(host.getStatuses())
                .transform(STATUS_VALUE_CONVERTER)
                // LINKED is implicit and should not be imported onto the new host.
                .filter(not(equalTo(StatusValue.LINKED)))
                .toSet())
        .setInetAddresses(ImmutableSet.copyOf(Lists.transform(host.getAddrs(), ADDR_CONVERTER)))
        .build();
  }

  /** Converts {@link XjcHostStatusType} to {@link StatusValue}. */
  private static StatusValue convertStatusType(XjcHostStatusType status) {
    return StatusValue.fromXmlName(status.getS().value());
  }

  /** Converts {@link XjcHostAddrType} to {@link InetAddress}. */
  private static InetAddress convertAddrType(XjcHostAddrType addr) {
    return InetAddresses.forString(addr.getValue());
  }

  private XjcToHostResourceConverter() {}
}
