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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.net.InetAddresses;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.xjc.host.XjcHostAddrType;
import google.registry.xjc.host.XjcHostIpType;
import google.registry.xjc.host.XjcHostStatusType;
import google.registry.xjc.host.XjcHostStatusValueType;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.joda.time.DateTime;

/** Utility class that turns {@link HostResource} as {@link XjcRdeHostElement}. */
final class HostResourceToXjcConverter {

  /** Converts a subordinate {@link HostResource} to {@link XjcRdeHostElement}. */
  static XjcRdeHostElement convertSubordinate(HostResource host, DomainBase superordinateDomain) {
    checkArgument(superordinateDomain.createVKey().equals(host.getSuperordinateDomain()));
    return new XjcRdeHostElement(convertSubordinateHost(host, superordinateDomain));
  }

  /** Converts an external {@link HostResource} to {@link XjcRdeHostElement}. */
  static XjcRdeHostElement convertExternal(HostResource host) {
    checkArgument(!host.isSubordinate());
    return new XjcRdeHostElement(convertExternalHost(host));
  }

  /** Converts {@link HostResource} to {@link XjcRdeHost}. */
  static XjcRdeHost convertSubordinateHost(HostResource model, DomainBase superordinateDomain) {
    XjcRdeHost bean = convertHostCommon(
        model,
        superordinateDomain.getCurrentSponsorClientId(),
        model.computeLastTransferTime(superordinateDomain));
    if (superordinateDomain.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      bean.getStatuses().add(convertStatusValue(StatusValue.PENDING_TRANSFER));
    }
    return bean;
  }

  /** Converts {@link HostResource} to {@link XjcRdeHost}. */
  static XjcRdeHost convertExternalHost(HostResource model) {
    return convertHostCommon(
        model,
        model.getPersistedCurrentSponsorClientId(),
        model.getLastTransferTime());
  }

  private static XjcRdeHost convertHostCommon(
      HostResource model, String clientId, DateTime lastTransferTime) {
    XjcRdeHost bean = new XjcRdeHost();
    bean.setName(model.getHostName());
    bean.setRoid(model.getRepoId());
    bean.setCrDate(model.getCreationTime());
    bean.setUpDate(model.getLastEppUpdateTime());
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationClientId(), null));
    bean.setUpRr(RdeAdapter.convertRr(model.getLastEppUpdateClientId(), null));
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationClientId(), null));
    bean.setClID(clientId);
    bean.setTrDate(lastTransferTime);
    for (StatusValue status : model.getStatusValues()) {
      // TODO(b/34844887): Remove when PENDING_TRANSFER is not persisted on host resources.
      if (status.equals(StatusValue.PENDING_TRANSFER)) {
        continue;
      }
      // TODO(cgoldfeder): Add in LINKED status if applicable.
      bean.getStatuses().add(convertStatusValue(status));
    }
    for (InetAddress addr : model.getInetAddresses()) {
      bean.getAddrs().add(convertInetAddress(addr));
    }
    return bean;
  }

  /** Converts {@link StatusValue} to {@link XjcHostStatusType}. */
  private static XjcHostStatusType convertStatusValue(StatusValue model) {
    XjcHostStatusType bean = new XjcHostStatusType();
    bean.setS(XjcHostStatusValueType.fromValue(model.getXmlName()));
    return bean;
  }

  /** Converts {@link InetAddress} to {@link XjcHostAddrType}. */
  private static XjcHostAddrType convertInetAddress(InetAddress model) {
    XjcHostAddrType bean = new XjcHostAddrType();
    bean.setIp(model instanceof Inet6Address ? XjcHostIpType.V_6 : XjcHostIpType.V_4);
    bean.setValue(InetAddresses.toAddrString(model));
    return bean;
  }

  private HostResourceToXjcConverter() {}
}
