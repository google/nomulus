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

import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.UnsafeSerializable;
import google.registry.model.host.HostHistory.HostHistoryId;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.io.Serializable;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.Table;

/**
 * A persisted history entry representing an EPP modification to a host.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the host entity at this point in time. We persist a raw {@link HostBase} so that the
 * foreign-keyed fields in that class can refer to this object.
 */
@Entity
@Table(
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "historyRegistrarId"),
      @Index(columnList = "hostName"),
      @Index(columnList = "historyType"),
      @Index(columnList = "historyModificationTime")
    })
@Access(AccessType.FIELD)
@IdClass(HostHistoryId.class)
public class HostHistory extends HistoryEntry implements UnsafeSerializable {

  // Store HostBase instead of Host, so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @DoNotCompare @Nullable HostBase hostBase;

  @Id
  @Access(AccessType.PROPERTY)
  private String getHostRepoId() {
    return hostBase == null ? null : hostBase.getRepoId();
  }

  // This method is private because it is only used by Hibernate.
  // We also don't actually set anything because the information in contained in hostBase.
  @SuppressWarnings("unused")
  private void setHostRepoId(String hostRepoId) {}

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  protected long getId() {
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

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<HostHistory> createVKey() {
    return VKey.createSql(HostHistory.class, new HostHistoryId(getHostRepoId(), getId()));
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getHostBase().map(hostBase -> new Host.Builder().copyFrom(hostBase).build());
  }

  /** Class to represent the composite primary key of {@link HostHistory} entity. */
  public static class HostHistoryId extends ImmutableObject implements Serializable {

    private String hostRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    @SuppressWarnings("unused")
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
    public String getHostRepoId() {
      return hostRepoId;
    }

    /**
     * Returns the history revision id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    public long getId() {
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
      return thisCastToDerived();
    }
  }
}
