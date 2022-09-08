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

package google.registry.model.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import com.googlecode.objectify.annotation.Ignore;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.Host;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinColumns;
import javax.persistence.JoinTable;
import javax.persistence.OneToMany;
import javax.persistence.PostLoad;
import javax.persistence.Table;
import org.hibernate.Hibernate;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainBase} so that the
 * foreign-keyed fields in that class can refer to this object.
 *
 * <p>This class is only marked as a Datastore entity subclass and registered with Objectify so that
 * when building it its ID can be auto-populated by Objectify. It is converted to its superclass
 * {@link HistoryEntry} when persisted to Datastore using {@link
 * google.registry.persistence.transaction.TransactionManager}.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "historyRegistrarId"),
      @Index(columnList = "historyType"),
      @Index(columnList = "historyModificationTime")
    })
@EntitySubclass
@Access(AccessType.FIELD)
@IdClass(DomainHistoryId.class)
public class DomainHistory extends HistoryEntry {

  // Store DomainBase instead of Domain so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @DoNotCompare @Nullable DomainBase domainBase;

  @Id
  @Access(AccessType.PROPERTY)
  public String getDomainRepoId() {
    // We need to handle null case here because Hibernate sometimes accesses this method before
    // parent gets initialized
    return parent == null ? null : parent.getName();
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setDomainRepoId(String domainRepoId) {
    parent = Key.create(Domain.class, domainRepoId);
  }

  // We could have reused domainBase.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  // TODO(b/166776754): Investigate if we can reuse domainBase.nsHosts for storing host keys.
  @DoNotCompare
  @ElementCollection
  @JoinTable(
      name = "DomainHistoryHost",
      indexes = {
        @Index(
            columnList =
                "domain_history_history_revision_id,domain_history_domain_repo_id,host_repo_id",
            unique = true),
      })
  @ImmutableObject.EmptySetToNull
  @Column(name = "host_repo_id")
  Set<VKey<Host>> nsHosts;

  @DoNotCompare
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
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
  @Ignore
  Set<DomainDsDataHistory> dsDataHistories = new HashSet<>();

  @DoNotCompare
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
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
  @Ignore
  Set<GracePeriodHistory> gracePeriodHistories = new HashSet<>();

  @Override
  @Nullable
  @Access(AccessType.PROPERTY)
  @AttributeOverrides({
    @AttributeOverride(name = "unit", column = @Column(name = "historyPeriodUnit")),
    @AttributeOverride(name = "value", column = @Column(name = "historyPeriodValue"))
  })
  public Period getPeriod() {
    return super.getPeriod();
  }

  /**
   * For transfers, the id of the other registrar.
   *
   * <p>For requests and cancels, the other registrar is the losing party (because the registrar
   * sending the EPP transfer command is the gaining party). For approves and rejects, the other
   * registrar is the gaining party.
   */
  @Nullable
  @Access(AccessType.PROPERTY)
  @Column(name = "historyOtherRegistrarId")
  @Override
  public String getOtherRegistrarId() {
    return super.getOtherRegistrarId();
  }

  /**
   * Logging field for transaction reporting.
   *
   * <p>This will be empty for any DomainHistory/HistoryEntry generated before this field was added,
   * mid-2017, as well as any action that does not generate billable events (e.g. updates).
   *
   * <p>This method is dedicated for Hibernate, external caller should use {@link
   * #getDomainTransactionRecords()}.
   */
  @Access(AccessType.PROPERTY)
  @OneToMany(
      cascade = {CascadeType.ALL},
      fetch = FetchType.EAGER,
      orphanRemoval = true)
  @JoinColumn(name = "historyRevisionId", referencedColumnName = "historyRevisionId")
  @JoinColumn(name = "domainRepoId", referencedColumnName = "domainRepoId")
  @SuppressWarnings("unused")
  private Set<DomainTransactionRecord> getInternalDomainTransactionRecords() {
    return domainTransactionRecords;
  }

  /** Sets the domain transaction records. This method is dedicated for Hibernate. */
  @SuppressWarnings("unused")
  private void setInternalDomainTransactionRecords(
      Set<DomainTransactionRecord> domainTransactionRecords) {
    super.setDomainTransactionRecords(domainTransactionRecords);
  }

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  public DomainHistoryId getDomainHistoryId() {
    return new DomainHistoryId(getDomainRepoId(), getId());
  }

  /** Returns keys to the {@link Host} that are the nameservers for the domain. */
  public Set<VKey<Host>> getNsHosts() {
    return nsHosts;
  }

  /** Returns the collection of {@link DomainDsDataHistory} instances. */
  public ImmutableSet<DomainDsDataHistory> getDsDataHistories() {
    return nullToEmptyImmutableCopy(dsDataHistories);
  }

  /**
   * The values of all the fields on the {@link DomainBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<DomainBase> getDomainBase() {
    return Optional.ofNullable(domainBase);
  }

  /** The key to the {@link Domain} this is based off of. */
  public VKey<Domain> getParentVKey() {
    return VKey.create(Domain.class, getDomainRepoId());
  }

  public Set<GracePeriodHistory> getGracePeriodHistories() {
    return nullToEmptyImmutableCopy(gracePeriodHistories);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @SuppressWarnings("unchecked")
  @Override
  public VKey<DomainHistory> createVKey() {
    return (VKey<DomainHistory>) createVKey(Key.create(this));
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getDomainBase().map(domainBase -> new Domain.Builder().copyFrom(domainBase).build());
  }

  @PostLoad
  void postLoad() {
    // TODO(b/188044616): Determine why Eager loading doesn't work here.
    Hibernate.initialize(domainTransactionRecords);
    Hibernate.initialize(nsHosts);
    Hibernate.initialize(dsDataHistories);
    Hibernate.initialize(gracePeriodHistories);

    if (domainBase != null) {
      domainBase.nsHosts = nullToEmptyImmutableCopy(nsHosts);
      domainBase.gracePeriods =
          gracePeriodHistories.stream()
              .map(GracePeriod::createFromHistory)
              .collect(toImmutableSet());
      domainBase.dsData =
          dsDataHistories.stream().map(DelegationSignerData::create).collect(toImmutableSet());
      // Normally Hibernate would see that the domain fields are all null and would fill
      // domainBase with a null object. Unfortunately, the updateTimestamp is never null in SQL.
      if (domainBase.getDomainName() == null) {
        domainBase = null;
      } else {
        if (domainBase.getRepoId() == null) {
          // domainBase still hasn't been fully constructed yet, so it's ok to go in and mutate
          // it.  In fact, we have to because going through the builder causes the hash codes of
          // contained objects to be calculated prematurely.
          domainBase.setRepoId(parent.getName());
        }
      }
    }
  }

  private static void fillAuxiliaryFieldsFromDomain(DomainHistory domainHistory) {
    if (domainHistory.domainBase != null) {
      domainHistory.nsHosts = nullToEmptyImmutableCopy(domainHistory.domainBase.nsHosts);
      domainHistory.dsDataHistories =
          nullToEmptyImmutableCopy(domainHistory.domainBase.getDsData()).stream()
              .filter(dsData -> dsData.getDigest() != null && dsData.getDigest().length > 0)
              .map(dsData -> DomainDsDataHistory.createFrom(domainHistory.id, dsData))
              .collect(toImmutableSet());
      domainHistory.gracePeriodHistories =
          nullToEmptyImmutableCopy(domainHistory.domainBase.getGracePeriods()).stream()
              .map(gracePeriod -> GracePeriodHistory.createFrom(domainHistory.id, gracePeriod))
              .collect(toImmutableSet());
    } else {
      domainHistory.nsHosts = ImmutableSet.of();
    }
  }

  /** Class to represent the composite primary key of {@link DomainHistory} entity. */
  public static class DomainHistoryId extends ImmutableObject implements Serializable {

    private String domainRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    private DomainHistoryId() {}

    public DomainHistoryId(String domainRepoId, long id) {
      this.domainRepoId = domainRepoId;
      this.id = id;
    }

    /** Returns the domain repository id. */
    public String getDomainRepoId() {
      return domainRepoId;
    }

    /** Returns the history revision id. */
    public long getId() {
      return id;
    }

    /**
     * Sets the domain repository id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setDomainRepoId(String domainRepoId) {
      this.domainRepoId = domainRepoId;
    }

    /**
     * Sets the history revision id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setId(long id) {
      this.id = id;
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

    public Builder setDomain(@Nullable DomainBase domainBase) {
      // Nullable for the sake of pre-Registry-3.0 history objects
      if (domainBase == null) {
        return this;
      }
      // TODO(b/203609982): if actual type of domainBase is Domain, convert to DomainBase
      // Note: a DomainHistory fetched by JPA has DomainBase in this field. Allowing Domain
      // in the setter makes equality checks messy.
      getInstance().domainBase = domainBase;
      if (domainBase instanceof Domain) {
        super.setParent(domainBase);
      } else {
        super.setParent(Key.create(Domain.class, domainBase.getRepoId()));
      }
      return this;
    }

    public Builder setDomainRepoId(String domainRepoId) {
      getInstance().parent = Key.create(Domain.class, domainRepoId);
      return this;
    }

    @Override
    public DomainHistory build() {
      DomainHistory instance = super.build();
      // TODO(b/171990736): Assert instance.domainBase is not null after database migration.
      // Note that we cannot assert that instance.domainBase is not null here because this
      // builder is also used to convert legacy HistoryEntry objects to DomainHistory, when
      // domainBase is not available.
      fillAuxiliaryFieldsFromDomain(instance);
      return instance;
    }

    public DomainHistory buildAndAssemble(
        ImmutableSet<DomainDsDataHistory> dsDataHistories,
        ImmutableSet<VKey<Host>> domainHistoryHosts,
        ImmutableSet<GracePeriodHistory> gracePeriodHistories,
        ImmutableSet<DomainTransactionRecord> transactionRecords) {
      DomainHistory instance = super.build();
      instance.dsDataHistories = dsDataHistories;
      instance.nsHosts = domainHistoryHosts;
      instance.gracePeriodHistories = gracePeriodHistories;
      instance.domainTransactionRecords = transactionRecords;
      instance.hashCode = null;
      return instance;
    }
  }
}
