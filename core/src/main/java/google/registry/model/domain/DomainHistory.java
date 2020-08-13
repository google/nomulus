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

import static google.registry.util.CollectionUtils.nullToEmptyImmutableCopy;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainHistory.DomainHistoryId;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.io.Serializable;
import java.util.Set;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.Index;
import javax.persistence.JoinTable;
import javax.persistence.PostLoad;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainContent} so that
 * the foreign-keyed fields in that class can refer to this object.
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
  // Store DomainContent instead of DomainBase so we don't pick up its @Id
  DomainContent domainContent;

  @Id String domainRepoId;

  // We could have reused domainContent.nsHosts here, but Hibernate throws a weird exception after
  // we change to use a composite primary key.
  @ElementCollection
  @JoinTable(name = "DomainHistoryHost")
  @Column(name = "host_repo_id")
  Set<VKey<HostResource>> nsHosts;

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "HistorySequenceGenerator")
  @SequenceGenerator(
      name = "HistorySequenceGenerator",
      sequenceName = "history_id_sequence",
      allocationSize = 1)
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public Long getId() {
    return super.getId();
  }

  /** Returns keys to the {@link HostResource} that are the nameservers for the domain. */
  public Set<VKey<HostResource>> getNsHosts() {
    return nsHosts;
  }

  /** The state of the {@link DomainContent} object at this point in time. */
  public DomainContent getDomainContent() {
    return domainContent;
  }

  /** The key to the {@link DomainBase} this is based off of. */
  public VKey<DomainBase> getDomainRepoId() {
    return VKey.create(DomainBase.class, domainRepoId, Key.create(DomainBase.class, domainRepoId));
  }

  public VKey<DomainHistory> createVKey() {
    return VKey.createSql(DomainHistory.class, new DomainHistoryId(getId(), domainRepoId));
  }

  @PostLoad
  void postLoad() {
    if (domainContent != null) {
      domainContent.nsHosts = nullToEmptyImmutableCopy(nsHosts);
    }
  }

  /** Class to represent the composite primary key of {@link DomainHistory} entity. */
  static class DomainHistoryId extends ImmutableObject implements Serializable {

    private Long id;

    private String domainRepoId;

    private DomainHistoryId() {}

    DomainHistoryId(long id, String domainRepoId) {
      this.id = id;
      this.domainRepoId = domainRepoId;
    }

    Long getId() {
      return id;
    }

    void setId(Long id) {
      this.id = id;
    }

    String getDomainRepoId() {
      return domainRepoId;
    }

    void setDomainRepoId(String domainRepoId) {
      this.domainRepoId = domainRepoId;
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

    public Builder setDomainContent(DomainContent domainContent) {
      getInstance().domainContent = domainContent;
      if (domainContent != null) {
        getInstance().nsHosts = nullToEmptyImmutableCopy(domainContent.nsHosts);
      }
      return this;
    }

    public Builder setDomainRepoId(String domainRepoId) {
      getInstance().domainRepoId = domainRepoId;
      getInstance().parent = Key.create(DomainBase.class, domainRepoId);
      return this;
    }

    // We can remove this once all HistoryEntries are converted to History objects
    @Override
    public Builder setParent(Key<? extends EppResource> parent) {
      super.setParent(parent);
      getInstance().domainRepoId = parent.getName();
      return this;
    }
  }
}
