// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.flows.domain.DomainTransferFlowTestCase.persistWithPendingTransfer;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.getOnlyPollMessage;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainResourceSubject.assertAboutDomains;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainDeleteFlow.DomainToDeleteHasHostsException;
import google.registry.flows.domain.DomainFlowUtils.BadCommandForRegistryPhaseException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.TestExtraLogicManager;
import google.registry.model.domain.TestExtraLogicManager.TestExtraLogicManagerSuccessException;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.ProtocolDefinition.ServiceExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.HostResource;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainDeleteFlow}. */
public class DomainDeleteFlowTest extends ResourceFlowTestCase<DomainDeleteFlow, DomainResource> {

  private DomainResource domain;
  private HistoryEntry earlierHistoryEntry;

  private static final DateTime TIME_BEFORE_FLOW = DateTime.parse("2000-06-06T22:00:00.0Z");
  private static final DateTime A_MONTH_AGO = TIME_BEFORE_FLOW.minusMonths(1);
  private static final DateTime A_MONTH_FROM_NOW = TIME_BEFORE_FLOW.plusMonths(1);

  private static final ImmutableMap<String, String> FEE_06_MAP =
      ImmutableMap.of("FEE_VERSION", "0.6", "FEE_NS", "fee");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      ImmutableMap.of("FEE_VERSION", "0.11", "FEE_NS", "fee11");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      ImmutableMap.of("FEE_VERSION", "0.12", "FEE_NS", "fee12");

  public DomainDeleteFlowTest() {
    setEppInput("domain_delete.xml");
    clock.setTo(TIME_BEFORE_FLOW);
  }

  @Before
  public void initDomainTest() throws Exception {
    createTlds("tld", "flags");
    // For flags extension tests.
    RegistryExtraFlowLogicProxy.setOverride("flags", TestExtraLogicManager.class);
  }

  private void setupSuccessfulTest() throws Exception {
    createReferencedEntities(A_MONTH_FROM_NOW);
    BillingEvent.Recurring autorenewBillingEvent = persistResource(
        createAutorenewBillingEvent("TheRegistrar").build());
    PollMessage.Autorenew autorenewPollMessage = persistResource(
        createAutorenewPollMessage("TheRegistrar").build());
    domain = persistResource(domain.asBuilder()
        .setAutorenewBillingEvent(Key.create(autorenewBillingEvent))
        .setAutorenewPollMessage(Key.create(autorenewPollMessage))
        .build());
    assertTransactionalFlow(true);
  }

  private void createReferencedEntities(DateTime expirationTime) throws Exception {
    // Persist a linked contact.
    ContactResource contact = persistActiveContact("sh8013");
    domain = newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .setCreationTimeForTest(TIME_BEFORE_FLOW)
        .setRegistrant(Key.create(contact))
        .setRegistrationExpirationTime(expirationTime)
        .build();
    earlierHistoryEntry = persistResource(
        new HistoryEntry.Builder()
            .setType(HistoryEntry.Type.DOMAIN_CREATE)
            .setParent(domain)
            .build());
  }

  private void setupGracePeriods(GracePeriod... gracePeriods) throws Exception {
    domain = persistResource(
        domain.asBuilder().setGracePeriods(ImmutableSet.copyOf(gracePeriods)).build());
  }

  private void setupAutorenewGracePeriod() throws Exception {
    createReferencedEntities(A_MONTH_AGO.plusYears(1));
    BillingEvent.Recurring autorenewBillingEvent = persistResource(
        createAutorenewBillingEvent("TheRegistrar")
            .setEventTime(A_MONTH_AGO)
            .build());
    PollMessage.Autorenew autorenewPollMessage = persistResource(
        createAutorenewPollMessage("TheRegistrar")
            .setEventTime(A_MONTH_AGO)
            .build());
    domain = persistResource(
        domain.asBuilder()
            .setGracePeriods(ImmutableSet.of(GracePeriod.createForRecurring(
                GracePeriodStatus.AUTO_RENEW,
                A_MONTH_AGO.plusDays(45),
                "TheRegistrar",
                Key.create(autorenewBillingEvent))))
            .setAutorenewBillingEvent(Key.create(autorenewBillingEvent))
            .setAutorenewPollMessage(Key.create(autorenewPollMessage))
            .build());
    assertTransactionalFlow(true);
  }

