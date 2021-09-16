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

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.annotations.ExternalMessagingName;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.replay.DatastoreAndSqlEntity;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import javax.persistence.Access;
import javax.persistence.AccessType;
import org.joda.time.DateTime;

/**
 * A persistable contact resource including mutable and non-mutable fields.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5733">RFC 5733</a>
 */
@ReportedOn
@Entity
@javax.persistence.Entity(name = "Contact")
@javax.persistence.Table(
    name = "Contact",
    indexes = {
      @javax.persistence.Index(columnList = "creationTime"),
      @javax.persistence.Index(columnList = "currentSponsorRegistrarId"),
      @javax.persistence.Index(columnList = "deletionTime"),
      @javax.persistence.Index(columnList = "contactId"),
      @javax.persistence.Index(columnList = "searchName")
    })
@ExternalMessagingName("contact")
@WithStringVKey
@Access(AccessType.FIELD)
public class ContactResource extends ContactBase
    implements DatastoreAndSqlEntity, ForeignKeyedEppResource {

  @Override
  public VKey<ContactResource> createVKey() {
    return VKey.create(ContactResource.class, getRepoId(), Key.create(this));
  }

  @Override
  @javax.persistence.Id
  @Access(AccessType.PROPERTY)
  public String getRepoId() {
    return super.getRepoId();
  }

  @Override
  public ContactResource cloneProjectedAtTime(DateTime now) {
    return ContactBase.cloneContactProjectedAtTime(this, now);
  }

  @Override
  public void beforeDatastoreSaveOnReplay() {
    saveIndexesToDatastore();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link ContactResource}, since it is immutable. */
  public static class Builder extends ContactBase.Builder<ContactResource, Builder> {

    public Builder() {}

    private Builder(ContactResource instance) {
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
