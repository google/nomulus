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

import google.registry.model.EppResource;
import google.registry.model.ImmutableObject;
import google.registry.model.contact.ContactHistory.ContactHistoryId;
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
@Access(AccessType.FIELD)
@IdClass(ContactHistoryId.class)
public class ContactHistory extends HistoryEntry {

  // Store ContactBase instead of Contact, so we don't pick up its @Id
  // Nullable for the sake of pre-Registry-3.0 history objects
  @DoNotCompare @Nullable ContactBase contactBase;

  @Id
  @Access(AccessType.PROPERTY)
  @SuppressWarnings("unused")
  // This method is private because it is only used by Hibernate.
  public String getContactRepoId() {
    return contactBase == null ? null : contactBase.getRepoId();
  }

  // This method is private because it is only used by Hibernate.
  // We also don't actually set anything because the information in contained in contactBase.
  @SuppressWarnings("unused")
  private void setContactRepoId(String contactRepoId) {}

  @Id
  @Column(name = "historyRevisionId")
  @Access(AccessType.PROPERTY)
  @Override
  @SuppressWarnings("unused")
  // This method is protected because it is only used by Hibernate.
  protected long getId() {
    return super.getId();
  }

  /**
   * The values of all the fields on the {@link ContactBase} object after the action represented by
   * this history object was executed.
   *
   * <p>Will be absent for objects created prior to the Registry 3.0 SQL migration.
   */
  public Optional<ContactBase> getContactBase() {
    return Optional.ofNullable(contactBase);
  }

  /** Creates a {@link VKey} instance for this entity. */
  @Override
  public VKey<ContactHistory> createVKey() {
    return VKey.createSql(ContactHistory.class, new ContactHistoryId(getContactRepoId(), getId()));
  }

  @Override
  public Optional<? extends EppResource> getResourceAtPointInTime() {
    return getContactBase().map(contactBase -> new Contact.Builder().copyFrom(contactBase).build());
  }

  /** Class to represent the composite primary key of {@link ContactHistory} entity. */
  public static class ContactHistoryId extends ImmutableObject implements Serializable {

    private String contactRepoId;

    private Long id;

    /** Hibernate requires this default constructor. */
    @SuppressWarnings("unused")
    private ContactHistoryId() {}

    public ContactHistoryId(String contactRepoId, long id) {
      this.contactRepoId = contactRepoId;
      this.id = id;
    }

    /**
     * Returns the contact repository id.
     *
     * <p>This method is private because it is only used by Hibernate.
     */
    public String getContactRepoId() {
      return contactRepoId;
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
     * Sets the contact repository id.
     *
     * <p>This method is private because it is only used by Hibernate and should not be used
     * externally to keep immutability.
     */
    @SuppressWarnings("unused")
    private void setContactRepoId(String contactRepoId) {
      this.contactRepoId = contactRepoId;
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

  public static class Builder extends HistoryEntry.Builder<ContactHistory, ContactHistory.Builder> {

    public Builder() {}

    public Builder(ContactHistory instance) {
      super(instance);
    }

    public Builder setContact(@Nullable ContactBase contactBase) {
      // Nullable for the sake of pre-Registry-3.0 history objects
      if (contactBase == null) {
        return this;
      }
      getInstance().contactBase = contactBase;
      return thisCastToDerived();
    }

    public Builder wipeOutPii() {
      getInstance().contactBase =
          getInstance().getContactBase().get().asBuilder().wipeOut().build();
      return this;
    }
  }
}
