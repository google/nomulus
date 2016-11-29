// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.xjc.host.XjcHostAddrType;
import google.registry.xjc.host.XjcHostStatusType;
import google.registry.xjc.rdehost.XjcRdeHost;
import java.net.InetAddress;

/** Utility class that converts an {@link XjcRdeHost} into a {@link HostResource}. */
public class XjcToHostResourceConverter {

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
    return new HostResource.Builder()
        .setFullyQualifiedHostName(host.getName())
        .setRepoId(host.getRoid())
        .setCurrentSponsorClientId(host.getClID())
        .setLastTransferTime(host.getTrDate())
        .setCreationTime(host.getCrDate())
        .setLastEppUpdateTime(host.getUpDate())
        .setCreationClientId(host.getCrRr().getValue())
        .setLastEppUpdateClientId(host.getUpRr() == null ? null : host.getUpRr().getValue())
        .setStatusValues(
            ImmutableSet.copyOf(Lists.transform(host.getStatuses(), STATUS_VALUE_CONVERTER)))
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
