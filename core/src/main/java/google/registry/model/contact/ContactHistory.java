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

package google.registry.model.contact;

import google.registry.model.EppResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A persisted history entry representing an EPP modification to a contact.
 *
 * <p>In addition to the general history fields (e.g. action time, registrar ID) we also persist a
 * copy of the contact entity at this point in time. We persist a raw {@link ContactBase} so that
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
@AttributeOverride(name = "repoId", column = @Column(name = "contactRepoId"))
@Access(AccessType.FIELD)
public class ContactHistory extends HistoryEntry {

  // Store ContactBase instead of Contact, so we don't pick up its @Id
  // @Nullable for the sake of pre-Registry-3.0 history objects
  @Nullable ContactBase resource;

  @Override
  protected ContactBase getResource() {
    return resource;
  }

  /**
   * The values of all the fields on the {@link ContactBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<ContactBase> getContactBase() {
    return Optional.ofNullable(resource);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<ContactHistory> createVKey() {
    return VKey.create(ContactHistory.class, getHistoryEntryId());
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getContactBase().map(contactBase -> new Contact.Builder().copyFrom(contactBase).build());
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends HistoryEntry.Builder<ContactHistory, ContactHistory.Builder> {

    public Builder() {}

    public Builder(ContactHistory instance) {
      super(instance);
    }

    public Builder setContact(ContactBase contactBase) {
      getInstance().resource = contactBase;
      return setRepoId(contactBase);
    }

    public Builder wipeOutPii() {
      getInstance().resource = getInstance().resource.asBuilder().wipeOut().build();
      return this;
    }
  }
}
