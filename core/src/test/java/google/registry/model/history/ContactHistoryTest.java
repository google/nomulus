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

package google.registry.model.history;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactHistory;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.ContactTransferData;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.Test;

/** Tests for {@link ContactHistory}. */
public class ContactHistoryTest extends EntityTestCase {

  public ContactHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  public void testPersistence() {
    saveRegistrar("registrar1");

    ContactResource contact =
        new ContactResource.Builder()
            .setRepoId("contact1")
            .setContactId("contactId")
            .setCreationClientId("registrar1")
            .setPersistedCurrentSponsorClientId("registrar1")
            .setTransferData(new ContactTransferData.Builder().build())
            .build();

    jpaTm().transact(() -> jpaTm().saveNew(contact));
    VKey<ContactResource> contactVKey = VKey.createSql(ContactResource.class, "contact1");
    ContactResource contactFromDb = jpaTm().transact(() -> jpaTm().load(contactVKey));
    ContactHistory contactHistory =
        new ContactHistory.Builder()
            .setType(HistoryEntry.Type.HOST_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("registrar1")
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
            });
  }

  private void assertContactHistoriesEqual(ContactHistory one, ContactHistory two) {
    // enough of the fields get changed during serialization that we can't depend on .equals()
    assertThat(one.getClientId()).isEqualTo(two.getClientId());
    assertThat(one.getContactRepoId()).isEqualTo(two.getContactRepoId());
    assertThat(one.getBySuperuser()).isEqualTo(two.getBySuperuser());
    assertThat(one.getRequestedByRegistrar()).isEqualTo(two.getRequestedByRegistrar());
    assertThat(one.getReason()).isEqualTo(two.getReason());
    assertThat(one.getTrid()).isEqualTo(two.getTrid());
    assertThat(one.getType()).isEqualTo(two.getType());
    assertThat(one.getContactBase().getContactId()).isEqualTo(two.getContactBase().getContactId());
  }
}
