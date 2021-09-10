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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.newContactResourceWithRoid;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.googlecode.objectify.Key;
import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactBase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.Test;

/** Tests for {@link ContactHistory}. */
public class ContactHistoryTest extends EntityTestCase {

  ContactHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    saveRegistrar("TheRegistrar");

    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    jpaTm().transact(() -> jpaTm().insert(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = jpaTm().transact(() -> jpaTm().loadByKey(contactVKey));
    ContactHistory contactHistory = createContactHistory(contactFromDb);
    jpaTm().transact(() -> jpaTm().insert(contactHistory));
    jpaTm()
        .transact(
            () -> {
              ContactHistory fromDatabase = jpaTm().loadByKey(contactHistory.createVKey());
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(contactHistory.getParentVKey());
            });
  }

  @Test
  void testLegacyPersistence_nullContactBase() {
    saveRegistrar("TheRegistrar");

    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    jpaTm().transact(() -> jpaTm().insert(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = jpaTm().transact(() -> jpaTm().loadByKey(contactVKey));
    ContactHistory contactHistory =
        createContactHistory(contactFromDb).asBuilder().setContact(null).build();
    jpaTm().transact(() -> jpaTm().insert(contactHistory));

    jpaTm()
        .transact(
            () -> {
              ContactHistory fromDatabase = jpaTm().loadByKey(contactHistory.createVKey());
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getParentVKey()).isEqualTo(contactHistory.getParentVKey());
            });
  }

  @Test
  void testOfyPersistence() {
    saveRegistrar("TheRegistrar");

    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    tm().transact(() -> tm().insert(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = tm().transact(() -> tm().loadByKey(contactVKey));
    fakeClock.advanceOneMilli();
    ContactHistory contactHistory = createContactHistory(contactFromDb);
    tm().transact(() -> tm().insert(contactHistory));

    // retrieving a HistoryEntry or a ContactHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for ContactHistory objects will have
    // type HistoryEntry
    VKey<ContactHistory> contactHistoryVKey = contactHistory.createVKey();
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(contactHistory.asHistoryEntry()));
    ContactHistory hostHistoryFromDb = tm().transact(() -> tm().loadByKey(contactHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().loadByKey(historyEntryVKey));

    assertThat(hostHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  @Test
  void testBeforeSqlSave_afterContactPersisted() {
    saveRegistrar("TheRegistrar");
    ContactResource contactResource = newContactResource("contactId");
    ContactHistory contactHistory =
        new ContactHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setRegistrarId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setContactRepoId(contactResource.getRepoId())
            .build();
    jpaTm()
        .transact(
            () -> {
              jpaTm().put(contactResource);
              contactHistory.beforeSqlSaveOnReplay();
              jpaTm().put(contactHistory);
            });
    jpaTm()
        .transact(
            () ->
                assertAboutImmutableObjects()
                    .that(jpaTm().loadByEntity(contactResource))
                    .hasFieldsEqualTo(jpaTm().loadByEntity(contactHistory).getContactBase().get()));
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
        .setContactRepoId(contact.getRepoId())
        .build();
  }

  static void assertContactHistoriesEqual(ContactHistory one, ContactHistory two) {
    assertAboutImmutableObjects()
        .that(one)
        .isEqualExceptFields(two, "contactBase", "contactRepoId");
    assertAboutImmutableObjects()
        .that(one.getContactBase().orElse(null))
        .isEqualExceptFields(two.getContactBase().orElse(null), "repoId");
  }
}