  private void assertAutorenewClosedAndCancellationCreatedFor(
      BillingEvent.OneTime graceBillingEvent,
      HistoryEntry historyEntryDomainDelete) throws Exception {
    DateTime eventTime = clock.nowUtc();
    assertBillingEvents(
        createAutorenewBillingEvent("TheRegistrar")
            .setRecurrenceEndTime(eventTime)
            .build(),
        graceBillingEvent,
        new BillingEvent.Cancellation.Builder()
            .setReason(graceBillingEvent.getReason())
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(eventTime)
            .setBillingTime(TIME_BEFORE_FLOW.plusDays(1))
            .setOneTimeEventKey(Key.create(graceBillingEvent))
            .setParent(historyEntryDomainDelete)
            .build());
  }

  private void assertOnlyBillingEventIsClosedAutorenew(String clientId) throws Exception {
    // There should be no billing events (even timed to when the transfer would have expired) except
    // for the now closed autorenew one.
    assertBillingEvents(
        createAutorenewBillingEvent(clientId).setRecurrenceEndTime(clock.nowUtc()).build());
  }

  private BillingEvent.OneTime createBillingEvent(Reason reason, Money cost) {
    return new BillingEvent.OneTime.Builder()
        .setReason(reason)
        .setTargetId("example.tld")
        .setClientId("TheRegistrar")
        .setCost(cost)
        .setPeriodYears(2)
        .setEventTime(TIME_BEFORE_FLOW.minusDays(4))
        .setBillingTime(TIME_BEFORE_FLOW.plusDays(1))
        .setParent(earlierHistoryEntry)
        .build();
  }

  private BillingEvent.Recurring.Builder createAutorenewBillingEvent(String clientId) {
    return new BillingEvent.Recurring.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId("example.tld")
        .setClientId(clientId)
        .setEventTime(A_MONTH_FROM_NOW)
        .setRecurrenceEndTime(END_OF_TIME)
        .setParent(earlierHistoryEntry);
  }

  private PollMessage.Autorenew.Builder createAutorenewPollMessage(String clientId) {
    return new PollMessage.Autorenew.Builder()
        .setTargetId("example.tld")
        .setClientId(clientId)
        .setEventTime(A_MONTH_FROM_NOW)
        .setAutorenewEndTime(END_OF_TIME)
        .setParent(earlierHistoryEntry);
  }

  @Test
  public void testDryRun() throws Exception {
    setupSuccessfulTest();
    setupGracePeriods(GracePeriod.create(
        GracePeriodStatus.ADD, TIME_BEFORE_FLOW.plusDays(1), "foo", null));
    dryRunFlowAssertResponse(readFile("domain_delete_response.xml"));
  }

  @Test
  public void testDryRun_noGracePeriods() throws Exception {
    setupSuccessfulTest();
    dryRunFlowAssertResponse(readFile("domain_delete_response_pending.xml"));
  }

  private void doImmediateDeleteTest(GracePeriodStatus gracePeriodStatus, String responseFilename)
      throws Exception {
    doImmediateDeleteTest(gracePeriodStatus, responseFilename, ImmutableMap.<String, String>of());
  }

