// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.batch;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.persistence.transaction.QueryComposer.Comparator;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DeleteExpiredDomainsAction}. */
class DeleteExpiredDomainsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2016-06-13T20:21:22Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final FakeResponse response = new FakeResponse();
  private DeleteExpiredDomainsAction action;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    EppController eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(FakesAndMocksModule.create(clock))
            .build()
            .startRequest()
            .eppController();
    action =
        new DeleteExpiredDomainsAction(
            eppController, "NewRegistrar", clock, new FakeLockHandler(true), response);
  }

  @Test
  void test_deletesOnlyExpiredDomain() {
    // A normal, active autorenewing domain that shouldn't be touched.
    Domain activeDomain = persistActiveDomain("foo.tld");

    // A non-autorenewing domain that is already pending delete and shouldn't be touched.
    Domain alreadyDeletedDomain =
        persistResource(
            DatabaseHelper.newDomain("bar.tld")
                .asBuilder()
                .setAutorenewEndTime(Optional.of(clock.nowUtc().minusDays(10)))
                .setDeletionTime(clock.nowUtc().plusDays(17))
                .build());

    // A non-autorenewing domain that hasn't reached its expiration time and shouldn't be touched.
    Domain notYetExpiredDomain =
        persistResource(
            DatabaseHelper.newDomain("baz.tld")
                .asBuilder()
                .setAutorenewEndTime(Optional.of(clock.nowUtc().plusDays(15)))
                .build());

    // A non-autorenewing domain that is past its expiration time and should be deleted.
    // (This is the only one that needs a full set of subsidiary resources, for the delete flow to
    //  operate on.)
    Domain pendingExpirationDomain = persistNonAutorenewingDomain("fizz.tld");

    assertThat(loadByEntity(pendingExpirationDomain).getStatusValues())
        .doesNotContain(PENDING_DELETE);
    // action.run() does not use any test helper that can advance the fake clock. We manually
    // advance the clock to emulate the actual behavior. This works because the action only has
    // one transaction.
    clock.advanceOneMilli();
    action.run();

    Domain reloadedActiveDomain = loadByEntity(activeDomain);
    assertThat(reloadedActiveDomain).isEqualTo(activeDomain);
    assertThat(reloadedActiveDomain.getStatusValues()).doesNotContain(PENDING_DELETE);
    assertThat(loadByEntity(alreadyDeletedDomain)).isEqualTo(alreadyDeletedDomain);
    assertThat(loadByEntity(notYetExpiredDomain)).isEqualTo(notYetExpiredDomain);
    Domain reloadedExpiredDomain = loadByEntity(pendingExpirationDomain);
    assertThat(reloadedExpiredDomain.getStatusValues()).contains(PENDING_DELETE);
    assertThat(reloadedExpiredDomain.getDeletionTime()).isEqualTo(clock.nowUtc().plusDays(35));
  }

  @Test
  void test_deletesThreeDomainsInOneRun() throws Exception {
    Domain domain1 = persistNonAutorenewingDomain("ecck1.tld");
    Domain domain2 = persistNonAutorenewingDomain("veee2.tld");
    Domain domain3 = persistNonAutorenewingDomain("tarm3.tld");

    // action.run() executes an ancestor-less query which is subject to eventual consistency (it
    // uses an index that is updated asynchronously). For a deterministic test outcome, we busy
    // wait here to give the data time to converge.
    int maxRetries = 5;
    while (true) {
      ImmutableSet<String> matchingDomains =
          tm().transact(
                  () ->
                      tm()
                          .createQueryComposer(Domain.class)
                          .where("autorenewEndTime", Comparator.LTE, clock.nowUtc())
                          .stream()
                          .map(Domain::getDomainName)
                          .collect(toImmutableSet()));
      if (matchingDomains.containsAll(ImmutableSet.of("ecck1.tld", "veee2.tld", "tarm3.tld"))) {
        break;
      }
      if (maxRetries-- <= 0) {
        throw new RuntimeException("Eventual consistency does not converge in time for this test.");
      }
      Thread.sleep(200);
    }

    // action.run does multiple write transactions. We cannot emulate the behavior by manually
    // advancing the clock. Auto-increment to avoid timestamp inversion.
    clock.setAutoIncrementByOneMilli();
    action.run();
    clock.disableAutoIncrement();

    assertThat(loadByEntity(domain1).getStatusValues()).contains(PENDING_DELETE);
    assertThat(loadByEntity(domain2).getStatusValues()).contains(PENDING_DELETE);
    assertThat(loadByEntity(domain3).getStatusValues()).contains(PENDING_DELETE);
  }

  private Domain persistNonAutorenewingDomain(String domainName) {
    Domain pendingExpirationDomain = persistActiveDomain(domainName);
    DomainHistory createHistoryEntry =
        persistResource(
            new DomainHistory.Builder()
                .setType(DOMAIN_CREATE)
                .setDomain(pendingExpirationDomain)
                .setModificationTime(clock.nowUtc().minusMonths(9))
                .setRegistrarId(pendingExpirationDomain.getCreationRegistrarId())
                .build());
    BillingRecurrence autorenewBillingEvent =
        persistResource(createAutorenewBillingEvent(createHistoryEntry).build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(createAutorenewPollMessage(createHistoryEntry).build());
    pendingExpirationDomain =
        persistResource(
            pendingExpirationDomain
                .asBuilder()
                .setAutorenewEndTime(Optional.of(clock.nowUtc().minusDays(10)))
                .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
                .setAutorenewPollMessage(autorenewPollMessage.createVKey())
                .build());

    return pendingExpirationDomain;
  }

  private BillingRecurrence.Builder createAutorenewBillingEvent(DomainHistory createHistoryEntry) {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId("fizz.tld")
        .setRegistrarId("TheRegistrar")
        .setEventTime(clock.nowUtc().plusYears(1))
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistory(createHistoryEntry);
  }

  private PollMessage.Autorenew.Builder createAutorenewPollMessage(
      HistoryEntry createHistoryEntry) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId("fizz.tld")
        .setRegistrarId("TheRegistrar")
        .setEventTime(clock.nowUtc().plusYears(1))
        .setAutorenewEndTime(END_OF_TIME)
        .setHistoryEntry(createHistoryEntry);
  }
}
