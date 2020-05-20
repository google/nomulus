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

package google.registry.model.contact;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.EntitySubclass;
import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;

/**
 * A persisted history entry representing an EPP modification to a contact.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the contact entity at this point in time. We persist a raw {@link ContactBase} so that
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
@EntitySubclass
@Access(AccessType.FIELD)
public class ContactHistory extends HistoryEntry implements SqlEntity {

  // Store ContactBase instead of ContactResource so we don't pick up its @Id
  ContactBase contactBase;

  @Column(nullable = false)
  VKey<ContactResource> contactRepoId;

  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "HistorySequenceGenerator")
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  public long getId() {
    return super.getId();
  }

  /** The state of the {@link ContactBase} object at this point in time. */
  public ContactBase getContactBase() {
    return contactBase;
  }

  /** The key to the {@link ContactResource} this is based off of. */
  public VKey<ContactResource> getContactRepoId() {
    return contactRepoId;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  // In Datastore, save as a HistoryEntry object
  @Override
  public ImmutableList<DatastoreEntity> toDatastoreEntities() {
    return ImmutableList.of(asHistoryEntry());
  }

  public static class Builder extends HistoryEntry.Builder<ContactHistory, ContactHistory.Builder> {

    public Builder() {}

    public Builder(ContactHistory instance) {
      super(instance);
    }

    public Builder setContactBase(ContactBase contactBase) {
      getInstance().contactBase = contactBase;
      return this;
    }

    public Builder setContactRepoId(VKey<ContactResource> contactRepoId) {
      getInstance().contactRepoId = contactRepoId;
      contactRepoId.maybeGetOfyKey().ifPresent(parent -> getInstance().parent = parent);
      return this;
    }

    // We can remove this once all HistoryEntries are converted to History objects
    @Override
    public Builder setParent(Key<? extends EppResource> parent) {
      super.setParent(parent);
      getInstance().contactRepoId =
          VKey.create(ContactResource.class, parent.getName(), (Key<ContactResource>) parent);
      return this;
    }
  }
}
