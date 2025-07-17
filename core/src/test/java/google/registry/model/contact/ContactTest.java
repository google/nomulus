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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.testing.ContactSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.Disclose.PostalInfoChoice;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Contact}. */
public class ContactTest extends EntityTestCase {

  private Contact originalContact;
  private Contact contact;

  ContactTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    createTld("foobar");
    originalContact =
        new Contact.Builder()
            .setContactId("contact_id")
            .setRepoId("1-FOOBAR")
            .setCreationRegistrarId("TheRegistrar")
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateRegistrarId("NewRegistrar")
            .setLastTransferTime(fakeClock.nowUtc())
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .setLocalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(Type.LOCALIZED)
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("111 8th Ave", "4th Floor"))
                            .setCity("New York")
                            .setState("NY")
                            .setZip("10011")
                            .setCountryCode("US")
                            .build())
                    .build())
            .setInternationalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(Type.INTERNATIONALIZED)
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("111 8th Ave", "4th Floor"))
                            .setCity("New York")
                            .setState("NY")
                            .setZip("10011")
                            .setCountryCode("US")
                            .build())
                    .build())
            .setVoiceNumber(new ContactPhoneNumber.Builder().setPhoneNumber("867-5309").build())
            .setFaxNumber(
                new ContactPhoneNumber.Builder()
                    .setPhoneNumber("867-5309")
                    .setExtension("1000")
                    .build())
            .setEmailAddress("jenny@example.com")
            .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("passw0rd")))
            .setDisclose(
                new Disclose.Builder()
                    .setVoice(new PresenceMarker())
                    .setEmail(new PresenceMarker())
                    .setFax(new PresenceMarker())
                    .setFlag(true)
                    .setAddrs(ImmutableList.of(PostalInfoChoice.create(Type.INTERNATIONALIZED)))
                    .setNames(ImmutableList.of(PostalInfoChoice.create(Type.INTERNATIONALIZED)))
                    .setOrgs(ImmutableList.of(PostalInfoChoice.create(Type.INTERNATIONALIZED)))
                    .build())
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .setTransferData(
                new ContactTransferData.Builder()
                    .setGainingRegistrarId("TheRegistrar")
                    .setLosingRegistrarId("NewRegistrar")
                    .setPendingTransferExpirationTime(fakeClock.nowUtc())
                    .setTransferRequestTime(fakeClock.nowUtc())
                    .setTransferStatus(TransferStatus.SERVER_APPROVED)
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build();
    // Set up a new persisted Contact entity.
    contact = persistResource(cloneAndSetAutoTimestamps(originalContact));
  }

  @Test
  void testContactBaseToContact() {
    assertAboutImmutableObjects()
        .that(new Contact.Builder().copyFrom(contact).build())
        .isEqualExceptFields(contact, "updateTimestamp", "revisions");
  }

  @Test
  void testCloudSqlPersistence_failWhenViolateForeignKeyConstraint() {
    assertThrowForeignKeyViolation(
        () ->
            persistResource(
                originalContact
                    .asBuilder()
                    .setRepoId("2-FOOBAR")
                    .setCreationRegistrarId("nonexistent-registrar")
                    .build()));
  }

  @Test
  void testCloudSqlPersistence_succeed() {
    Contact persisted = loadByEntity(originalContact);
    Contact fixed =
        originalContact
            .asBuilder()
            .setCreationTime(persisted.getCreationTime())
            .setTransferData(
                originalContact
                    .getTransferData()
                    .asBuilder()
                    .setServerApproveEntities(null, null, null)
                    .build())
            .build();
    assertAboutImmutableObjects().that(persisted).isEqualExceptFields(fixed, "updateTimestamp");
  }

  @Test
  void testPersistence() {
    assertThat(loadByForeignKey(Contact.class, contact.getForeignKey(), fakeClock.nowUtc()))
        .hasValue(contact);
  }

  @Test
  void testSerializable() {
    Contact persisted =
        loadByForeignKey(Contact.class, contact.getForeignKey(), fakeClock.nowUtc()).get();
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testEmptyStringsBecomeNull() {
    assertThat(new Contact.Builder().setContactId(null).build().getContactId()).isNull();
    assertThat(new Contact.Builder().setContactId("").build().getContactId()).isNull();
    assertThat(new Contact.Builder().setContactId(" ").build().getContactId()).isNotNull();
    // Nested ImmutableObjects should also be fixed
    assertThat(
            new Contact.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName(null).build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNull();
    assertThat(
            new Contact.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName("").build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNull();
    assertThat(
            new Contact.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName(" ").build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNotNull();
  }

  @Test
  void testEmptyTransferDataBecomesNull() {
    Contact withNull = new Contact.Builder().setTransferData(null).build();
    Contact withEmpty = withNull.asBuilder().setTransferData(ContactTransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    assertThat(withEmpty.transferData).isNull();
  }

  @Test
  void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    assertAboutContacts()
        .that(new Contact.Builder().build())
        .hasExactlyStatusValues(StatusValue.OK);
    // If there are other status values, OK should be suppressed.
    assertAboutContacts()
        .that(
            new Contact.Builder().setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD)).build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutContacts()
        .that(
            new Contact.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @Test
  void testExpiredTransfer() {
    Contact afterTransfer =
        contact
            .asBuilder()
            .setTransferData(
                contact
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setPendingTransferExpirationTime(fakeClock.nowUtc().plusDays(1))
                    .setGainingRegistrarId("winner")
                    .build())
            .build()
            .cloneProjectedAtTime(fakeClock.nowUtc().plusDays(1));
    assertThat(afterTransfer.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(afterTransfer.getCurrentSponsorRegistrarId()).isEqualTo("winner");
    assertThat(afterTransfer.getLastTransferTime()).isEqualTo(fakeClock.nowUtc().plusDays(1));
  }

  @Test
  void testSetCreationTime_cantBeCalledTwice() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class, () -> contact.asBuilder().setCreationTime(END_OF_TIME));
    assertThat(thrown).hasMessageThat().contains("creationTime can only be set once");
  }

  @Test
  void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    contact.toHydratedString();
  }
}
