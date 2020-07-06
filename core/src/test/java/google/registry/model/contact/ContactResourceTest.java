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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.EppResourceTestUtils.assertEqualsIgnoreLastUpdateTime;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.assertThrowForeignKeyViolation;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.model.EntityTestCase;
import google.registry.model.billing.BillingEvent;
import google.registry.model.contact.Disclose.PostalInfoChoice;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.PresenceMarker;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.transfer.ContactTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactResource}. */
public class ContactResourceTest extends EntityTestCase {
  private ContactResource originalContact;
  private ContactResource contactResource;

  ContactResourceTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    createTld("foobar");
    originalContact =
        new ContactResource.Builder()
            .setContactId("contact_id")
            .setRepoId("1-FOOBAR")
            .setCreationClientId("registrar1")
            .setLastEppUpdateTime(fakeClock.nowUtc())
            .setLastEppUpdateClientId("registrar2")
            .setLastTransferTime(fakeClock.nowUtc())
            .setPersistedCurrentSponsorClientId("registrar3")
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
                    .setGainingClientId("gaining")
                    .setLosingClientId("losing")
                    .setPendingTransferExpirationTime(fakeClock.nowUtc())
                    .setServerApproveEntities(
                        ImmutableSet.of(VKey.create(BillingEvent.OneTime.class, 1)))
                    .setTransferRequestTime(fakeClock.nowUtc())
                    .setTransferStatus(TransferStatus.SERVER_APPROVED)
                    .setTransferRequestTrid(Trid.create("client-trid", "server-trid"))
                    .build())
            .build();
    // Set up a new persisted ContactResource entity.
    contactResource = persistResource(cloneAndSetAutoTimestamps(originalContact));
  }

  @Test
  void testCloudSqlPersistence_failWhenViolateForeignKeyConstraint() {
    assertThrowForeignKeyViolation(() -> jpaTm().transact(() -> jpaTm().saveNew(originalContact)));
  }

  @Test
  void testCloudSqlPersistence_succeed() {
    saveRegistrar("registrar1");
    saveRegistrar("registrar2");
    saveRegistrar("registrar3");
    saveRegistrar("gaining");
    saveRegistrar("losing");
    jpaTm().transact(() -> jpaTm().saveNew(originalContact));
    ContactResource persisted =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .load(VKey.createSql(ContactResource.class, originalContact.getRepoId())));

    ContactResource fixed =
        originalContact
            .asBuilder()
            .setCreationTime(persisted.getCreationTime())
            .setTransferData(
                originalContact
                    .getTransferData()
                    .asBuilder()
                    .setServerApproveEntities(null)
                    .build())
            .build();
    assertEqualsIgnoreLastUpdateTime(persisted, fixed);
  }

  @Test
  void testPersistence() {
    assertThat(
            loadByForeignKey(
                ContactResource.class, contactResource.getForeignKey(), fakeClock.nowUtc()))
        .hasValue(contactResource);
  }

  @Test
  void testIndexing() throws Exception {
    verifyIndexing(contactResource, "deletionTime", "currentSponsorClientId", "searchName");
  }

  @Test
  void testEmptyStringsBecomeNull() {
    assertThat(new ContactResource.Builder().setContactId(null).build().getContactId()).isNull();
    assertThat(new ContactResource.Builder().setContactId("").build().getContactId()).isNull();
    assertThat(new ContactResource.Builder().setContactId(" ").build().getContactId()).isNotNull();
    // Nested ImmutableObjects should also be fixed
    assertThat(
            new ContactResource.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName(null).build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNull();
    assertThat(
            new ContactResource.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName("").build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNull();
    assertThat(
            new ContactResource.Builder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder().setType(Type.INTERNATIONALIZED).setName(" ").build())
                .build()
                .getInternationalizedPostalInfo()
                .getName())
        .isNotNull();
  }

  @Test
  void testEmptyTransferDataBecomesNull() {
    ContactResource withNull = new ContactResource.Builder().setTransferData(null).build();
    ContactResource withEmpty =
        withNull.asBuilder().setTransferData(ContactTransferData.EMPTY).build();
    assertThat(withNull).isEqualTo(withEmpty);
    assertThat(withEmpty.transferData).isNull();
  }

  @Test
  void testImplicitStatusValues() {
    // OK is implicit if there's no other statuses.
    assertAboutContacts()
        .that(new ContactResource.Builder().build())
        .hasExactlyStatusValues(StatusValue.OK);
    // If there are other status values, OK should be suppressed.
    assertAboutContacts()
        .that(
            new ContactResource.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
    // When OK is suppressed, it should be removed even if it was originally there.
    assertAboutContacts()
        .that(
            new ContactResource.Builder()
                .setStatusValues(ImmutableSet.of(StatusValue.OK, StatusValue.CLIENT_HOLD))
                .build())
        .hasExactlyStatusValues(StatusValue.CLIENT_HOLD);
  }

  @Test
  void testExpiredTransfer() {
    ContactResource afterTransfer =
        contactResource
            .asBuilder()
            .setTransferData(
                contactResource
                    .getTransferData()
                    .asBuilder()
                    .setTransferStatus(TransferStatus.PENDING)
                    .setPendingTransferExpirationTime(fakeClock.nowUtc().plusDays(1))
                    .setGainingClientId("winner")
                    .build())
            .build()
            .cloneProjectedAtTime(fakeClock.nowUtc().plusDays(1));
    assertThat(afterTransfer.getTransferData().getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    assertThat(afterTransfer.getCurrentSponsorClientId()).isEqualTo("winner");
    assertThat(afterTransfer.getLastTransferTime()).isEqualTo(fakeClock.nowUtc().plusDays(1));
  }

  @Test
  void testSetCreationTime_cantBeCalledTwice() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> contactResource.asBuilder().setCreationTime(END_OF_TIME));
    assertThat(thrown).hasMessageThat().contains("creationTime can only be set once");
  }

  @Test
  void testToHydratedString_notCircular() {
    // If there are circular references, this will overflow the stack.
    contactResource.toHydratedString();
  }
}
