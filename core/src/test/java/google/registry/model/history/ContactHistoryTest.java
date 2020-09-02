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
import static google.registry.testing.DatastoreHelper.newContactResourceWithRoid;
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
    jpaTm().transact(() -> jpaTm().saveNew(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = jpaTm().transact(() -> jpaTm().load(contactVKey));
    ContactHistory contactHistory = createContactHistory(contactFromDb, contactVKey);
    contactHistory.id = null;
    jpaTm().transact(() -> jpaTm().saveNew(contactHistory));
    jpaTm()
        .transact(
            () -> {
              ContactHistory fromDatabase =
                  jpaTm().load(VKey.createSql(ContactHistory.class, contactHistory.getId()));
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getContactRepoId().getSqlKey())
                  .isEqualTo(contactHistory.getContactRepoId().getSqlKey());
            });
  }

  @Test
  void testLegacyPersistence_nullContactBase() {
    saveRegistrar("TheRegistrar");

    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    jpaTm().transact(() -> jpaTm().saveNew(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = jpaTm().transact(() -> jpaTm().load(contactVKey));
    ContactHistory contactHistory =
        createContactHistory(contactFromDb, contactVKey).asBuilder().setContactBase(null).build();
    contactHistory.id = null;
    jpaTm().transact(() -> jpaTm().saveNew(contactHistory));

    jpaTm()
        .transact(
            () -> {
              ContactHistory fromDatabase =
                  jpaTm().load(VKey.createSql(ContactHistory.class, contactHistory.getId()));
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getContactRepoId().getSqlKey())
                  .isEqualTo(contactHistory.getContactRepoId().getSqlKey());
            });
  }

  @Test
  void testOfyPersistence() {
    saveRegistrar("TheRegistrar");

    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    tm().transact(() -> tm().saveNew(contact));
    VKey<ContactResource> contactVKey = contact.createVKey();
    ContactResource contactFromDb = tm().transact(() -> tm().load(contactVKey));
    fakeClock.advanceOneMilli();
    ContactHistory contactHistory = createContactHistory(contactFromDb, contactVKey);
    tm().transact(() -> tm().saveNew(contactHistory));

    // retrieving a HistoryEntry or a ContactHistory with the same key should return the same object
    // note: due to the @EntitySubclass annotation. all Keys for ContactHistory objects will have
    // type HistoryEntry
    VKey<ContactHistory> contactHistoryVKey =
        VKey.createOfy(ContactHistory.class, Key.create(contactHistory));
    VKey<HistoryEntry> historyEntryVKey =
        VKey.createOfy(HistoryEntry.class, Key.create(contactHistory.asHistoryEntry()));
    ContactHistory hostHistoryFromDb = tm().transact(() -> tm().load(contactHistoryVKey));
    HistoryEntry historyEntryFromDb = tm().transact(() -> tm().load(historyEntryVKey));

    assertThat(hostHistoryFromDb).isEqualTo(historyEntryFromDb);
  }

  private ContactHistory createContactHistory(
      ContactBase contact, VKey<ContactResource> contactVKey) {
    return new ContactHistory.Builder()
        .setType(HistoryEntry.Type.HOST_CREATE)
        .setXmlBytes("<xml></xml>".getBytes(UTF_8))
        .setModificationTime(fakeClock.nowUtc())
        .setClientId("TheRegistrar")
        .setTrid(Trid.create("ABC-123", "server-trid"))
        .setBySuperuser(false)
        .setReason("reason")
        .setRequestedByRegistrar(true)
        .setContactBase(contact)
        .setContactRepoId(contactVKey)
        .build();
  }

  static void assertContactHistoriesEqual(ContactHistory one, ContactHistory two) {
    assertAboutImmutableObjects()
        .that(one)
        .isEqualExceptFields(two, "contactBase", "contactRepoId", "parent");
    assertAboutImmutableObjects()
        .that(one.getContactBase().orElse(null))
        .isEqualExceptFields(two.getContactBase().orElse(null), "repoId");
  }
}
