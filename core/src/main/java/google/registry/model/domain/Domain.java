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

package google.registry.model.domain;

import google.registry.model.EppResource;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import java.util.Set;
import org.hibernate.Hibernate;
import org.joda.time.DateTime;

/**
 * A persistable domain resource including mutable and non-mutable fields.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5731">RFC 5731</a>
 */
@Entity
@Table(
    name = "Domain",
    indexes = {
      @Index(columnList = "adminContact"),
      @Index(columnList = "autorenewEndTime"),
      @Index(columnList = "billingContact"),
      @Index(columnList = "creationTime"),
      @Index(columnList = "currentSponsorRegistrarId"),
      @Index(columnList = "deletionTime"),
      @Index(columnList = "domainName"),
      @Index(columnList = "techContact"),
      @Index(columnList = "tld"),
      @Index(columnList = "registrantContact"),
      @Index(columnList = "lordnPhase"),
      @Index(columnList = "billing_recurrence_id"),
      @Index(columnList = "transfer_billing_event_id"),
      @Index(columnList = "transfer_billing_recurrence_id"),
      @Index(columnList = "transfer_billing_cancellation_id")
    })
@WithVKey(String.class)
@ExternalMessagingName("domain")
@Access(AccessType.FIELD)
public class Domain extends DomainBase implements ForeignKeyedEppResource {

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  // It seems like this should be FetchType.EAGER, but for some reason when we do that we get a lazy
  // load error during the load of a domain.
  @ElementCollection
  @JoinTable(
      name = "DomainHost",
      indexes = {
        @Index(columnList = "domain_repo_id,host_repo_id", unique = true),
        @Index(columnList = "host_repo_id")
      })
  @Access(AccessType.PROPERTY)
  @Column(name = "host_repo_id")
  public Set<VKey<Host>> getNsHosts() {
    return nsHosts;
  }

  /**
   * Returns the set of {@link GracePeriod} associated with the domain.
   *
   * <p>This is the getter method specific for Hibernate to access the field, so it is set to
   * private. The caller can use the public {@link #getGracePeriods()} to get the grace periods.
   *
   * <p>Note that we need to set `insertable = false, updatable = false` for @JoinColumn, otherwise
   * Hibernate would try to set the foreign key to null(through an UPDATE TABLE sql) instead of
   * deleting the whole entry from the table when the {@link GracePeriod} is removed from the set.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(
      name = "domainRepoId",
      referencedColumnName = "repoId",
      insertable = false,
      updatable = false)
  @SuppressWarnings("unused")
  private Set<GracePeriod> getInternalGracePeriods() {
    return gracePeriods;
  }

  /**
   * Returns the set of {@link DomainDsData} associated with the domain.
   *
   * <p>This is the getter method specific for Hibernate to access the field, so it is set to
   * private. The caller can use the public {@link #getDsData()} to get the DS data.
   *
   * <p>Note that we need to set `insertable = false, updatable = false` for @JoinColumn, otherwise
   * Hibernate would try to set the foreign key to null(through an UPDATE TABLE sql) instead of
   * deleting the whole entry from the table when the {@link DomainDsData} is removed from the set.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(
      name = "domainRepoId",
      referencedColumnName = "repoId",
      insertable = false,
      updatable = false)
  @SuppressWarnings("unused")
  private Set<DomainDsData> getInternalDelegationSignerData() {
    return dsData;
  }

  /** Post-load method to eager load the collections. */
  @PostLoad
  protected void postLoad() {
    // TODO(b/188044616): Determine why Eager loading doesn't work here.
    Hibernate.initialize(dsData);
    Hibernate.initialize(gracePeriods);
  }

  @Override
  public VKey<Domain> createVKey() {
    return VKey.create(Domain.class, getRepoId());
  }

  @Override
  public Domain cloneProjectedAtTime(final DateTime now) {
    return cloneDomainProjectedAtTime(this, now);
  }

  public static VKey<Domain> createVKey(String repoId) {
    return VKey.create(Domain.class, repoId);
  }

  /** An override of {@link EppResource#asBuilder} with tighter typing. */
  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Domain}, since it is immutable. */
  public static class Builder extends DomainBase.Builder<Domain, Builder> {

    public Builder() {}

    Builder(Domain instance) {
      super(instance);
    }

    public Builder copyFrom(DomainBase domainBase) {
      getInstance().copyUpdateTimestamp(domainBase);
      return setAuthInfo(domainBase.getAuthInfo())
          .setAutorenewPollMessage(domainBase.getAutorenewPollMessage())
          .setAutorenewBillingEvent(domainBase.getAutorenewBillingEvent())
          .setAutorenewEndTime(domainBase.getAutorenewEndTime())
          .setCreationRegistrarId(domainBase.getCreationRegistrarId())
          .setCreationTime(domainBase.getCreationTime())
          .setDomainName(domainBase.getDomainName())
          .setDeletePollMessage(domainBase.getDeletePollMessage())
          .setDsData(domainBase.getDsData())
          .setDeletionTime(domainBase.getDeletionTime())
          .setGracePeriods(domainBase.getGracePeriods())
          .setIdnTableName(domainBase.getIdnTableName())
          .setLastTransferTime(domainBase.getLastTransferTime())
          .setLaunchNotice(domainBase.getLaunchNotice())
          .setLastEppUpdateRegistrarId(domainBase.getLastEppUpdateRegistrarId())
          .setLastEppUpdateTime(domainBase.getLastEppUpdateTime())
          .setNameservers(domainBase.getNameservers())
          .setPersistedCurrentSponsorRegistrarId(domainBase.getPersistedCurrentSponsorRegistrarId())
          .setRegistrationExpirationTime(domainBase.getRegistrationExpirationTime())
          .setRepoId(domainBase.getRepoId())
          .setSmdId(domainBase.getSmdId())
          .setSubordinateHosts(domainBase.getSubordinateHosts())
          .setStatusValues(domainBase.getStatusValues())
          .setTransferData(domainBase.getTransferData())
          .setLordnPhase(domainBase.getLordnPhase())
          .setCurrentBulkToken(domainBase.getCurrentBulkToken().orElse(null));
    }
  }
}
