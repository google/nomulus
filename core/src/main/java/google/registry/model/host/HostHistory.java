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

package google.registry.model.host;

import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.ImmutableObject;
import google.registry.model.host.HostHistory.HostHistoryId;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.io.Serializable;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.PostLoad;

/**
 * A persisted history entry representing an EPP modification to a host.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the host entity at this point in time. We persist a raw {@link HostBase} so that the
 * foreign-keyed fields in that class can refer to this object.
 *
 * <p>This class is only marked as a Datastore entity subclass and registered with Objectify so that
 * when building it its ID can be auto-populated by Objectify. It is converted to its superclass
 * {@link HistoryEntry} when persisted to Datastore using {@link
 * google.registry.persistence.transaction.TransactionManager}.
 */
@Entity
@javax.persistence.Table(
    indexes = {
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "historyRegistrarId"),
      @javax.persistence.Index(columnList = "hostName"),
      @javax.persistence.Index(columnList = "historyType"),
      @javax.persistence.Index(columnList = "historyModificationTime")
    })
@EntitySubclass
@Access(AccessType.FIELD)
@IdClass(HostHistoryId.class)
public class HostHistory extends HistoryEntry implements SqlEntity {

  // Store HostBase instead of HostResource so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable HostBase hostBase;

  @Id
  @Access(AccessType.PROPERTY)
  public String getHostRepoId() {
    // We need to handle null case here because Hibernate sometimes accesses this method before
    // parent gets initialized
    return parent == null ? null : parent.getName();
  }

  /** This method is private because it is only used by Hibernate. */
  @SuppressWarnings("unused")
  private void setHostRepoId(String hostRepoId) {
    parent = Key.create(HostResource.class, hostRepoId);
  }

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  /**
   * The values of all the fields on the {@link HostBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<HostBase> getHostBase() {
    return Optional.ofNullable(hostBase);
  }

  /** The key to the {@link google.registry.model.host.HostResource} this is based off of. */
  public VKey<HostResource> getParentVKey() {
    return VKey.create(HostResource.class, getHostRepoId());
  }

  /** Creates a {@link VKey} instance for this entity. */
  @SuppressWarnings("unchecked")
  public VKey<HostHistory> createVKey() {
    return (VKey<HostHistory>) createVKey(Key.create(this));
  }

  @PostLoad
  void postLoad() {
    // Normally Hibernate would see that the host fields are all null and would fill hostBase
    // with a null object. Unfortunately, the updateTimestamp is never null in SQL.
    if (hostBase != null && hostBase.getHostName() == null) {
      hostBase = null;
    }
    if (hostBase != null && hostBase.getRepoId() == null) {
      hostBase = hostBase.asBuilder().setRepoId(parent.getName()).build();
    }
  }

  // In Datastore, save as a HistoryEntry object regardless of this object's type
  @Override
  public Optional<DatastoreEntity> toDatastoreEntity() {
    return Optional.of(asHistoryEntry());
  }

  // Used to fill out the hostBase field during asynchronous replay
  public static void beforeSqlSave(HostHistory hostHistory) {
    hostHistory.hostBase =
        jpaTm().loadByKey(VKey.createSql(HostResource.class, hostHistory.getHostRepoId()));
  }

  /** Class to represent the composite primary key of {@link HostHistory} entity. */
  public static class HostHistoryId extends ImmutableObject implements Serializable {

    private String hostRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    private HostHistoryId() {}

    public HostHistoryId(String hostRepoId, long id) {
      this.hostRepoId = hostRepoId;
      this.id = id;
    }

    /**
     * Returns the host repository id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    @SuppressWarnings("unused")
    private String getHostRepoId() {
      return hostRepoId;
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
     * Sets the host repository id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setHostRepoId(String hostRepoId) {
      this.hostRepoId = hostRepoId;
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

  public static class Builder extends HistoryEntry.Builder<HostHistory, Builder> {

    public Builder() {}

    public Builder(HostHistory instance) {
      super(instance);
    }

    public Builder setHost(@Nullable HostBase hostBase) {
      // Nullable for the sake of pre-Registry-3.0 history objects
      if (hostBase == null) {
        return this;
      }
      getInstance().hostBase = hostBase;
      return super.setParent(hostBase);
    }

    public Builder setHostRepoId(String hostRepoId) {
      getInstance().parent = Key.create(HostResource.class, hostRepoId);
      return this;
    }
  }
}
