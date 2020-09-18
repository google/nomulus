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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PostLoad;

/**
 * A persisted history entry representing an EPP modification to a host.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the host entity at this point in time. We persist a raw {@link HostBase} so that the
 * foreign-keyed fields in that class can refer to this object.
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
public class HostHistory extends HistoryEntry {

  // Store HostBase instead of HostResource so we don't pick up its @Id
  @Nullable HostBase hostBase;

  @Column(nullable = false)
  VKey<HostResource> hostRepoId;

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "TempHistorySequenceGenerator")
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
  public VKey<HostResource> getHostRepoId() {
    return hostRepoId;
  }

  @PostLoad
  void postLoad() {
    // Normally Hibernate would see that the host fields are all null and would fill hostBase
    // with a null object. Unfortunately, the updateTimestamp is never null in SQL.
    if (hostBase != null && hostBase.getHostName() == null) {
      hostBase = null;
    }
    // Fill in the full, symmetric, parent repo ID key
    Key<HostResource> parentKey = Key.create(HostResource.class, (String) hostRepoId.getSqlKey());
    parent = parentKey;
    hostRepoId = VKey.create(HostResource.class, hostRepoId.getSqlKey(), parentKey);
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

    public Builder setHostBase(HostBase hostBase) {
      getInstance().hostBase = hostBase;
      return this;
    }

    public Builder setHostRepoId(VKey<HostResource> hostRepoId) {
      getInstance().hostRepoId = hostRepoId;
      hostRepoId.maybeGetOfyKey().ifPresent(parent -> getInstance().parent = parent);
      return this;
    }

    // We can remove this once all HistoryEntries are converted to History objects
    @Override
    public Builder setParent(Key<? extends EppResource> parent) {
      super.setParent(parent);
      getInstance().hostRepoId =
          VKey.create(HostResource.class, parent.getName(), (Key<HostResource>) parent);
      return this;
    }
  }
}
