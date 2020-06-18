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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.newContactResourceWithRoid;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResourceWithRoid;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.Test;

public class DomainHistoryTest extends EntityTestCase {

  public DomainHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  public void testPersistence() {
    saveRegistrar("TheRegistrar");

    HostResource host = newHostResourceWithRoid("ns1.example.com", "host1");
    jpaTm().transact(() -> jpaTm().saveNew(host));
    ContactResource contact = newContactResourceWithRoid("contactId", "contact1");
    jpaTm().transact(() -> jpaTm().saveNew(contact));

    DomainBase domain =
        newDomainBase("example.tld", "domainRepoId", contact)
            .asBuilder()
            .setNameservers(host.createVKey())
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(domain));

    DomainHistory domainHistory =
        new DomainHistory.Builder()
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setXmlBytes("<xml></xml>".getBytes(UTF_8))
            .setModificationTime(fakeClock.nowUtc())
            .setClientId("TheRegistrar")
            .setTrid(Trid.create("ABC-123", "server-trid"))
            .setBySuperuser(false)
            .setReason("reason")
            .setRequestedByRegistrar(true)
            .setDomainContent(domain)
            .setDomainRepoId(domain.createVKey())
            .build();
    jpaTm().transact(() -> jpaTm().saveNew(domainHistory));

    jpaTm()
        .transact(
            () -> {
              DomainHistory fromDatabase =
                  jpaTm().load(VKey.createSql(DomainHistory.class, domainHistory.getId()));
              assertHistoriesEqual(fromDatabase, domainHistory);
              assertThat(fromDatabase.getDomainRepoId().getSqlKey())
                  .isEqualTo(domainHistory.getDomainRepoId().getSqlKey());
            });
  }

  static void assertHistoriesEqual(HistoryEntry one, HistoryEntry two) {
    // enough of the fields get changed during serialization that we can't depend on .equals()
    assertThat(one.getClientId()).isEqualTo(two.getClientId());
    assertThat(one.getBySuperuser()).isEqualTo(two.getBySuperuser());
    assertThat(one.getRequestedByRegistrar()).isEqualTo(two.getRequestedByRegistrar());
    assertThat(one.getReason()).isEqualTo(two.getReason());
    assertThat(one.getTrid()).isEqualTo(two.getTrid());
    assertThat(one.getType()).isEqualTo(two.getType());
  }
}
