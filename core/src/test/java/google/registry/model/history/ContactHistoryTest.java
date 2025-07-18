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

package google.registry.model.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newContactWithRoid;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactPhoneNumber;
import google.registry.model.contact.PostalInfo;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.Test;

/** Tests for {@link ContactHistory}. */
public class ContactHistoryTest extends EntityTestCase {

  ContactHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    Contact contact = newContactWithRoid("contactId", "contact1");
    persistResource(contact);
    Contact contactFromDb = loadByEntity(contact);
    ContactHistory contactHistory = createContactHistory(contactFromDb);
    persistResource(contactHistory);
    tm().transact(
            () -> {
              ContactHistory fromDatabase = tm().loadByKey(contactHistory.createVKey());
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getRepoId()).isEqualTo(contactHistory.getRepoId());
            });
  }

  @Test
  void testSerializable() {
    Contact contact = newContactWithRoid("contactId", "contact1");
    persistResource(contact);
    Contact contactFromDb = loadByEntity(contact);
    ContactHistory contactHistory = createContactHistory(contactFromDb);
    persistResource(contactHistory);
    ContactHistory fromDatabase = tm().transact(() -> tm().loadByKey(contactHistory.createVKey()));
    assertThat(SerializeUtils.serializeDeserialize(fromDatabase)).isEqualTo(fromDatabase);
  }

  @Test
  void testWipeOutPii_assertsAllPiiFieldsAreNull() {
    ContactHistory originalEntity =
        createContactHistory(
            new Contact.Builder()
                .setRepoId("1-FOOBAR")
                .setLocalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(PostalInfo.Type.LOCALIZED)
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
                        .setType(PostalInfo.Type.INTERNATIONALIZED)
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
                .setEmailAddress("test@example.com")
                .build());

    assertThat(originalEntity.getContactBase().get().getEmailAddress()).isNotNull();
    assertThat(originalEntity.getContactBase().get().getLocalizedPostalInfo()).isNotNull();
    assertThat(originalEntity.getContactBase().get().getInternationalizedPostalInfo()).isNotNull();
    assertThat(originalEntity.getContactBase().get().getVoiceNumber()).isNotNull();
    assertThat(originalEntity.getContactBase().get().getFaxNumber()).isNotNull();

    ContactHistory wipedEntity = originalEntity.asBuilder().wipeOutPii().build();

    assertThat(wipedEntity.getContactBase().get().getEmailAddress()).isNull();
    assertThat(wipedEntity.getContactBase().get().getLocalizedPostalInfo()).isNull();
    assertThat(wipedEntity.getContactBase().get().getInternationalizedPostalInfo()).isNull();
    assertThat(wipedEntity.getContactBase().get().getVoiceNumber()).isNull();
    assertThat(wipedEntity.getContactBase().get().getFaxNumber()).isNull();
  }

  private ContactHistory createContactHistory(ContactBase contact) {
    return new ContactHistory.Builder()
        .setType(HistoryEntry.Type.HOST_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setRegistrarId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setContact(contact)
        .build();
  }

  static void assertContactHistoriesEqual(ContactHistory one, ContactHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two, "resource");
    assertAboutImmutableObjects()
        .that(one.getContactBase().get())
        .isEqualExceptFields(two.getContactBase().get());
  }
}
