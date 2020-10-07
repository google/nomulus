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

package google.registry.model.poll;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PollMessage}. */
public class PollMessageTest extends EntityTestCase {

  private DomainBase domain;
  private HistoryEntry historyEntry;
  private PollMessage.OneTime oneTime;
  private PollMessage.Autorenew autoRenew;

  PollMessageTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    createTld("foobar");
    ContactResource contact = persistActiveContact("contact1234");
    domain = persistResource(newDomainBase("foo.foobar", contact));
    historyEntry =
        persistResource(
            new HistoryEntry.Builder()
                .setParent(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setPeriod(Period.create(1, Period.Unit.YEARS))
                .setXmlBytes("<xml></xml>".getBytes(UTF_8))
                .setModificationTime(fakeClock.nowUtc())
                .setClientId("TheRegistrar")
                .setTrid(Trid.create("ABC-123", "server-trid"))
                .setBySuperuser(false)
                .setReason("reason")
                .setRequestedByRegistrar(false)
                .build());
    oneTime =
        new PollMessage.OneTime.Builder()
            .setId(100L)
            .setClientId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setParent(historyEntry)
            .build();
    autoRenew =
        new PollMessage.Autorenew.Builder()
            .setId(200L)
            .setClientId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setParent(historyEntry)
            .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
            .setTargetId("foobar.foo")
            .build();
    jpaTm()
        .transact(
            () -> {
              saveRegistrar("TheRegistrar");
              jpaTm().insert(contact);
              jpaTm().insert(domain);
              jpaTm().insert(historyEntry.toChildHistoryEntity());
            });
  }

  @Test
  void testCloudSqlPersistenceOneTime() {
    jpaTm().transact(() -> jpaTm().insert(oneTime));
    PollMessage.OneTime persisted =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.OneTime.class, oneTime.id)));
    assertThat(persisted).isEqualTo(oneTime);
  }

  @Test
  void testCloudSqlPersistenceAutorenew() {
    jpaTm().transact(() -> jpaTm().insert(autoRenew));
    PollMessage.Autorenew persisted =
        jpaTm()
            .transact(
                () -> jpaTm().load(VKey.createSql(PollMessage.Autorenew.class, autoRenew.id)));
    assertThat(persisted).isEqualTo(autoRenew);
  }

  @Test
  void testCloudSqlSupportForPolymorphicVKey() {
    jpaTm().transact(() -> jpaTm().insert(oneTime));
    PollMessage persistedOneTime =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.class, oneTime.getId())));
    assertThat(persistedOneTime).isInstanceOf(PollMessage.OneTime.class);
    assertThat(persistedOneTime).isEqualTo(oneTime);

    jpaTm().transact(() -> jpaTm().insert(autoRenew));
    PollMessage persistedAutoRenew =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.class, autoRenew.getId())));
    assertThat(persistedAutoRenew).isInstanceOf(PollMessage.Autorenew.class);
    assertThat(persistedAutoRenew).isEqualTo(autoRenew);
  }

  @Test
  void testPersistenceOneTime() {
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setClientId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .build());
    assertThat(ofy().load().entity(pollMessage).now()).isEqualTo(pollMessage);
  }

  @Test
  void testPersistenceAutorenew() {
    PollMessage.Autorenew pollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setClientId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
                .setTargetId("foobar.foo")
                .build());
    assertThat(ofy().load().entity(pollMessage).now()).isEqualTo(pollMessage);
  }

  @Test
  void testIndexingAutorenew() throws Exception {
    PollMessage.Autorenew pollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setClientId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setParent(historyEntry)
                .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
                .setTargetId("foobar.foo")
                .build());
    verifyIndexing(pollMessage);
  }
}
