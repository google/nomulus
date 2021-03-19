// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent.FakesAndMocksModule;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.testing.InjectExtension;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DeleteExpiredDomainsAction}. */
class DeleteExpiredDomainsActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private final FakeClock clock = new FakeClock(DateTime.parse("2016-06-13T20:21:22Z"));
  private final FakeResponse response = new FakeResponse();
  private DeleteExpiredDomainsAction action;

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("tld");
    EppController eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(
                FakesAndMocksModule.create(clock, EppMetric.builderForRequest(clock)))
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
    DomainBase activeDomain = persistActiveDomain("foo.tld");
    clock.advanceOneMilli();

    // A non-autorenewing domain that is already pending delete and shouldn't be touched.
    DomainBase alreadyDeletedDomain =
        persistResource(
            newDomainBase("bar.tld")
                .asBuilder()
                .setAutorenewEndTime(Optional.of(clock.nowUtc().minusDays(10)))
                .setDeletionTime(clock.nowUtc().plusDays(17))
                .build());
    clock.advanceOneMilli();

    // A non-autorenewing domain that hasn't reached its expiration time and shouldn't be touched.
    DomainBase notYetExpiredDomain =
        persistResource(
            newDomainBase("baz.tld")
                .asBuilder()
                .setAutorenewEndTime(Optional.of(clock.nowUtc().plusDays(15)))
                .build());
    clock.advanceOneMilli();

    // A non-autorenewing domain that is past its expiration time and should be deleted.
    // (This is the only one that needs a full set of subsidiary resources, for the delete flow to
    // to operate on.)
    DomainBase pendingExpirationDomain = persistNonAutorenewingDomain("fizz.tld");

    assertThat(tm().loadByEntity(pendingExpirationDomain).getStatusValues())
        .doesNotContain(PENDING_DELETE);
    action.run();

    DomainBase reloadedActiveDomain = tm().loadByEntity(activeDomain);
    assertThat(reloadedActiveDomain).isEqualTo(activeDomain);
    assertThat(reloadedActiveDomain.getStatusValues()).doesNotContain(PENDING_DELETE);
    assertThat(tm().loadByEntity(alreadyDeletedDomain)).isEqualTo(alreadyDeletedDomain);
    assertThat(tm().loadByEntity(notYetExpiredDomain)).isEqualTo(notYetExpiredDomain);
    DomainBase reloadedExpiredDomain = tm().loadByEntity(pendingExpirationDomain);
    assertThat(reloadedExpiredDomain.getStatusValues()).contains(PENDING_DELETE);
    assertThat(reloadedExpiredDomain.getDeletionTime()).isEqualTo(clock.nowUtc().plusDays(35));
  }

  @Test
  void test_deletesThreeDomainsInOneRun() {
    DomainBase domain1 = persistNonAutorenewingDomain("ecck1.tld");
    DomainBase domain2 = persistNonAutorenewingDomain("veee2.tld");
    DomainBase domain3 = persistNonAutorenewingDomain("tarm3.tld");

    // action.run() executes a non-transactional query by design but makes this test flaky.
    // Executing a transaction here seems to force the test Datastore to become up to date.
    assertThat(tm().loadByEntity(domain3).getStatusValues()).doesNotContain(PENDING_DELETE);
    action.run();

    assertThat(tm().loadByEntity(domain1).getStatusValues()).contains(PENDING_DELETE);
    assertThat(tm().loadByEntity(domain2).getStatusValues()).contains(PENDING_DELETE);
    assertThat(tm().loadByEntity(domain3).getStatusValues()).contains(PENDING_DELETE);
  }

  private DomainBase persistNonAutorenewingDomain(String domainName) {
    DomainBase pendingExpirationDomain = persistActiveDomain(domainName);
    HistoryEntry createHistoryEntry =
        persistResource(
            new HistoryEntry.Builder()
                .setType(DOMAIN_CREATE)
                .setParent(pendingExpirationDomain)
                .setModificationTime(clock.nowUtc().minusMonths(9))
                .build());
    BillingEvent.Recurring autorenewBillingEvent =
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
    clock.advanceOneMilli();

    return pendingExpirationDomain;
  }

  private BillingEvent.Recurring.Builder createAutorenewBillingEvent(
      HistoryEntry createHistoryEntry) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId("fizz.tld")
        .setClientId("TheRegistrar")
        .setEventTime(clock.nowUtc().plusYears(1))
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(createHistoryEntry);
  }

  private PollMessage.Autorenew.Builder createAutorenewPollMessage(
      HistoryEntry createHistoryEntry) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId("fizz.tld")
        .setClientId("TheRegistrar")
        .setEventTime(clock.nowUtc().plusYears(1))
        .setAutorenewEndTime(END_OF_TIME)
        .setParent(createHistoryEntry);
  }
}
