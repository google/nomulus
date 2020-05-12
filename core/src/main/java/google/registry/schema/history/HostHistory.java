// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.history;

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import java.net.InetAddress;
import java.util.Set;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Table;
import org.joda.time.DateTime;

/** A history of a host modification, including all the field from the host in question. */
@Entity
@Table(indexes = {@Index(columnList = "registrarId"), @Index(columnList = "creationTime")})
public class HostHistory extends EppHistory {

  VKey<HostResource> referencedEntity;

  String fullyQualifiedHostName;

  @ElementCollection Set<InetAddress> inetAddresses;

  VKey<DomainBase> superordinateDomain;

  DateTime lastTransferTime;

  DateTime lastSuperordinateChange;

  public VKey<HostResource> getReferencedEntity() {
    return referencedEntity;
  }

  public String getFullyQualifiedHostName() {
    return fullyQualifiedHostName;
  }

  public ImmutableSet<InetAddress> getInetAddresses() {
    return nullToEmptyImmutableCopy(inetAddresses);
  }

  public VKey<DomainBase> getSuperordinateDomain() {
    return superordinateDomain;
  }

  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  public DateTime getLastSuperordinateChange() {
    return lastSuperordinateChange;
  }

  public static class Builder extends EppHistory.Builder<HostHistory> {
    public Builder() {}

    public Builder(HostHistory instance) {
      super(instance);
    }

    public Builder setHostResource(HostResource hostResource) {
      getInstance().referencedEntity = VKey.createSql(HostResource.class, hostResource.getRepoId());
      getInstance().fullyQualifiedHostName = hostResource.getFullyQualifiedHostName();
      getInstance().inetAddresses = hostResource.getInetAddresses();
      if (hostResource.getSuperordinateDomain() != null) {
        getInstance().superordinateDomain =
            VKey.createOfy(DomainBase.class, hostResource.getSuperordinateDomain());
      }
      getInstance().lastTransferTime = hostResource.getLastTransferTime();
      getInstance().lastSuperordinateChange = hostResource.getLastSuperordinateChange();
      return this;
    }
  }
}
