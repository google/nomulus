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
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static java.nio.charset.StandardCharsets.UTF_8;

import google.registry.model.EntityTestCase;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PollMessage}. */
public class PollMessageTest extends EntityTestCase {

  HistoryEntry historyEntry;
  PollMessage.OneTime oneTime;
  PollMessage.Autorenew autoRenew;

  public PollMessageTest() {
    super(true);
  }

  @BeforeEach
  public void setUp() {
    createTld("foobar");
    historyEntry =
        persistResource(
            new HistoryEntry.Builder()
                .setParent(persistActiveDomain("foo.foobar"))
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setPeriod(Period.create(1, Period.Unit.YEARS))
                .setXmlBytes("<xml></xml>".getBytes(UTF_8))
                .setModificationTime(fakeClock.nowUtc())
                .setClientId("foo")
                .setTrid(Trid.create("ABC-123", "server-trid"))
                .setBySuperuser(false)
                .setReason("reason")
                .setRequestedByRegistrar(false)
                .build());
    oneTime =
        new PollMessage.OneTime.Builder()
            .setClientId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setParent(historyEntry)
            .build();
    autoRenew =
        new PollMessage.Autorenew.Builder()
            .setClientId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setParent(historyEntry)
            .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
            .setTargetId("foobar.foo")
            .build();
    // TODO(shicong): Remove these two lines after we use symmetric vkey and change the cloud sql
    //  schema
    oneTime.id = null;
    autoRenew.id = null;
  }

  @Test
  void testCloudSqlPersistenceOneTime() {
    saveRegistrar("TheRegistrar");
    jpaTm().transact(() -> jpaTm().saveNew(oneTime));
    PollMessage.OneTime persisted =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.OneTime.class, oneTime.id)));
    persisted.parent = oneTime.parent;
    assertThat(persisted).isEqualTo(oneTime);
  }

  @Test
  void testCloudSqlPersistenceAutorenew() {
    saveRegistrar("TheRegistrar");
    jpaTm().transact(() -> jpaTm().saveNew(autoRenew));
    PollMessage.Autorenew persisted =
        jpaTm()
            .transact(
                () -> jpaTm().load(VKey.createSql(PollMessage.Autorenew.class, autoRenew.id)));
    persisted.parent = autoRenew.parent;
    assertThat(persisted).isEqualTo(autoRenew);
  }

  @Test
  void testCloudSqlSupportForPolymorphicVKey() {
    saveRegistrar("TheRegistrar");

    jpaTm().transact(() -> jpaTm().saveNew(oneTime));
    PollMessage persistedOneTime =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.class, oneTime.getId())));
    assertThat(persistedOneTime).isInstanceOf(PollMessage.OneTime.class);
    persistedOneTime.parent = oneTime.parent;
    assertThat(persistedOneTime).isEqualTo(oneTime);

    jpaTm().transact(() -> jpaTm().saveNew(autoRenew));
    PollMessage persistedAutoRenew =
        jpaTm().transact(() -> jpaTm().load(VKey.createSql(PollMessage.class, autoRenew.getId())));
    assertThat(persistedAutoRenew).isInstanceOf(PollMessage.Autorenew.class);
    persistedAutoRenew.parent = oneTime.parent;
    assertThat(persistedAutoRenew).isEqualTo(autoRenew);
  }

  @Test
  public void testPersistenceOneTime() {
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
  public void testPersistenceAutorenew() {
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
  public void testIndexingAutorenew() throws Exception {
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
