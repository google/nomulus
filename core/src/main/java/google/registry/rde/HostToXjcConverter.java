// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.xjc.host.XjcHostAddrType;
import google.registry.xjc.host.XjcHostIpType;
import google.registry.xjc.host.XjcHostStatusType;
import google.registry.xjc.host.XjcHostStatusValueType;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.joda.time.DateTime;

/** Utility class that turns a {@link Host} resource into {@link XjcRdeHostElement}. */
final class HostToXjcConverter {

  /** Converts a subordinate {@link Host} to {@link XjcRdeHostElement}. */
  static XjcRdeHostElement convertSubordinate(Host host, Domain superordinateDomain) {
    checkArgument(superordinateDomain.createVKey().equals(host.getSuperordinateDomain()));
    return new XjcRdeHostElement(convertSubordinateHost(host, superordinateDomain));
  }

  /** Converts an external {@link Host} to {@link XjcRdeHostElement}. */
  static XjcRdeHostElement convertExternal(Host host) {
    checkArgument(!host.isSubordinate());
    return new XjcRdeHostElement(convertExternalHost(host));
  }

  /** Converts {@link Host} to {@link XjcRdeHost}. */
  static XjcRdeHost convertSubordinateHost(Host model, Domain superordinateDomain) {
    XjcRdeHost bean =
        convertHostCommon(
            model,
            superordinateDomain.getCurrentSponsorRegistrarId(),
            model.computeLastTransferTime(superordinateDomain));
    if (superordinateDomain.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
      bean.getStatuses().add(convertStatusValue(StatusValue.PENDING_TRANSFER));
    }
    return bean;
  }

  /** Converts {@link Host} to {@link XjcRdeHost}. */
  static XjcRdeHost convertExternalHost(Host model) {
    return convertHostCommon(
        model, model.getPersistedCurrentSponsorRegistrarId(), model.getLastTransferTime());
  }

  private static XjcRdeHost convertHostCommon(
      Host model, String registrarId, DateTime lastTransferTime) {
    XjcRdeHost bean = new XjcRdeHost();
    bean.setName(model.getHostName());
    bean.setRoid(model.getRepoId());
    bean.setCrDate(model.getCreationTime());
    bean.setUpDate(model.getLastEppUpdateTime());
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationRegistrarId(), null));
    bean.setUpRr(RdeAdapter.convertRr(model.getLastEppUpdateRegistrarId(), null));
    bean.setCrRr(RdeAdapter.convertRr(model.getCreationRegistrarId(), null));
    bean.setClID(registrarId);
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

  private HostToXjcConverter() {}
}
