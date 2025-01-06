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

package google.registry.model.host;

import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A persistable Host resource including mutable and non-mutable fields.
 *
 * <p>The {@link Id} of the Host is the repoId.
 */
@Entity(name = "Host")
@Table(
    name = "Host",
    /*
     * A gin index defined on the inet_addresses field ({@link HostBase#inetAddresses} cannot be
     * declared here because JPA/Hibernate does not support index type specification. As a result,
     * the hibernate-generated schema (which is for reference only) does not have this index.
     *
     * <p>There are Hibernate-specific solutions for adding this index to Hibernate's domain model.
     * We could either declare the index in hibernate.cfg.xml or add it to the {@link
     * org.hibernate.cfg.Configuration} instance for {@link SessionFactory} instantiation (which
     * would prevent us from using JPA standard bootstrapping). For now, there is no obvious benefit
     * doing either.
     */
    indexes = {
      @Index(columnList = "hostName"),
      @Index(columnList = "creationTime"),
      @Index(columnList = "deletionTime"),
      @Index(columnList = "currentSponsorRegistrarId"),
      @Index(columnList = "superordinateDomain")
    })
@ExternalMessagingName("host")
@WithVKey(String.class)
@Access(AccessType.FIELD) // otherwise it'll use the default if the repoId (property)
public class Host extends HostBase implements ForeignKeyedEppResource {

  @Override
  @Id
  @Access(AccessType.PROPERTY) // to tell it to use the non-default property-as-ID
  public String getRepoId() {
    return super.getRepoId();
  }

  @Override
  public VKey<Host> createVKey() {
    return VKey.create(Host.class, getRepoId());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Host}, since it is immutable. */
  public static class Builder extends HostBase.Builder<Host, Builder> {
    public Builder() {}

    private Builder(Host instance) {
      super(instance);
    }

    public Builder copyFrom(HostBase hostBase) {
      return setCreationRegistrarId(hostBase.getCreationRegistrarId())
          .setCreationTime(hostBase.getCreationTime())
          .setDeletionTime(hostBase.getDeletionTime())
          .setHostName(hostBase.getHostName())
          .setInetAddresses(hostBase.getInetAddresses())
          .setLastTransferTime(hostBase.getLastTransferTime())
          .setLastSuperordinateChange(hostBase.getLastSuperordinateChange())
          .setLastEppUpdateRegistrarId(hostBase.getLastEppUpdateRegistrarId())
          .setLastEppUpdateTime(hostBase.getLastEppUpdateTime())
          .setPersistedCurrentSponsorRegistrarId(hostBase.getPersistedCurrentSponsorRegistrarId())
          .setRepoId(hostBase.getRepoId())
          .setSuperordinateDomain(hostBase.getSuperordinateDomain())
          .setStatusValues(hostBase.getStatusValues());
    }
  }
}
