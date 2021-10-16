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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.domain.GracePeriod.GracePeriodHistory;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.domain.secdns.DomainDsDataHistory;
import google.registry.model.host.HostResource;
import google.registry.model.replay.DatastoreEntity;
import google.registry.model.replay.SqlEntity;
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
 * copy of the domain entity at this point in time. We persist a raw {@link DomainContent} so that
 * the foreign-keyed fields in that class can refer to this object.
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
public class DomainHistory extends HistoryEntry implements SqlEntity {

  // Store DomainContent instead of DomainBase so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable DomainContent domainContent;

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
    parent = Key.create(DomainBase.class, domainRepoId);
  }

  // We could have reused domainContent.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  // TODO(b/166776754): Investigate if we can reuse domainContent.nsHosts for storing host keys.
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
  Set<VKey<HostResource>> nsHosts;

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
  Set<DomainDsDataHistory> dsDataHistories = new HashSet<>();

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
      fetch = FetchType.EAGER)
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
    this.domainTransactionRecords = domainTransactionRecords;
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

  /** Returns keys to the {@link HostResource} that are the nameservers for the domain. */
  public Set<VKey<HostResource>> getNsHosts() {
    return nsHosts;
  }

  /** Returns the collection of {@link DomainDsDataHistory} instances. */
  public ImmutableSet<DomainDsDataHistory> getDsDataHistories() {
    return nullToEmptyImmutableCopy(dsDataHistories);
  }

  /**
   * The values of all the fields on the {@link DomainContent} object after the action represented
   * by this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<DomainContent> getDomainContent() {
    return Optional.ofNullable(domainContent);
  }

  /** The key to the {@link DomainBase} this is based off of. */
  public VKey<DomainBase> getParentVKey() {
    return VKey.create(DomainBase.class, getDomainRepoId());
  }

  public Set<GracePeriodHistory> getGracePeriodHistories() {
    return nullToEmptyImmutableCopy(gracePeriodHistories);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @SuppressWarnings("unchecked")
  public VKey<DomainHistory> createVKey() {
    return (VKey<DomainHistory>) createVKey(Key.create(this));
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getDomainContent()
        .map(domainContent -> new DomainBase.Builder().copyFrom(domainContent).build());
  }

  @PostLoad
  void postLoad() {
    // TODO(b/188044616): Determine why Eager loading doesn't work here.
    Hibernate.initialize(domainTransactionRecords);
    Hibernate.initialize(nsHosts);
    Hibernate.initialize(dsDataHistories);
    Hibernate.initialize(gracePeriodHistories);

    if (domainContent != null) {
      domainContent.nsHosts = nullToEmptyImmutableCopy(nsHosts);
      domainContent.gracePeriods =
          gracePeriodHistories.stream()
              .map(GracePeriod::createFromHistory)
              .collect(toImmutableSet());
      domainContent.dsData =
          dsDataHistories.stream().map(DelegationSignerData::create).collect(toImmutableSet());
      // Normally Hibernate would see that the domain fields are all null and would fill
      // domainContent with a null object. Unfortunately, the updateTimestamp is never null in SQL.
      if (domainContent.getDomainName() == null) {
        domainContent = null;
      } else {
        if (domainContent.getRepoId() == null) {
          // domainContent still hasn't been fully constructed yet, so it's ok to go in and mutate
          // it.  In fact, we have to because going through the builder causes the hash codes of
          // contained objects to be calculated prematurely.
          domainContent.setRepoId(parent.getName());
        }
      }
    }
  }

  // In Datastore, save as a HistoryEntry object regardless of this object's type
  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.of(asHistoryEntry());
  }

  // Used to fill out the domainContent field during asynchronous replay
  @Override
  public void beforeSqlSaveOnReplay() {
    if (domainContent == null) {
      domainContent = jpaTm().getEntityManager().find(DomainBase.class, getDomainRepoId());
      fillAuxiliaryFieldsFromDomain(this);
    }
  }

  private static void fillAuxiliaryFieldsFromDomain(DomainHistory domainHistory) {
    if (domainHistory.domainContent != null) {
      domainHistory.nsHosts = nullToEmptyImmutableCopy(domainHistory.domainContent.nsHosts);
      domainHistory.dsDataHistories =
          nullToEmptyImmutableCopy(domainHistory.domainContent.getDsData()).stream()
              .map(dsData -> DomainDsDataHistory.createFrom(domainHistory.id, dsData))
              .collect(toImmutableSet());
      domainHistory.gracePeriodHistories =
          nullToEmptyImmutableCopy(domainHistory.domainContent.getGracePeriods()).stream()
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

    /**
     * Returns the domain repository id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private String getDomainRepoId() {
      return domainRepoId;
    }

    /**
     * Returns the history revision id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private long getId() {
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

    public Builder setDomain(@Nullable DomainContent domainContent) {
      // Nullable for the sake of pre-Registry-3.0 history objects
      if (domainContent == null) {
        return this;
      }
      // TODO(b/203609982): if actual type of domainContent is DomainBase, convert to DomainContent
      // Note: a DomainHistory fetched by JPA has DomainContent in this field. Allowing DomainBase
      // in the setter makes equality checks messy.
      getInstance().domainContent = domainContent;
      if (domainContent instanceof DomainBase) {
        super.setParent(domainContent);
      } else {
        super.setParent(Key.create(DomainBase.class, domainContent.getRepoId()));
      }
      return this;
    }

    public Builder setDomainRepoId(String domainRepoId) {
      getInstance().parent = Key.create(DomainBase.class, domainRepoId);
      return this;
    }

    @Override
    public DomainHistory build() {
      DomainHistory instance = super.build();
      // TODO(b/171990736): Assert instance.domainContent is not null after database migration.
      // Note that we cannot assert that instance.domainContent is not null here because this
      // builder is also used to convert legacy HistoryEntry objects to DomainHistory, when
      // domainContent is not available.
      fillAuxiliaryFieldsFromDomain(instance);
      return instance;
    }

    public DomainHistory buildAndAssemble(
        ImmutableSet<DomainDsDataHistory> dsDataHistories,
        ImmutableSet<VKey<HostResource>> domainHistoryHosts,
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
