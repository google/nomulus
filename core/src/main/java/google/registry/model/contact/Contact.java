// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A persistable contact resource including mutable and non-mutable fields.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5733">RFC 5733</a>
 */
@Entity
@Table(
    name = "Contact",
    indexes = {
      @Index(columnList = "creationTime"),
      @Index(columnList = "currentSponsorRegistrarId"),
      @Index(columnList = "deletionTime"),
      @Index(columnList = "contactId"),
      @Index(columnList = "searchName")
    })
@ExternalMessagingName("contact")
@WithVKey(String.class)
@Access(AccessType.FIELD)
public class Contact extends ContactBase implements ForeignKeyedEppResource {

  @Override
  public VKey<Contact> createVKey() {
    return VKey.create(Contact.class, getRepoId());
  }

  @Override
  @Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link Contact}, since it is immutable. */
  public static class Builder extends ContactBase.Builder<Contact, Builder> {

    public Builder() {}

    private Builder(Contact instance) {
      super(instance);
    }

    public Builder copyFrom(ContactBase contactBase) {
      return this.setAuthInfo(contactBase.getAuthInfo())
          .setContactId(contactBase.getContactId())
          .setCreationRegistrarId(contactBase.getCreationRegistrarId())
          .setCreationTime(contactBase.getCreationTime())
          .setDeletionTime(contactBase.getDeletionTime())
          .setDisclose(contactBase.getDisclose())
          .setEmailAddress(contactBase.getEmailAddress())
          .setFaxNumber(contactBase.getFaxNumber())
          .setInternationalizedPostalInfo(contactBase.getInternationalizedPostalInfo())
          .setLastTransferTime(contactBase.getLastTransferTime())
          .setLastEppUpdateRegistrarId(contactBase.getLastEppUpdateRegistrarId())
          .setLastEppUpdateTime(contactBase.getLastEppUpdateTime())
          .setLocalizedPostalInfo(contactBase.getLocalizedPostalInfo())
          .setPersistedCurrentSponsorRegistrarId(
              contactBase.getPersistedCurrentSponsorRegistrarId())
          .setRepoId(contactBase.getRepoId())
          .setStatusValues(contactBase.getStatusValues())
          .setTransferData(contactBase.getTransferData())
          .setVoiceNumber(contactBase.getVoiceNumber());
    }
  }
}
