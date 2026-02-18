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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.Period;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse.HostPendingActionNotificationResponse;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.util.SerializeUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PollMessage}. */
public class PollMessageTest extends EntityTestCase {

  private HistoryEntry historyEntry;
  private PollMessage.OneTime oneTime;
  private PollMessage.Autorenew autoRenew;

  PollMessageTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @BeforeEach
  void setUp() {
    createTld("foobar");
    Domain domain = persistResource(DatabaseHelper.newDomain("foo.foobar"));
    historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setPeriod(Period.create(1, Period.Unit.YEARS))
                .setXmlBytes("<xml></xml>".getBytes(UTF_8))
                .setModificationTime(fakeClock.nowUtc())
                .setRegistrarId("TheRegistrar")
                .setTrid(Trid.create("ABC-123", "server-trid"))
                .setBySuperuser(false)
                .setReason("reason")
                .setRequestedByRegistrar(false)
                .build());
    oneTime =
        new PollMessage.OneTime.Builder()
            .setId(100L)
            .setRegistrarId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setHistoryEntry(historyEntry)
            .build();
    autoRenew =
        new PollMessage.Autorenew.Builder()
            .setId(200L)
            .setRegistrarId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setHistoryEntry(historyEntry)
            .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
            .setTargetId("foobar.foo")
            .build();
  }

  @Test
  void testCloudSqlSupportForPolymorphicVKey() {
    persistResource(oneTime);
    PollMessage persistedOneTime = loadByKey(VKey.create(PollMessage.class, oneTime.getId()));
    assertThat(persistedOneTime).isInstanceOf(PollMessage.OneTime.class);
    assertThat(persistedOneTime).isEqualTo(oneTime);

    persistResource(autoRenew);
    PollMessage persistedAutoRenew = loadByKey(VKey.create(PollMessage.class, autoRenew.getId()));
    assertThat(persistedAutoRenew).isInstanceOf(PollMessage.Autorenew.class);
    assertThat(persistedAutoRenew).isEqualTo(autoRenew);
  }

  @Test
  void testPersistenceOneTime() {
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .build());
    assertThat(tm().transact(() -> tm().loadByEntity(pollMessage))).isEqualTo(pollMessage);
  }

  @Test
  void testPersistenceOneTime_hostPendingActionNotification() {
    HostPendingActionNotificationResponse hostPendingActionNotificationResponse =
        HostPendingActionNotificationResponse.create(
            "test.example",
            true,
            Trid.create("ABC-123", "server-trid"),
            fakeClock.nowUtc().minusDays(5));

    PollMessage.OneTime pollMessage =
        new PollMessage.OneTime.Builder()
            .setRegistrarId("TheRegistrar")
            .setEventTime(fakeClock.nowUtc())
            .setMsg("Test poll message")
            .setHistoryEntry(historyEntry)
            .setResponseData(ImmutableList.of(hostPendingActionNotificationResponse))
            .build();
    persistResource(pollMessage);
    assertThat(tm().transact(() -> tm().loadByEntity(pollMessage).getMsg()))
        .isEqualTo(pollMessage.msg);
    assertThat(
            tm().transact(() -> tm().loadByEntity(pollMessage)).pendingActionNotificationResponse)
        .isEqualTo(hostPendingActionNotificationResponse);
  }

  @Test
  void testSerializableOneTime() {
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .build());
    PollMessage persisted = tm().transact(() -> tm().loadByEntity(pollMessage));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }

  @Test
  void testPersistenceAutorenew() {
    PollMessage.Autorenew pollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
                .setTargetId("foobar.foo")
                .build());
    assertThat(tm().transact(() -> tm().loadByEntity(pollMessage))).isEqualTo(pollMessage);
  }

  @Test
  void testSerializableAutorenew() {
    PollMessage.Autorenew pollMessage =
        persistResource(
            new PollMessage.Autorenew.Builder()
                .setRegistrarId("TheRegistrar")
                .setEventTime(fakeClock.nowUtc())
                .setMsg("Test poll message")
                .setHistoryEntry(historyEntry)
                .setAutorenewEndTime(fakeClock.nowUtc().plusDays(365))
                .setTargetId("foobar.foo")
                .build());
    PollMessage persisted = tm().transact(() -> tm().loadByEntity(pollMessage));
    assertThat(SerializeUtils.serializeDeserialize(persisted)).isEqualTo(persisted);
  }
}
