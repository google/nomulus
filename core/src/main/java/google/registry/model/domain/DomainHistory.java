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

package google.registry.model.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import google.registry.model.EppResource;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.Host;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.hibernate.Hibernate;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainBase} so that the
 * foreign-keyed fields in that class can refer to this object.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "historyRegistrarId"),
      @Index(columnList = "historyType"),
      @Index(columnList = "historyModificationTime")
    })
@Access(AccessType.FIELD)
@AttributeOverride(name = "repoId", column = @Column(name = "domainRepoId"))
public class DomainHistory extends HistoryEntry {

  // Store DomainBase instead of Domain, so we don't pick up its @Id
  // @Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable DomainBase resource;

  @Override
  protected DomainBase getResource() {
    return resource;
  }

  /**
   * The values of all the fields on the {@link DomainBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<DomainBase> getDomainBase() {
    return Optional.ofNullable(resource);
  }

  // We could have reused domainBase.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  @ElementCollection
  @JoinTable(
      name = "DomainHistoryHost",
      indexes = {
        @Index(columnList = "domain_history_domain_repo_id"),
        @Index(
            columnList =
                "domain_history_history_revision_id,domain_history_domain_repo_id,host_repo_id",
            unique = true)
      })
  @Column(name = "host_repo_id")
  Set<VKey<Host>> nsHosts;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  // HashSet rather than ImmutableSet so that Hibernate can fill them out lazily on request
  Set<DomainDsDataHistory> dsDataHistories = new HashSet<>();

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumns({
    @JoinColumn(
        name = "domainHistoryRevisionId",
        referencedColumnName = "historyRevisionId",
        insertable = false,
        updatable = false),
    @JoinColumn(
        name = "domainRepoId",
        referencedColumnName = "domainRepoId",
        insertable = false,
        updatable = false)
  })
  // HashSet rather than ImmutableSet so that Hibernate can fill them out lazily on request
  Set<GracePeriodHistory> gracePeriodHistories = new HashSet<>();

  /** The length of time that a create, allocate, renewal, or transfer request was issued for. */
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  Period period;

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Nullable
  @Column(name = "historyOtherRegistrarId")
  String otherRegistrarId;

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any DomainHistory/HistoryEntry generated before this field was added
   * (mid-2017), as well as any action that does not generate billable events (e.g. contact/host
   * updates).
   */
  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
  @JoinColumn(name = "historyRevisionId", referencedColumnName = "historyRevisionId")
  @JoinColumn(name = "domainRepoId", referencedColumnName = "domainRepoId")
  Set<DomainTransactionRecord> domainTransactionRecords;

  public Set<DomainTransactionRecord> getDomainTransactionRecords() {
    return nullToEmptyImmutableCopy(domainTransactionRecords);
  }

  /** Returns keys to the {@link Host} that are the nameservers for the domain. */
  public Set<VKey<Host>> getNsHosts() {
    return ImmutableSet.copyOf(nsHosts);
  }

  /** Returns the collection of {@link DomainDsDataHistory} instances. */
  public ImmutableSet<DomainDsDataHistory> getDsDataHistories() {
    return nullToEmptyImmutableCopy(dsDataHistories);
  }

  public Set<GracePeriodHistory> getGracePeriodHistories() {
    return nullToEmptyImmutableCopy(gracePeriodHistories);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<DomainHistory> createVKey() {
    return VKey.create(DomainHistory.class, getHistoryEntryId());
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getDomainBase().map(domainBase -> new Domain.Builder().copyFrom(domainBase).build());
  }

  public String getOtherRegistrarId() {
    return otherRegistrarId;
  }

  public Period getPeriod() {
    return period;
  }

  @Override
  @PostLoad
  protected void postLoad() {
    // TODO(b/188044616): Determine why Eager loading doesn't work here.
    Hibernate.initialize(domainTransactionRecords);
    Hibernate.initialize(nsHosts);
    Hibernate.initialize(dsDataHistories);
    Hibernate.initialize(gracePeriodHistories);

    if (resource != null) {
      resource.nsHosts = nullToEmptyImmutableCopy(nsHosts);
      resource.gracePeriods =
          gracePeriodHistories.stream()
              .map(GracePeriod::createFromHistory)
              .collect(toImmutableSet());
      resource.dsData =
          dsDataHistories.stream().map(DomainDsData::create).collect(toImmutableSet());
    }
    processResourcePostLoad();
  }

  private static void fillAuxiliaryFieldsFromDomain(DomainHistory domainHistory) {
    DomainBase domainBase = domainHistory.resource;
    if (domainBase != null) {
      domainHistory.nsHosts = nullToEmptyImmutableCopy(domainBase.nsHosts);
      domainHistory.dsDataHistories =
          nullToEmptyImmutableCopy(domainBase.getDsData()).stream()
              .filter(dsData -> dsData.getDigest() != null && dsData.getDigest().length > 0)
              .map(dsData -> DomainDsDataHistory.createFrom(domainHistory.getRevisionId(), dsData))
              .collect(toImmutableSet());
      domainHistory.gracePeriodHistories =
          nullToEmptyImmutableCopy(domainBase.getGracePeriods()).stream()
              .map(
                  gracePeriod ->
                      GracePeriodHistory.createFrom(domainHistory.getRevisionId(), gracePeriod))
              .collect(toImmutableSet());
    } else {
      domainHistory.nsHosts = ImmutableSet.of();
    }
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends HistoryEntry.Builder<DomainHistory, DomainHistory.Builder> {

    public Builder() {}

    public Builder(DomainHistory instance) {
      super(instance);
    }

    public Builder setDomain(DomainBase domainBase) {
      getInstance().resource = domainBase;
      return setRepoId(domainBase);
    }

    public Builder setPeriod(Period period) {
      getInstance().period = period;
      return this;
    }

    public Builder setOtherRegistrarId(String otherRegistrarId) {
      getInstance().otherRegistrarId = otherRegistrarId;
      return this;
    }

    public Builder setDomainTransactionRecords(
        ImmutableSet<DomainTransactionRecord> domainTransactionRecords) {
      getInstance().domainTransactionRecords = domainTransactionRecords;
      return this;
    }

    @Override
    public DomainHistory build() {
      DomainHistory instance = super.build();
      fillAuxiliaryFieldsFromDomain(instance);
      return instance;
    }
  }
}
