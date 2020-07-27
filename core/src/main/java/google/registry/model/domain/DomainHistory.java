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

import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.util.Set;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.JoinTable;

/**
 * A persisted history entry representing an EPP modification to a domain.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the domain entity at this point in time. We persist a raw {@link DomainContent} so that
 * the foreign-keyed fields in that class can refer to this object.
 */
@Entity
@javax.persistence.Table(
    indexes = {
        @javax.persistence.Index(columnList = "creationTime"),
        @javax.persistence.Index(columnList = "historyRegistrarId"),
        @javax.persistence.Index(columnList = "historyType"),
        @javax.persistence.Index(columnList = "historyModificationTime")
    })
public class DomainHistory extends HistoryEntry {
  // Store DomainContent instead of DomainBase so we don't pick up its @Id
  DomainContent domainContent;

  @Column(nullable = false)
  VKey<DomainBase> domainRepoId;

  @ElementCollection
  @JoinTable(name = "DomainHistoryHost")
  @Access(AccessType.PROPERTY)
  public Set<VKey<HostResource>> getNsHosts() {
    return domainContent.nsHosts;
  }

  /** The state of the {@link DomainContent} object at this point in time. */
  public DomainContent getDomainContent() {
    return domainContent;
  }

  /** The key to the {@link ContactResource} this is based off of. */
  public VKey<DomainBase> getDomainRepoId() {
    return domainRepoId;
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
      return this;
    }

    public Builder setDomainRepoId(VKey<DomainBase> domainRepoId) {
      getInstance().domainRepoId = domainRepoId;
      domainRepoId.maybeGetOfyKey().ifPresent(parent -> getInstance().parent = parent);
      return this;
    }

    // We can remove this once all HistoryEntries are converted to History objects
    @Override
    public Builder setParent(Key<? extends EppResource> parent) {
      super.setParent(parent);
      getInstance().domainRepoId = VKey.create(DomainBase.class, parent.getName(), parent);
      return this;
    }
  }
}
