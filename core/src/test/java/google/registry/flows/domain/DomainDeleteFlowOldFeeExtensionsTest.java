// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_DELETE;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessage;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.tld.Tld;
import google.registry.testing.DatabaseHelper;
import java.util.Map;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainDeleteFlow} that use the old fee extensions (0.6, 0.11, 0.12). */
public class DomainDeleteFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainDeleteFlow> {

  private static final DateTime TIME_BEFORE_FLOW = DateTime.parse("2000-06-06T22:00:00.0Z");
  private static final DateTime A_MONTH_AGO = TIME_BEFORE_FLOW.minusMonths(1);
  private static final DateTime A_MONTH_FROM_NOW = TIME_BEFORE_FLOW.plusMonths(1);

  private static final ImmutableMap<String, String> FEE_06_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.6", "FEE_NS", "fee");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.11", "FEE_NS", "fee11");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE_NS", "fee12");

  private Domain domain;
  private DomainHistory earlierHistoryEntry;

  @BeforeEach
  void beforeEachomainDeleteFlowOldFeeExtensionsTest() {
    clock.setTo(TIME_BEFORE_FLOW);
    setEppInput("domain_delete.xml");
    createTld("tld");
  }

  @Test
  void testSuccess_renewGracePeriodCredit_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_06_MAP);
  }

  @Test
  void testSuccess_renewGracePeriodCredit_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_11_MAP);
  }

  @Test
  void testSuccess_renewGracePeriodCredit_v12() throws Exception {
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_12_MAP);
  }

  @Test
  void testSuccess_addGracePeriodCredit_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doAddGracePeriodDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_06_MAP);
  }

  @Test
  void testSuccess_addGracePeriodCredit_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doAddGracePeriodDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_11_MAP);
  }

  @Test
  void testSuccess_addGracePeriodCredit_v12() throws Exception {
    doAddGracePeriodDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_12_MAP);
  }

  @Test
  void testSuccess_autoRenewGracePeriod_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_autoRenewGracePeriod_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_11_MAP));
  }

  @Test
  void testSuccess_autoRenewGracePeriod_v12() throws Exception {
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_12_MAP));
  }

  @Test
  void testSuccess_autoRenewGracePeriod_priceChanges_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 11),
                    TIME_BEFORE_FLOW.minusDays(5),
                    Money.of(USD, 20)))
            .build());
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_autoRenewGracePeriod_priceChanges_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 11),
                    TIME_BEFORE_FLOW.minusDays(5),
                    Money.of(USD, 20)))
            .build());
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_11_MAP));
  }

  @Test
  void testSuccess_autoRenewGracePeriod_priceChanges_v12() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 11),
                    TIME_BEFORE_FLOW.minusDays(5),
                    Money.of(USD, 20)))
            .build());
    setUpAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_autorenew_fee.xml", FEE_12_MAP));
  }

  @Test
  void testSuccess_freeCreation_deletionDuringGracePeriod_v12() throws Exception {
    // Deletion during the add grace period should still work even if the credit is 0
    setUpSuccessfulTest();
    BillingEvent graceBillingEvent =
        persistResource(createBillingEvent(Reason.CREATE, Money.of(USD, 0)));
    setUpGracePeriods(
        GracePeriod.forBillingEvent(GracePeriodStatus.ADD, domain.getRepoId(), graceBillingEvent));
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile("domain_delete_response_fee_free_grace_v12.xml"));
  }

  private void createReferencedEntities(DateTime expirationTime) throws Exception {
    // Persist a linked contact.
    Contact contact = persistActiveContact("sh8013");
    domain =
        persistResource(
            DatabaseHelper.newDomain(getUniqueIdFromCommand())
                .asBuilder()
                .setCreationTimeForTest(TIME_BEFORE_FLOW)
                .setRegistrant(Optional.of(contact.createVKey()))
                .setRegistrationExpirationTime(expirationTime)
                .build());
    earlierHistoryEntry =
        persistResource(
            new DomainHistory.Builder()
                .setType(DOMAIN_CREATE)
                .setDomain(domain)
                .setModificationTime(clock.nowUtc())
                .setRegistrarId(domain.getCreationRegistrarId())
                .build());
  }

  private void setUpSuccessfulTest() throws Exception {
    createReferencedEntities(A_MONTH_FROM_NOW);
    BillingRecurrence autorenewBillingEvent =
        persistResource(createAutorenewBillingEvent("TheRegistrar").build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(createAutorenewPollMessage("TheRegistrar").build());

    domain =
        persistResource(
            domain
                .asBuilder()
                .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
                .setAutorenewPollMessage(autorenewPollMessage.createVKey())
                .build());

    assertMutatingFlow(true);
  }

  private void doSuccessfulTest_noAddGracePeriod(String responseFilename) throws Exception {
    doSuccessfulTest_noAddGracePeriod(responseFilename, ImmutableMap.of());
  }

  private void doSuccessfulTest_noAddGracePeriod(
      String responseFilename, Map<String, String> substitutions) throws Exception {
    // Persist the billing event so it can be retrieved for cancellation generation and checking.
    setUpSuccessfulTest();
    BillingEvent renewBillingEvent =
        persistResource(createBillingEvent(Reason.RENEW, Money.of(USD, 456)));
    setUpGracePeriods(
        GracePeriod.forBillingEvent(GracePeriodStatus.RENEW, domain.getRepoId(), renewBillingEvent),
        // This grace period has no associated billing event, so it won't cause a cancellation.
        GracePeriod.create(
            GracePeriodStatus.TRANSFER,
            domain.getRepoId(),
            TIME_BEFORE_FLOW.plusDays(1),
            "NewRegistrar",
            null));
    // We should see exactly one poll message, which is for the autorenew 1 month in the future.
    assertPollMessages(createAutorenewPollMessage("TheRegistrar").build());
    DateTime expectedExpirationTime = domain.getRegistrationExpirationTime().minusYears(2);
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile(responseFilename, substitutions));
    Domain resource = reloadResourceByForeignKey();
    // Check that the domain is in the pending delete state.
    assertAboutDomains()
        .that(resource)
        .hasStatusValue(StatusValue.PENDING_DELETE)
        .and()
        .hasDeletionTime(
            clock
                .nowUtc()
                .plus(Tld.get("tld").getRedemptionGracePeriodLength())
                .plus(Tld.get("tld").getPendingDeleteLength()))
        .and()
        .hasExactlyStatusValues(StatusValue.INACTIVE, StatusValue.PENDING_DELETE)
        .and()
        .hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_DELETE);
    // We leave the original expiration time unchanged; if the expiration time is before the
    // deletion time, that means once it passes the domain will experience a "phantom autorenew"
    // where the expirationTime advances and the grace period appears, but since the delete flow
    // closed the autorenew recurrences immediately, there are no other autorenew effects.
    assertAboutDomains().that(resource).hasRegistrationExpirationTime(expectedExpirationTime);
    assertLastHistoryContainsResource(resource);
    // All existing grace periods that were for billable actions should cause cancellations.
    assertAutorenewClosedAndCancellationCreatedFor(
        renewBillingEvent, getOnlyHistoryEntryOfType(resource, DOMAIN_DELETE, DomainHistory.class));
    // All existing grace periods should be gone, and a new REDEMPTION one should be added.
    assertThat(resource.getGracePeriods())
        .containsExactly(
            GracePeriod.create(
                GracePeriodStatus.REDEMPTION,
                domain.getRepoId(),
                clock.nowUtc().plus(Tld.get("tld").getRedemptionGracePeriodLength()),
                "TheRegistrar",
                null,
                resource.getGracePeriods().iterator().next().getGracePeriodId()));
    assertDeletionPollMessageFor(resource, "Domain deleted.");
  }

  private void assertDeletionPollMessageFor(Domain domain, String expectedMessage) {
    // There should be a future poll message at the deletion time. The previous autorenew poll
    // message should now be deleted.
    assertAboutDomains().that(domain).hasDeletePollMessage();
    DateTime deletionTime = domain.getDeletionTime();
    assertThat(getPollMessages("TheRegistrar", deletionTime.minusMinutes(1))).isEmpty();
    assertThat(getPollMessages("TheRegistrar", deletionTime)).hasSize(1);
    assertThat(domain.getDeletePollMessage())
        .isEqualTo(getOnlyPollMessage("TheRegistrar").createVKey());
    PollMessage.OneTime deletePollMessage = loadByKey(domain.getDeletePollMessage());
    assertThat(deletePollMessage.getMsg()).isEqualTo(expectedMessage);
  }

  private void setUpGracePeriods(GracePeriod... gracePeriods) {
    domain =
        persistResource(
            domain.asBuilder().setGracePeriods(ImmutableSet.copyOf(gracePeriods)).build());
  }

  private void assertAutorenewClosedAndCancellationCreatedFor(
      BillingEvent graceBillingEvent, DomainHistory historyEntryDomainDelete) {
    assertAutorenewClosedAndCancellationCreatedFor(
        graceBillingEvent, historyEntryDomainDelete, clock.nowUtc());
  }

  private void assertAutorenewClosedAndCancellationCreatedFor(
      BillingEvent graceBillingEvent, DomainHistory historyEntryDomainDelete, DateTime eventTime) {
    assertBillingEvents(
        createAutorenewBillingEvent("TheRegistrar").setRecurrenceEndTime(eventTime).build(),
        graceBillingEvent,
        new BillingCancellation.Builder()
            .setReason(graceBillingEvent.getReason())
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(eventTime)
            .setBillingTime(TIME_BEFORE_FLOW.plusDays(1))
            .setBillingEvent(graceBillingEvent.createVKey())
            .setDomainHistory(historyEntryDomainDelete)
            .build());
  }

  private BillingRecurrence.Builder createAutorenewBillingEvent(String registrarId) {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId("example.tld")
        .setRegistrarId(registrarId)
        .setEventTime(A_MONTH_FROM_NOW)
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistory(earlierHistoryEntry);
  }

  private PollMessage.Autorenew.Builder createAutorenewPollMessage(String registrarId) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId("example.tld")
        .setRegistrarId(registrarId)
        .setEventTime(A_MONTH_FROM_NOW)
        .setAutorenewEndTime(END_OF_TIME)
        .setHistoryEntry(earlierHistoryEntry);
  }

  private BillingEvent createBillingEvent(Reason reason, Money cost) {
    return new BillingEvent.Builder()
        .setReason(reason)
        .setTargetId("example.tld")
        .setRegistrarId("TheRegistrar")
        .setCost(cost)
        .setPeriodYears(2)
        .setEventTime(TIME_BEFORE_FLOW.minusDays(4))
        .setBillingTime(TIME_BEFORE_FLOW.plusDays(1))
        .setDomainHistory(earlierHistoryEntry)
        .build();
  }

  private void doAddGracePeriodDeleteTest(
      GracePeriodStatus gracePeriodStatus, String responseFilename) throws Exception {
    doAddGracePeriodDeleteTest(gracePeriodStatus, responseFilename, ImmutableMap.of());
  }

  private void doAddGracePeriodDeleteTest(
      GracePeriodStatus gracePeriodStatus,
      String responseFilename,
      Map<String, String> substitutions)
      throws Exception {
    // Persist the billing event so it can be retrieved for cancellation generation and checking.
    setUpSuccessfulTest();
    BillingEvent graceBillingEvent =
        persistResource(createBillingEvent(Reason.CREATE, Money.of(USD, 123)));
    setUpGracePeriods(
        GracePeriod.forBillingEvent(gracePeriodStatus, domain.getRepoId(), graceBillingEvent));
    // We should see exactly one poll message, which is for the autorenew 1 month in the future.
    assertPollMessages(createAutorenewPollMessage("TheRegistrar").build());
    clock.advanceOneMilli();
    runFlowAssertResponse(loadFile(responseFilename, substitutions));
    // Check that the domain is fully deleted.
    assertThat(reloadResourceByForeignKey()).isNull();
    // The add grace period is for a billable action, so it should trigger a cancellation.
    assertAutorenewClosedAndCancellationCreatedFor(
        graceBillingEvent, getOnlyHistoryEntryOfType(domain, DOMAIN_DELETE, DomainHistory.class));
    assertDomainDnsRequests("example.tld");
    // There should be no poll messages. The previous autorenew poll message should now be deleted.
    assertThat(getPollMessages("TheRegistrar")).isEmpty();
  }

  private void setUpAutorenewGracePeriod() throws Exception {
    createReferencedEntities(A_MONTH_AGO.plusYears(1));
    BillingRecurrence autorenewBillingEvent =
        persistResource(
            createAutorenewBillingEvent("TheRegistrar").setEventTime(A_MONTH_AGO).build());
    PollMessage.Autorenew autorenewPollMessage =
        persistResource(
            createAutorenewPollMessage("TheRegistrar").setEventTime(A_MONTH_AGO).build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setGracePeriods(
                    ImmutableSet.of(
                        GracePeriod.createForRecurrence(
                            GracePeriodStatus.AUTO_RENEW,
                            domain.getRepoId(),
                            A_MONTH_AGO.plusDays(45),
                            "TheRegistrar",
                            autorenewBillingEvent.createVKey())))
                .setAutorenewBillingEvent(autorenewBillingEvent.createVKey())
                .setAutorenewPollMessage(autorenewPollMessage.createVKey())
                .build());
    assertMutatingFlow(true);
  }
}