  private void doImmediateDeleteTest(
      GracePeriodStatus gracePeriodStatus,
      String responseFilename,
      Map<String, String> substitutions) throws Exception {
    // Persist the billing event so it can be retrieved for cancellation generation and checking.
    setupSuccessfulTest();
    BillingEvent.OneTime graceBillingEvent =
        persistResource(createBillingEvent(Reason.CREATE, Money.of(USD, 123)));
    setupGracePeriods(GracePeriod.forBillingEvent(gracePeriodStatus, graceBillingEvent));
    // We should see exactly one poll message, which is for the autorenew 1 month in the future.
    assertPollMessages(createAutorenewPollMessage("TheRegistrar").build());
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile(responseFilename, substitutions));
    // Check that the domain is fully deleted.
    assertThat(reloadResourceByForeignKey()).isNull();
    // The add grace period is for a billable action, so it should trigger a cancellation.
    assertAutorenewClosedAndCancellationCreatedFor(
        graceBillingEvent,
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_DELETE));
    assertDnsTasksEnqueued("example.tld");
    // There should be no poll messages. The previous autorenew poll message should now be deleted.
    assertThat(getPollMessages("TheRegistrar", A_MONTH_FROM_NOW)).isEmpty();
  }

  @Test
  public void testSuccess_addGracePeriodResultsInImmediateDelete() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    doImmediateDeleteTest(GracePeriodStatus.ADD, "domain_delete_response.xml");
  }

  @Test
  public void testSuccess_addGracePeriodCredit_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doImmediateDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_06_MAP);
  }

  @Test
  public void testSuccess_addGracePeriodCredit_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doImmediateDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_11_MAP);
  }

  @Test
  public void testSuccess_addGracePeriodCredit_v12() throws Exception {
    doImmediateDeleteTest(GracePeriodStatus.ADD, "domain_delete_response_fee.xml", FEE_12_MAP);
  }

  @Test
  public void testSuccess_sunrushAddGracePeriodResultsInImmediateDelete() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    doImmediateDeleteTest(GracePeriodStatus.SUNRUSH_ADD, "domain_delete_response.xml");
  }

  private void doSuccessfulTest_noAddGracePeriod(String responseFilename) throws Exception {
    doSuccessfulTest_noAddGracePeriod(responseFilename, ImmutableMap.<String, String>of());
  }

  private void doSuccessfulTest_noAddGracePeriod(
      String responseFilename, Map<String, String> substitutions) throws Exception {
    // Persist the billing event so it can be retrieved for cancellation generation and checking.
    setupSuccessfulTest();
    BillingEvent.OneTime renewBillingEvent =
        persistResource(createBillingEvent(Reason.RENEW, Money.of(USD, 456)));
    setupGracePeriods(
        GracePeriod.forBillingEvent(GracePeriodStatus.RENEW, renewBillingEvent),
        // This grace period has no associated billing event, so it won't cause a cancellation.
        GracePeriod.create(GracePeriodStatus.TRANSFER, TIME_BEFORE_FLOW.plusDays(1), "foo", null));
    // We should see exactly one poll message, which is for the autorenew 1 month in the future.
    assertPollMessages(createAutorenewPollMessage("TheRegistrar").build());
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile(responseFilename, substitutions));
    DomainResource resource = reloadResourceByForeignKey();
    // Check that the domain is in the pending delete state.
    assertAboutDomains().that(resource)
        .hasStatusValue(StatusValue.PENDING_DELETE).and()
        .hasDeletionTime(clock.nowUtc().plus(Registry.get("tld").getRedemptionGracePeriodLength())
            .plus(Registry.get("tld").getPendingDeleteLength())).and()
        .hasDeletePollMessage().and()
        .hasExactlyStatusValues(StatusValue.INACTIVE, StatusValue.PENDING_DELETE).and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE,
            HistoryEntry.Type.DOMAIN_DELETE);
    // All existing grace periods that were for billable actions should cause cancellations.
    assertAutorenewClosedAndCancellationCreatedFor(
        renewBillingEvent,
        getOnlyHistoryEntryOfType(resource, HistoryEntry.Type.DOMAIN_DELETE));
    // All existing grace periods should be gone, and a new REDEMPTION one should be added.
    assertThat(resource.getGracePeriods()).containsExactly(
        GracePeriod.create(
            GracePeriodStatus.REDEMPTION,
            clock.nowUtc().plus(Registry.get("tld").getRedemptionGracePeriodLength()),
            "TheRegistrar",
            null));
    // There should be a future poll message at the deletion time. The previous autorenew poll
    // message should now be deleted.
    DateTime deletionTime = resource.getDeletionTime();
    assertThat(getPollMessages("TheRegistrar", deletionTime.minusMinutes(1)))
        .isEmpty();
    assertThat(getPollMessages("TheRegistrar", deletionTime)).hasSize(1);
    assertThat(resource.getDeletePollMessage())
        .isEqualTo(Key.create(getOnlyPollMessage("TheRegistrar")));
  }

  @Test
  public void testSuccess_noAddGracePeriodResultsInPendingDelete() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending.xml");
  }

  @Test
  public void testSuccess_renewGracePeriodCredit_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_06_MAP);
  }

  @Test
  public void testSuccess_renewGracePeriodCredit_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_11_MAP);
  }

  @Test
  public void testSuccess_renewGracePeriodCredit_v12() throws Exception {
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending_fee.xml", FEE_12_MAP);
  }

  @Test
  public void testSuccess_autorenewPollMessageIsNotDeleted() throws Exception {
    setupSuccessfulTest();
    // Modify the autorenew poll message so that it has unacked messages in the past. This should
    // prevent it from being deleted when the domain is deleted.
    persistResource(
        ofy().load().key(reloadResourceByForeignKey().getAutorenewPollMessage()).now().asBuilder()
            .setEventTime(A_MONTH_FROM_NOW.minusYears(3))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_pending.xml"));
    // There should now be two poll messages; one for the delete of the domain (in the future), and
    // another for the unacked autorenew messages.
    DateTime deletionTime = reloadResourceByForeignKey().getDeletionTime();
    assertThat(getPollMessages("TheRegistrar", deletionTime.minusMinutes(1)))
        .hasSize(1);
    assertThat(getPollMessages("TheRegistrar", deletionTime)).hasSize(2);
  }

  @Test
  public void testSuccess_nonDefaultRedemptionGracePeriod() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRedemptionGracePeriodLength(Duration.standardMinutes(7))
            .build());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending.xml");
  }

  @Test
  public void testSuccess_nonDefaultPendingDeleteLength() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setPendingDeleteLength(Duration.standardMinutes(8))
            .build());
    doSuccessfulTest_noAddGracePeriod("domain_delete_response_pending.xml");
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_06_MAP));
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_11_MAP));
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_v12() throws Exception {
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_12_MAP));
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_priceChanges_v06() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_11.getUri());
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(
                START_OF_TIME, Money.of(USD, 11), TIME_BEFORE_FLOW.minusDays(5), Money.of(USD, 20)))
            .build());
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_06_MAP));
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_priceChanges_v11() throws Exception {
    removeServiceExtensionUri(ServiceExtension.FEE_0_12.getUri());
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(
                START_OF_TIME, Money.of(USD, 11), TIME_BEFORE_FLOW.minusDays(5), Money.of(USD, 20)))
            .build());
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_11_MAP));
  }

  @Test
  public void testSuccess_autoRenewGracePeriod_priceChanges_v12() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(
                START_OF_TIME, Money.of(USD, 11), TIME_BEFORE_FLOW.minusDays(5), Money.of(USD, 20)))
            .build());
    setupAutorenewGracePeriod();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_autorenew_fee.xml", FEE_12_MAP));
  }

  @Test
  public void testSuccess_pendingTransfer() throws Exception {
    setClientIdForFlow("TheRegistrar");
    setupSuccessfulTest();
    // Modify the domain we are testing to include a pending transfer.
    TransferData oldTransferData =
        persistWithPendingTransfer(reloadResourceByForeignKey()).getTransferData();
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_pending.xml"));
    DomainResource domain = reloadResourceByForeignKey();
    // Check that the domain is in the pending delete state.
    // The PENDING_TRANSFER status should be gone.
    assertAboutDomains().that(domain)
        .hasExactlyStatusValues(StatusValue.INACTIVE, StatusValue.PENDING_DELETE).and()
        .hasDeletionTime(clock.nowUtc().plus(Registry.get("tld").getRedemptionGracePeriodLength())
            .plus(Registry.get("tld").getPendingDeleteLength())).and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE,
            HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST,
            HistoryEntry.Type.DOMAIN_DELETE);
    // All existing grace periods should be gone, and a new REDEMPTION one should be added.
    assertThat(domain.getGracePeriods()).containsExactly(
        GracePeriod.create(
            GracePeriodStatus.REDEMPTION,
            clock.nowUtc().plus(Registry.get("tld").getRedemptionGracePeriodLength()),
            "TheRegistrar",
            null));
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)))
        .isEmpty();
    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar.
    PollMessage gainingPollMessage = getOnlyPollMessage("NewRegistrar");
    assertThat(gainingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(
        Iterables.getOnlyElement(FluentIterable
            .from(gainingPollMessage.getResponseData())
            .filter(TransferResponse.class))
                .getTransferStatus())
                .isEqualTo(TransferStatus.SERVER_CANCELLED);
    PendingActionNotificationResponse panData = Iterables.getOnlyElement(FluentIterable
        .from(gainingPollMessage.getResponseData())
        .filter(PendingActionNotificationResponse.class));
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
    // There should be a future poll message to the losing registrar at the deletion time.
    DateTime deletionTime = domain.getDeletionTime();
    assertThat(getPollMessages("TheRegistrar", deletionTime.minusMinutes(1)))
        .isEmpty();
    assertThat(getPollMessages("TheRegistrar", deletionTime)).hasSize(1);
    assertOnlyBillingEventIsClosedAutorenew("TheRegistrar");
    // The keys written for server approve should be null, and the entities should all be deleted.
    assertThat(domain.getTransferData().getServerApproveEntities()).isEmpty();
    assertThat(domain.getTransferData().getServerApproveBillingEvent()).isNull();
    assertThat(domain.getTransferData().getServerApproveAutorenewEvent()).isNull();
    assertThat(domain.getTransferData().getServerApproveAutorenewPollMessage()).isNull();
    assertThat(domain.getTransferData().getPendingTransferExpirationTime())
        .isEqualTo(clock.nowUtc());
    assertThat(ofy().load().key(oldTransferData.getServerApproveBillingEvent()).now()).isNull();
    assertThat(ofy().load().key(oldTransferData.getServerApproveAutorenewEvent()).now()).isNull();
    assertThat(ofy().load().key(oldTransferData.getServerApproveAutorenewPollMessage()).now())
        .isNull();
    assertThat(oldTransferData.getServerApproveEntities()).isNotEmpty();  // Just a sanity check.
    assertThat(ofy().load()
        .keys(oldTransferData.getServerApproveEntities().toArray(new Key<?>[]{})))
            .isEmpty();
  }

  @Test
  public void testUnlinkingOfResources() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    setupSuccessfulTest();
    // Persist the billing event so it can be retrieved for cancellation generation and checking.
    BillingEvent.OneTime graceBillingEvent =
        persistResource(createBillingEvent(Reason.CREATE, Money.of(USD, 123)));
    // Use a grace period so that the delete is immediate, simplifying the assertions below.
    setupGracePeriods(GracePeriod.forBillingEvent(GracePeriodStatus.ADD, graceBillingEvent));
    // Add a nameserver.
    HostResource host = persistResource(newHostResource("ns1.example.tld"));
    persistResource(loadByForeignKey(
        DomainResource.class, getUniqueIdFromCommand(), clock.nowUtc())
        .asBuilder()
        .setNameservers(ImmutableSet.of(Key.create(host)))
        .build());
    // Persist another domain that's already been deleted and references this contact and host.
    persistResource(
        newDomainResource("example1.tld")
            .asBuilder()
            .setRegistrant(
                Key.create(loadByForeignKey(ContactResource.class, "sh8013", clock.nowUtc())))
            .setNameservers(ImmutableSet.of(Key.create(host)))
            .setDeletionTime(START_OF_TIME)
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response.xml"));
    assertDnsTasksEnqueued("example.tld");
    assertAutorenewClosedAndCancellationCreatedFor(
        graceBillingEvent,
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_DELETE));
  }

  @Test
  public void testSuccess_deletedSubordinateDomain() throws Exception {
    setupSuccessfulTest();
    persistResource(
        newHostResource("ns1." + getUniqueIdFromCommand()).asBuilder()
            .setSuperordinateDomain(Key.create(reloadResourceByForeignKey()))
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(readFile("domain_delete_response_pending.xml"));
    assertDnsTasksEnqueued("example.tld");
    assertOnlyBillingEventIsClosedAutorenew("TheRegistrar");
  }

  @Test
  public void testFailure_predelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    setupSuccessfulTest();
    thrown.expect(BadCommandForRegistryPhaseException.class);
    runFlow();
  }

  @Test
  public void testSuccess_superuserPredelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    setupSuccessfulTest();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, readFile("domain_delete_response_pending.xml"));
  }

  @Test
  public void testFailure_neverExisted() throws Exception {
    thrown.expect(
        ResourceDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    thrown.expect(
        ResourceDoesNotExistException.class,
        String.format("(%s)", getUniqueIdFromCommand()));
    runFlow();
  }

  @Test
  public void testFailure_hasSubordinateHosts() throws Exception {
    DomainResource domain = persistActiveDomain(getUniqueIdFromCommand());
    HostResource subordinateHost = persistResource(
        newHostResource("ns1." + getUniqueIdFromCommand()).asBuilder()
            .setSuperordinateDomain(Key.create(reloadResourceByForeignKey()))
            .build());
    domain = persistResource(domain.asBuilder()
        .addSubordinateHost(subordinateHost.getFullyQualifiedHostName())
        .build());
    thrown.expect(DomainToDeleteHasHostsException.class);
    runFlow();
  }

  @Test
  public void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveDomain(getUniqueIdFromCommand());
    thrown.expect(ResourceNotOwnedException.class);
    runFlow();
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    setupSuccessfulTest();
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.<String>of())
            .build());
    thrown.expect(NotAuthorizedForTldException.class);
    runFlow();
  }

  @Test
  public void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    setupSuccessfulTest();
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, readFile("domain_delete_response_pending.xml"));
  }

  @Test
  public void testFailure_clientDeleteProhibited() throws Exception {
    persistResource(newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
        .build());
    thrown.expect(ResourceStatusProhibitsOperationException.class);
    runFlow();
  }

  @Test
  public void testFailure_serverDeleteProhibited() throws Exception {
    persistResource(newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
        .build());
    thrown.expect(ResourceStatusProhibitsOperationException.class);
    runFlow();
  }

  @Test
  public void testFailure_pendingDelete() throws Exception {
    persistResource(newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .addStatusValue(StatusValue.PENDING_DELETE)
        .build());
    thrown.expect(ResourceStatusProhibitsOperationException.class);
    runFlow();
  }

  @Test
  public void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_delete_metadata.xml");
    setupSuccessfulTest();
    clock.advanceOneMilli();
    runFlow();
    assertAboutDomains().that(reloadResourceByForeignKey())
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE,
            HistoryEntry.Type.DOMAIN_DELETE);
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_DELETE))
        .hasType(HistoryEntry.Type.DOMAIN_DELETE).and()
        .hasMetadataReason("domain-delete-test").and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @Test
  public void testFailure_metadataNotFromTool() throws Exception {
    setEppInput("domain_delete_metadata.xml");
    persistResource(newDomainResource(getUniqueIdFromCommand()));
    thrown.expect(OnlyToolCanPassMetadataException.class);
    runFlow();
  }

  @Test
  public void testSuccess_flags() throws Exception {
    setEppInput("domain_delete_flags.xml");
    setupSuccessfulTest();
    thrown.expect(TestExtraLogicManagerSuccessException.class, "deleted");
    runFlow();
  }
}
