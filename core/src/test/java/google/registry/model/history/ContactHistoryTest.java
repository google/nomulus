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
import static google.registry.model.history.DomainHistoryTest.assertHistoriesEqual;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.newContactResourceWithRoid;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
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
    ContactHistory contactHistory =
        new ContactHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setContactBase(contactFromDb)
            .setContactRepoId(contactVKey)
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(contactHistory));
    jpaTm()
        .transact(
            () -> {
              ContactHistory fromDatabase = jpaTm().load(VKey.createSql(ContactHistory.class, 1L));
              assertContactHistoriesEqual(fromDatabase, contactHistory);
              assertThat(fromDatabase.getContactRepoId().getSqlKey())
                  .isEqualTo(contactHistory.getContactRepoId().getSqlKey());
            });
  }

  static void assertContactHistoriesEqual(ContactHistory one, ContactHistory two) {
    assertAboutImmutableObjects().that(one).isEqualExceptFields(two);
  }
}
