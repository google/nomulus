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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.model.EppResourceUtils.projectResourceOntoBuilderAtTime;

import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.IgnoreSave;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.condition.IfNull;
import google.registry.model.EppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Embedded;
import javax.persistence.MappedSuperclass;
import javax.xml.bind.annotation.XmlElement;
import org.joda.time.DateTime;

/**
 * A persistable contact resource including mutable and non-mutable fields.
 *
 * @see <a href="https://tools.ietf.org/html/rfc5733">RFC 5733</a>
 *     <p>This class deliberately does not include an {@link javax.persistence.Id} so that any
 *     foreign-keyed fields can refer to the proper parent entity's ID, whether we're storing this
 *     in the DB itself or as part of another entity
 */
@MappedSuperclass
@Embeddable
@Access(AccessType.FIELD)
public class ContactBase extends EppResource implements ResourceWithTransferData {

  /**
   * Unique identifier for this contact.
   *
   * <p>This is only unique in the sense that for any given lifetime specified as the time range
   * from (creationTime, deletionTime) there can only be one contact in Datastore with this id.
   * However, there can be many contacts with the same id and non-overlapping lifetimes.
   */
  String contactId;

  /**
   * Localized postal info for the contact. All contained values must be representable in the 7-bit
   * US-ASCII character set. Personal info; cleared by {@link ContactResource.Builder#wipeOut}.
   */
  @IgnoreSave(IfNull.class)
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "name", column = @Column(name = "addr_local_name")),
    @AttributeOverride(name = "org", column = @Column(name = "addr_local_org")),
    @AttributeOverride(name = "type", column = @Column(name = "addr_local_type")),
    @AttributeOverride(
        name = "address.streetLine1",
        column = @Column(name = "addr_local_street_line1")),
    @AttributeOverride(
        name = "address.streetLine2",
        column = @Column(name = "addr_local_street_line2")),
    @AttributeOverride(
        name = "address.streetLine3",
        column = @Column(name = "addr_local_street_line3")),
    @AttributeOverride(name = "address.city", column = @Column(name = "addr_local_city")),
    @AttributeOverride(name = "address.state", column = @Column(name = "addr_local_state")),
    @AttributeOverride(name = "address.zip", column = @Column(name = "addr_local_zip")),
    @AttributeOverride(
        name = "address.countryCode",
        column = @Column(name = "addr_local_country_code"))
  })
  PostalInfo localizedPostalInfo;

  /**
   * Internationalized postal info for the contact. Personal info; cleared by {@link
   * ContactResource.Builder#wipeOut}.
   */
  @IgnoreSave(IfNull.class)
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "name", column = @Column(name = "addr_i18n_name")),
    @AttributeOverride(name = "org", column = @Column(name = "addr_i18n_org")),
    @AttributeOverride(name = "type", column = @Column(name = "addr_i18n_type")),
    @AttributeOverride(
        name = "address.streetLine1",
        column = @Column(name = "addr_i18n_street_line1")),
    @AttributeOverride(
        name = "address.streetLine2",
        column = @Column(name = "addr_i18n_street_line2")),
    @AttributeOverride(
        name = "address.streetLine3",
        column = @Column(name = "addr_i18n_street_line3")),
    @AttributeOverride(name = "address.city", column = @Column(name = "addr_i18n_city")),
    @AttributeOverride(name = "address.state", column = @Column(name = "addr_i18n_state")),
    @AttributeOverride(name = "address.zip", column = @Column(name = "addr_i18n_zip")),
    @AttributeOverride(
        name = "address.countryCode",
        column = @Column(name = "addr_i18n_country_code"))
  })
  PostalInfo internationalizedPostalInfo;

  /**
   * Contact name used for name searches. This is set automatically to be the internationalized
   * postal name, or if null, the localized postal name, or if that is null as well, null. Personal
   * info; cleared by {@link ContactResource.Builder#wipeOut}.
   */
  @Index String searchName;

  /** Contact’s voice number. Personal info; cleared by {@link ContactResource.Builder#wipeOut}. */
  @IgnoreSave(IfNull.class)
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "phoneNumber", column = @Column(name = "voice_phone_number")),
    @AttributeOverride(name = "extension", column = @Column(name = "voice_phone_extension")),
  })
  ContactPhoneNumber voice;

  /** Contact’s fax number. Personal info; cleared by {@link ContactResource.Builder#wipeOut}. */
  @IgnoreSave(IfNull.class)
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "phoneNumber", column = @Column(name = "fax_phone_number")),
    @AttributeOverride(name = "extension", column = @Column(name = "fax_phone_extension")),
  })
  ContactPhoneNumber fax;

  /** Contact’s email address. Personal info; cleared by {@link ContactResource.Builder#wipeOut}. */
  @IgnoreSave(IfNull.class)
  String email;

  /** Authorization info (aka transfer secret) of the contact. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "pw.value", column = @Column(name = "auth_info_value")),
    @AttributeOverride(name = "pw.repoId", column = @Column(name = "auth_info_repo_id")),
  })
  ContactAuthInfo authInfo;

  /** Data about any pending or past transfers on this contact. */
  ContactTransferData transferData;

  /**
   * The time that this resource was last transferred.
   *
   * <p>Can be null if the resource has never been transferred.
   */
  DateTime lastTransferTime;

  // If any new fields are added which contain personal information, make sure they are cleared by
  // the wipeOut() function, so that data is not kept around for deleted contacts.

  /** Disclosure policy. */
  @Embedded
  @AttributeOverrides({
    @AttributeOverride(name = "name", column = @Column(name = "disclose_types_name")),
    @AttributeOverride(name = "org", column = @Column(name = "disclose_types_org")),
    @AttributeOverride(name = "addr", column = @Column(name = "disclose_types_addr")),
    @AttributeOverride(name = "flag", column = @Column(name = "disclose_mode_flag")),
    @AttributeOverride(name = "voice.marked", column = @Column(name = "disclose_show_voice")),
    @AttributeOverride(name = "fax.marked", column = @Column(name = "disclose_show_fax")),
    @AttributeOverride(name = "email.marked", column = @Column(name = "disclose_show_email"))
  })
  Disclose disclose;

  @Override
  public VKey<? extends ContactBase> createVKey() {
    // TODO(mmuller): create symmetric keys if we can ever reload both sides.
    return VKey.create(ContactBase.class, getRepoId(), Key.create(this));
  }

  public String getContactId() {
    return contactId;
  }

  public PostalInfo getLocalizedPostalInfo() {
    return localizedPostalInfo;
  }

  public PostalInfo getInternationalizedPostalInfo() {
    return internationalizedPostalInfo;
  }

  public String getSearchName() {
    return searchName;
  }

  public ContactPhoneNumber getVoiceNumber() {
    return voice;
  }

  public ContactPhoneNumber getFaxNumber() {
    return fax;
  }

  public String getEmailAddress() {
    return email;
  }

  public ContactAuthInfo getAuthInfo() {
    return authInfo;
  }

  public Disclose getDisclose() {
    return disclose;
  }

  public final String getCurrentSponsorClientId() {
    return getPersistedCurrentSponsorClientId();
  }

  @Override
  public final ContactTransferData getTransferData() {
    return Optional.ofNullable(transferData).orElse(ContactTransferData.EMPTY);
  }

  @Override
  public DateTime getLastTransferTime() {
    return lastTransferTime;
  }

  @Override
  public String getForeignKey() {
    return contactId;
  }

  /**
   * Postal info for the contact.
   *
   * <p>The XML marshalling expects the {@link PostalInfo} objects in a list, but we can't actually
   * persist them to Datastore that way because Objectify can't handle collections of embedded
   * objects that themselves contain collections, and there's a list of streets inside. This method
   * transforms the persisted format to the XML format for marshalling.
   */
  @XmlElement(name = "postalInfo")
  public ImmutableList<PostalInfo> getPostalInfosAsList() {
    return Stream.of(localizedPostalInfo, internationalizedPostalInfo)
        .filter(Objects::nonNull)
        .collect(toImmutableList());
  }

  @Override
  public ContactBase cloneProjectedAtTime(DateTime now) {
    return cloneContactProjectedAtTime(this, now);
  }

  /**
   * Clones the contact (or subclass). A separate static method so that we can pass in and return a
   * T without the compiler complaining.
   */
  protected static <T extends ContactBase> T cloneContactProjectedAtTime(T contact, DateTime now) {
    Builder builder = contact.asBuilder();
    projectResourceOntoBuilderAtTime(contact, builder, now);
    return (T) builder.build();
  }

  @Override
  public Builder asBuilder() {
    return new Builder<>(clone(this));
  }

  /** A builder for constructing {@link ContactResource}, since it is immutable. */
  public static class Builder<T extends ContactBase, B extends Builder<T, B>>
      extends EppResource.Builder<T, B> implements BuilderWithTransferData<ContactTransferData, B> {

    public Builder() {}

    protected Builder(T instance) {
      super(instance);
    }

    public B setContactId(String contactId) {
      getInstance().contactId = contactId;
      return thisCastToDerived();
    }

    public B setLocalizedPostalInfo(PostalInfo localizedPostalInfo) {
      checkArgument(
          localizedPostalInfo == null
              || PostalInfo.Type.LOCALIZED.equals(localizedPostalInfo.getType()));
      getInstance().localizedPostalInfo = localizedPostalInfo;
      return thisCastToDerived();
    }

    public B setInternationalizedPostalInfo(PostalInfo internationalizedPostalInfo) {
      checkArgument(
          internationalizedPostalInfo == null
              || PostalInfo.Type.INTERNATIONALIZED.equals(internationalizedPostalInfo.getType()));
      getInstance().internationalizedPostalInfo = internationalizedPostalInfo;
      return thisCastToDerived();
    }

    public B overlayLocalizedPostalInfo(PostalInfo localizedPostalInfo) {
      return setLocalizedPostalInfo(
          getInstance().localizedPostalInfo == null
              ? localizedPostalInfo
              : getInstance().localizedPostalInfo.overlay(localizedPostalInfo));
    }

    public B overlayInternationalizedPostalInfo(PostalInfo internationalizedPostalInfo) {
      return setInternationalizedPostalInfo(
          getInstance().internationalizedPostalInfo == null
              ? internationalizedPostalInfo
              : getInstance().internationalizedPostalInfo.overlay(internationalizedPostalInfo));
    }

    public B setVoiceNumber(ContactPhoneNumber voiceNumber) {
      getInstance().voice = voiceNumber;
      return thisCastToDerived();
    }

    public B setFaxNumber(ContactPhoneNumber faxNumber) {
      getInstance().fax = faxNumber;
      return thisCastToDerived();
    }

    public B setEmailAddress(String emailAddress) {
      getInstance().email = emailAddress;
      return thisCastToDerived();
    }

    public B setAuthInfo(ContactAuthInfo authInfo) {
      getInstance().authInfo = authInfo;
      return thisCastToDerived();
    }

    public B setDisclose(Disclose disclose) {
      getInstance().disclose = disclose;
      return thisCastToDerived();
    }

    @Override
    public B setTransferData(ContactTransferData transferData) {
      getInstance().transferData = transferData;
      return thisCastToDerived();
    }

    @Override
    public B setLastTransferTime(DateTime lastTransferTime) {
      getInstance().lastTransferTime = lastTransferTime;
      return thisCastToDerived();
    }

    /**
     * Remove all personally identifying information about a contact.
     *
     * <p>This should be used when deleting a contact so that the soft-deleted entity doesn't
     * contain information that the registrant requested to be deleted.
     */
    public B wipeOut() {
      setEmailAddress(null);
      setFaxNumber(null);
      setInternationalizedPostalInfo(null);
      setLocalizedPostalInfo(null);
      setVoiceNumber(null);
      return thisCastToDerived();
    }

    @Override
    public T build() {
      T instance = getInstance();
      // If TransferData is totally empty, set it to null.
      if (ContactTransferData.EMPTY.equals(instance.transferData)) {
        setTransferData(null);
      }
      // Set the searchName using the internationalized and localized postal info names.
      if ((instance.internationalizedPostalInfo != null)
          && (instance.internationalizedPostalInfo.getName() != null)) {
        instance.searchName = instance.internationalizedPostalInfo.getName();
      } else if ((instance.localizedPostalInfo != null)
          && (instance.localizedPostalInfo.getName() != null)) {
        instance.searchName = instance.localizedPostalInfo.getName();
      } else {
        instance.searchName = null;
      }
      return super.build();
    }
  }
}
