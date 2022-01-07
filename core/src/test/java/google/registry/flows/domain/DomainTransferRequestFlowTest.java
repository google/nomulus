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

package google.registry.flows.domain;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
import static google.registry.model.tld.Registry.TldState.QUIET_PERIOD;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertBillingEventsEqual;
import static google.registry.testing.DatabaseHelper.assertPollMessagesEqual;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessage;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadByKeys;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.HostResourceSubject.assertAboutHosts;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import google.registry.batch.ResaveEntityAction;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ReadOnlyModeEppException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.exceptions.AlreadyPendingTransferException;
import google.registry.flows.exceptions.InvalidTransferPeriodValueException;
import google.registry.flows.exceptions.MissingTransferRequestAuthInfoException;
import google.registry.flows.exceptions.ObjectAlreadySponsoredException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.TransferPeriodMustBeOneYearException;
import google.registry.flows.exceptions.TransferPeriodZeroAndFeeTransferExtensionException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainTransferRequestFlow}. */
@DualDatabaseTest
class DomainTransferRequestFlowTest
    extends DomainTransferFlowTestCase<DomainTransferRequestFlow, DomainBase> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  private static final ImmutableMap<String, String> BASE_FEE_MAP =
      new ImmutableMap.Builder<String, String>()
          .put("DOMAIN", "example.tld")
          .put("YEARS", "1")
          .put("AMOUNT", "11.00")
          .put("CURRENCY", "USD")
          .build();
  private static final ImmutableMap<String, String> FEE_06_MAP =
      new ImmutableMap.Builder<String, String>()
          .putAll(BASE_FEE_MAP)
          .put("FEE_VERSION", "0.6")
          .put("FEE_NS", "fee")
          .build();
  private static final ImmutableMap<String, String> FEE_11_MAP =
      new ImmutableMap.Builder<String, String>()
          .putAll(BASE_FEE_MAP)
          .put("FEE_VERSION", "0.11")
          .put("FEE_NS", "fee11")
          .build();
  private static final ImmutableMap<String, String> FEE_12_MAP =
      new ImmutableMap.Builder<String, String>()
          .putAll(BASE_FEE_MAP)
          .put("FEE_VERSION", "0.12")
          .put("FEE_NS", "fee12")
          .build();
  private static final ImmutableMap<String, String> RICH_DOMAIN_MAP =
      ImmutableMap.<String, String>builder()
          .put("DOMAIN", "rich.example")
          .put("YEARS", "1")
          .put("AMOUNT", "100.00")
          .put("CURRENCY", "USD")
          .put("FEE_VERSION", "0.12")
          .put("FEE_NS", "fee12")
          .build();

  @BeforeEach
  void beforeEach() {
    setEppInput("domain_transfer_request.xml");
    setRegistrarIdForFlow("NewRegistrar");
  }

  private void assertTransferRequested(
      DomainBase domain,
      DateTime automaticTransferTime,
      Period expectedPeriod,
      DateTime expectedExpirationTime)
      throws Exception {
    assertAboutDomains()
        .that(domain)
        .hasCurrentSponsorRegistrarId("TheRegistrar")
        .and()
        .hasStatusValue(StatusValue.PENDING_TRANSFER)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("NewRegistrar");
    Trid expectedTrid =
        Trid.create(
            getClientTrid(),
            domain.getTransferData().getTransferRequestTrid().getServerTransactionId());
    assertThat(domain.getTransferData())
        .isEqualTo(
            // Compare against only the following fields by rebuilding the existing TransferData.
            // Equivalent to assertThat(transferData.getGainingClientId()).isEqualTo("NewReg")
            // and similar individual assertions, but produces a nicer error message this way.
            domain
                .getTransferData()
                .asBuilder()
                .setGainingRegistrarId("NewRegistrar")
                .setLosingRegistrarId("TheRegistrar")
                .setTransferRequestTrid(expectedTrid)
                .setTransferRequestTime(clock.nowUtc())
                .setTransferPeriod(expectedPeriod)
                .setTransferStatus(TransferStatus.PENDING)
                .setPendingTransferExpirationTime(automaticTransferTime)
                .setTransferredRegistrationExpirationTime(expectedExpirationTime)
                // Don't compare the server-approve entity fields; they're hard to reconstruct
                // and logic later will check them.
                .build());
  }

  private void assertTransferApproved(
      DomainBase domain, DateTime automaticTransferTime, Period expectedPeriod)
      throws Exception {
    assertAboutDomains()
        .that(domain)
        .hasCurrentSponsorRegistrarId("NewRegistrar")
        .and()
        .hasLastTransferTime(automaticTransferTime)
        .and()
        .doesNotHaveStatusValue(StatusValue.PENDING_TRANSFER);
    Trid expectedTrid =
        Trid.create(
            getClientTrid(),
            domain.getTransferData().getTransferRequestTrid().getServerTransactionId());
    assertThat(domain.getTransferData())
        .isEqualTo(
            new DomainTransferData.Builder()
                .setGainingRegistrarId("NewRegistrar")
                .setLosingRegistrarId("TheRegistrar")
                .setTransferRequestTrid(expectedTrid)
                .setTransferRequestTime(clock.nowUtc())
                .setTransferPeriod(expectedPeriod)
                .setTransferStatus(TransferStatus.SERVER_APPROVED)
                .setPendingTransferExpirationTime(automaticTransferTime)
                .setTransferredRegistrationExpirationTime(domain.getRegistrationExpirationTime())
                // Server-approve entity fields should all be nulled out.
                .build());
  }

  /** Implements the missing Optional.stream function that is added in Java 9. */
  private static <T> Stream<T> optionalToStream(Optional<T> optional) {
    return optional.map(Stream::of).orElseGet(Stream::empty);
  }

  private void assertHistoryEntriesContainBillingEventsAndGracePeriods(
      DateTime expectedExpirationTime,
      DateTime implicitTransferTime,
      Optional<Money> transferCost,
      ImmutableSet<GracePeriod> originalGracePeriods,
      boolean expectTransferBillingEvent,
      BillingEvent.Cancellation.Builder... extraExpectedBillingEvents) {
    Registry registry = Registry.get(domain.getTld());
    final DomainHistory historyEntryTransferRequest =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REQUEST, DomainHistory.class);

    // Construct the billing events we expect to exist, starting with the (optional) billing
    // event for the transfer itself.
    Optional<BillingEvent.OneTime> optionalTransferBillingEvent;
    if (expectTransferBillingEvent) {
      // For normal transfers, a BillingEvent should be created AUTOMATIC_TRANSFER_DAYS in the
      // future, for the case when the transfer is implicitly acked.
      optionalTransferBillingEvent =
          Optional.of(
              new BillingEvent.OneTime.Builder()
                  .setReason(Reason.TRANSFER)
                  .setTargetId(domain.getDomainName())
                  .setEventTime(implicitTransferTime)
                  .setBillingTime(
                      implicitTransferTime.plus(registry.getTransferGracePeriodLength()))
                  .setRegistrarId("NewRegistrar")
                  .setCost(transferCost.orElse(Money.of(USD, 11)))
                  .setPeriodYears(1)
                  .setParent(historyEntryTransferRequest)
                  .build());
    } else {
      // Superuser transfers with no bundled renewal have no transfer billing event.
      optionalTransferBillingEvent = Optional.empty();
    }
    // Construct the autorenew events for the losing/existing client and the gaining one. Note that
    // all of the other transfer flow tests happen on day 3 of the transfer, but the initial
    // request by definition takes place on day 1, so we need to edit the times in the
    // autorenew events from the base test case.
    BillingEvent.Recurring losingClientAutorenew =
        getLosingClientAutorenewEvent()
            .asBuilder()
            .setRecurrenceEndTime(implicitTransferTime)
            .build();
    BillingEvent.Recurring gainingClientAutorenew =
        getGainingClientAutorenewEvent().asBuilder().setEventTime(expectedExpirationTime).build();
    // Construct extra billing events expected by the specific test.
    ImmutableSet<BillingEvent> extraBillingEvents =
        Stream.of(extraExpectedBillingEvents)
            .map(builder -> builder.setParent(historyEntryTransferRequest).build())
            .collect(toImmutableSet());
    // Assert that the billing events we constructed above actually exist in Datastore.
    ImmutableSet<BillingEvent> expectedBillingEvents =
        Streams.concat(
                Stream.of(losingClientAutorenew, gainingClientAutorenew),
                optionalToStream(optionalTransferBillingEvent))
            .collect(toImmutableSet());
    assertBillingEvents(Sets.union(expectedBillingEvents, extraBillingEvents));
    // Assert that the domain's TransferData server-approve billing events match the above.
    if (expectTransferBillingEvent) {
      assertBillingEventsEqual(
          loadByKey(domain.getTransferData().getServerApproveBillingEvent()),
          optionalTransferBillingEvent.get());
    } else {
      assertThat(domain.getTransferData().getServerApproveBillingEvent()).isNull();
    }
    assertBillingEventsEqual(
        loadByKey(domain.getTransferData().getServerApproveAutorenewEvent()),
        gainingClientAutorenew);
    // Assert that the full set of server-approve billing events is exactly the extra ones plus
    // the transfer billing event (if present) and the gaining client autorenew.
    ImmutableSet<BillingEvent> expectedServeApproveBillingEvents =
        Streams.concat(
                Stream.of(gainingClientAutorenew), optionalToStream(optionalTransferBillingEvent))
            .collect(toImmutableSet());
    assertBillingEventsEqual(
        Iterables.filter(
            loadByKeys(domain.getTransferData().getServerApproveEntities()), BillingEvent.class),
        Sets.union(expectedServeApproveBillingEvents, extraBillingEvents));
    // The domain's autorenew billing event should still point to the losing client's event.
    BillingEvent.Recurring domainAutorenewEvent = loadByKey(domain.getAutorenewBillingEvent());
    assertThat(domainAutorenewEvent.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(domainAutorenewEvent.getRecurrenceEndTime()).isEqualTo(implicitTransferTime);
    // The original grace periods should remain untouched.
    assertThat(domain.getGracePeriods()).containsExactlyElementsIn(originalGracePeriods);
    // If we fast forward AUTOMATIC_TRANSFER_DAYS, the transfer should have cleared out all other
    // grace periods, but expect a transfer grace period (if there was a transfer billing event).
    DomainBase domainAfterAutomaticTransfer = domain.cloneProjectedAtTime(implicitTransferTime);
    if (expectTransferBillingEvent) {
      assertGracePeriods(
          domainAfterAutomaticTransfer.getGracePeriods(),
          ImmutableMap.of(
              GracePeriod.create(
                  GracePeriodStatus.TRANSFER,
                  domain.getRepoId(),
                  implicitTransferTime.plus(registry.getTransferGracePeriodLength()),
                  "NewRegistrar",
                  null),
              optionalTransferBillingEvent.get()));
    } else {
      assertGracePeriods(domainAfterAutomaticTransfer.getGracePeriods(), ImmutableMap.of());
    }
  }

  private void assertPollMessagesEmitted(
      DateTime expectedExpirationTime, DateTime implicitTransferTime) {
    // Assert that there exists a poll message to notify the losing registrar that a transfer was
    // requested. If the implicit transfer time is now (i.e. the automatic transfer length is zero)
    // then also expect a server approved poll message.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc()))
        .hasSize(implicitTransferTime.equals(clock.nowUtc()) ? 2 : 1);

    // Two poll messages on the gaining registrar's side at the expected expiration time: a
    // (OneTime) transfer approved message, and an Autorenew poll message.
    assertThat(getPollMessages("NewRegistrar", expectedExpirationTime)).hasSize(2);
    PollMessage transferApprovedPollMessage =
        getOnlyPollMessage("NewRegistrar", implicitTransferTime, PollMessage.OneTime.class);
    PollMessage autorenewPollMessage =
        getOnlyPollMessage("NewRegistrar", expectedExpirationTime, PollMessage.Autorenew.class);
    assertThat(transferApprovedPollMessage.getEventTime()).isEqualTo(implicitTransferTime);
    assertThat(autorenewPollMessage.getEventTime()).isEqualTo(expectedExpirationTime);
    assertThat(
            transferApprovedPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    PendingActionNotificationResponse panData =
        transferApprovedPollMessage
            .getResponseData()
            .stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid().getClientTransactionId()).hasValue("ABC-12345");
    assertThat(panData.getActionResult()).isTrue();

    // Two poll messages on the losing registrar's side at the implicit transfer time: a
    // transfer pending message, and a transfer approved message (both OneTime messages).
    assertThat(getPollMessages("TheRegistrar", implicitTransferTime)).hasSize(2);
    PollMessage losingTransferPendingPollMessage =
        getPollMessages("TheRegistrar", clock.nowUtc())
            .stream()
            .filter(pollMessage -> TransferStatus.PENDING.getMessage().equals(pollMessage.getMsg()))
            .collect(onlyElement());
    PollMessage losingTransferApprovedPollMessage =
        getPollMessages("TheRegistrar", implicitTransferTime)
            .stream()
            .filter(Predicates.not(Predicates.equalTo(losingTransferPendingPollMessage)))
            .collect(onlyElement());
    assertThat(losingTransferPendingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(losingTransferApprovedPollMessage.getEventTime()).isEqualTo(implicitTransferTime);
    assertThat(
            losingTransferPendingPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.PENDING);
    assertThat(
            losingTransferApprovedPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);

    // Assert that the poll messages show up in the TransferData server approve entities.
    assertPollMessagesEqual(
        loadByKey(domain.getTransferData().getServerApproveAutorenewPollMessage()),
        autorenewPollMessage);
    // Assert that the full set of server-approve poll messages is exactly the server approve
    // OneTime messages to gaining and losing registrars plus the gaining client autorenew.
    assertPollMessagesEqual(
        Iterables.filter(
            loadByKeys(domain.getTransferData().getServerApproveEntities()), PollMessage.class),
        ImmutableList.of(
            transferApprovedPollMessage, losingTransferApprovedPollMessage, autorenewPollMessage));
  }

  private void assertAboutDomainAfterAutomaticTransfer(
      DateTime expectedExpirationTime, DateTime implicitTransferTime, Period expectedPeriod)
      throws Exception {
    Registry registry = Registry.get(domain.getTld());
    DomainBase domainAfterAutomaticTransfer = domain.cloneProjectedAtTime(implicitTransferTime);
    assertTransferApproved(domainAfterAutomaticTransfer, implicitTransferTime, expectedPeriod);
    assertAboutDomains()
        .that(domainAfterAutomaticTransfer)
        .hasRegistrationExpirationTime(expectedExpirationTime)
        .and()
        .hasLastEppUpdateTime(implicitTransferTime)
        .and()
        .hasLastEppUpdateClientId("NewRegistrar");
    assertThat(loadByKey(domainAfterAutomaticTransfer.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(expectedExpirationTime);
    // And after the expected grace time, the grace period should be gone.
    DomainBase afterGracePeriod =
        domain.cloneProjectedAtTime(
            clock
                .nowUtc()
                .plus(registry.getAutomaticTransferLength())
                .plus(registry.getTransferGracePeriodLength()));
    assertThat(afterGracePeriod.getGracePeriods()).isEmpty();
  }

  /**
   * Runs a successful test. The extraExpectedBillingEvents parameter consists of cancellation
   * billing event builders that have had all of their attributes set except for the parent history
   * entry, which is filled in during the execution of this method.
   */
  private void doSuccessfulTest(
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      Map<String, String> substitutions,
      Optional<Money> transferCost,
      BillingEvent.Cancellation.Builder... extraExpectedBillingEvents)
      throws Exception {
    setEppInput(commandFilename, substitutions);
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // For all of the other transfer flow tests, 'now' corresponds to day 3 of the transfer, but
    // for the request test we want that same 'now' to be the initial request time, so we shift
    // the transfer timeline 3 days later by adjusting the implicit transfer time here.
    Registry registry = Registry.get(domain.getTld());
    DateTime implicitTransferTime = clock.nowUtc().plus(registry.getAutomaticTransferLength());
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename, substitutions));
    // Transfer should have been requested.
    domain = reloadResourceByForeignKey();
    // Verify that HistoryEntry was created.
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_TRANSFER_REQUEST);
    assertLastHistoryContainsResource(domain);
    final HistoryEntry historyEntryTransferRequest =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REQUEST);
    assertAboutHistoryEntries()
        .that(historyEntryTransferRequest)
        .hasPeriodYears(1)
        .and()
        .hasOtherClientId("TheRegistrar");
    // Verify correct fields were set.
    assertTransferRequested(
        domain, implicitTransferTime, Period.create(1, Unit.YEARS), expectedExpirationTime);

    subordinateHost = reloadResourceAndCloneAtTime(subordinateHost, clock.nowUtc());
    assertAboutHosts().that(subordinateHost).hasNoHistoryEntries();

    assertHistoryEntriesContainBillingEventsAndGracePeriods(
        expectedExpirationTime,
        implicitTransferTime,
        transferCost,
        originalGracePeriods,
        /* expectTransferBillingEvent = */ true,
        extraExpectedBillingEvents);

    assertPollMessagesEmitted(expectedExpirationTime, implicitTransferTime);
    assertAboutDomainAfterAutomaticTransfer(
        expectedExpirationTime, implicitTransferTime, Period.create(1, Unit.YEARS));
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(ResaveEntityAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, domain.createVKey().stringify())
            .param(PARAM_REQUESTED_TIME, clock.nowUtc().toString())
            .etaDelta(
                registry.getAutomaticTransferLength().minus(standardSeconds(30)),
                registry.getAutomaticTransferLength().plus(standardSeconds(30))));
  }

  private void doSuccessfulTest(
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      BillingEvent.Cancellation.Builder... extraExpectedBillingEvents)
      throws Exception {
    doSuccessfulTest(
        commandFilename,
        expectedXmlFilename,
        expectedExpirationTime,
        ImmutableMap.of(),
        Optional.empty(),
        extraExpectedBillingEvents);
  }

  private void doSuccessfulTest(
      String commandFilename, String expectedXmlFilename, Map<String, String> substitutions)
      throws Exception {
    clock.advanceOneMilli();
    doSuccessfulTest(
        commandFilename,
        expectedXmlFilename,
        domain.getRegistrationExpirationTime().plusYears(1),
        substitutions,
        Optional.empty());
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    clock.advanceOneMilli();
    doSuccessfulTest(
        commandFilename, expectedXmlFilename, domain.getRegistrationExpirationTime().plusYears(1));
  }

  private void doSuccessfulSuperuserExtensionTest(
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      Map<String, String> substitutions,
      Optional<Money> transferCost,
      Period expectedPeriod,
      Duration expectedAutomaticTransferLength,
      BillingEvent.Cancellation.Builder... extraExpectedBillingEvents)
      throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput(commandFilename, substitutions);
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // For all of the other transfer flow tests, 'now' corresponds to day 3 of the transfer, but
    // for the request test we want that same 'now' to be the initial request time, so we shift
    // the transfer timeline 3 days later by adjusting the implicit transfer time here.
    DateTime implicitTransferTime = clock.nowUtc().plus(expectedAutomaticTransferLength);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile(expectedXmlFilename, substitutions));

    if (expectedAutomaticTransferLength.equals(Duration.ZERO)) {
      // The transfer is going to happen immediately. To observe the domain in the pending transfer
      // state, grab it directly from the database.
      domain = Iterables.getOnlyElement(transactIfJpaTm(() -> tm().loadAllOf(DomainBase.class)));
      assertThat(domain.getDomainName()).isEqualTo("example.tld");
    } else {
      // Transfer should have been requested.
      domain = reloadResourceByForeignKey();
    }
    // Verify that HistoryEntry was created.
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(DOMAIN_CREATE, DOMAIN_TRANSFER_REQUEST);
    final HistoryEntry historyEntryTransferRequest =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REQUEST);
    assertAboutHistoryEntries()
        .that(historyEntryTransferRequest)
        .hasPeriodYears(expectedPeriod.getValue())
        .and()
        .hasOtherClientId("TheRegistrar");
    // Verify correct fields were set.
    assertTransferRequested(domain, implicitTransferTime, expectedPeriod, expectedExpirationTime);

    subordinateHost = reloadResourceAndCloneAtTime(subordinateHost, clock.nowUtc());
    assertAboutHosts().that(subordinateHost).hasNoHistoryEntries();

    boolean expectTransferBillingEvent = expectedPeriod.getValue() != 0;
    assertHistoryEntriesContainBillingEventsAndGracePeriods(
        expectedExpirationTime,
        implicitTransferTime,
        transferCost,
        originalGracePeriods,
        expectTransferBillingEvent,
        extraExpectedBillingEvents);

    assertPollMessagesEmitted(expectedExpirationTime, implicitTransferTime);
    assertAboutDomainAfterAutomaticTransfer(
        expectedExpirationTime, implicitTransferTime, expectedPeriod);
  }

  private void runTest(
      String commandFilename, UserPrivileges userPrivileges, Map<String, String> substitutions)
      throws Exception {
    setEppInput(commandFilename, substitutions);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow(CommitMode.LIVE, userPrivileges);
  }

  private void runTest(String commandFilename, UserPrivileges userPrivileges) throws Exception {
    runTest(commandFilename, userPrivileges, ImmutableMap.of());
  }

  private void doFailingTest(String commandFilename, Map<String, String> substitutions)
      throws Exception {
    runTest(commandFilename, UserPrivileges.NORMAL, substitutions);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    runTest(commandFilename, UserPrivileges.NORMAL, ImmutableMap.of());
  }

  @TestOfyAndSql
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    setupDomain("example", "tld");
    setEppInput("domain_transfer_request.xml");
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    dryRunFlowAssertResponse(loadFile("domain_transfer_request_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_fee_v06() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_06_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_v11() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_11_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_v12() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_12_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_06_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_11_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_12_MAP);
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_suspendedRegistrarCantTransferDomain() {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    persistResource(
        Registrar.loadByRegistrarId("NewRegistrar")
            .get()
            .asBuilder()
            .setState(State.SUSPENDED)
            .build());
    EppException thrown =
        assertThrows(
            RegistrarMustBeActiveForThisOperationException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_pendingRegistrarCantTransferDomain() {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    persistResource(
        Registrar.loadByRegistrarId("NewRegistrar")
            .get()
            .asBuilder()
            .setState(State.PENDING)
            .build());
    EppException thrown =
        assertThrows(
            RegistrarMustBeActiveForThisOperationException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_nonDefaultAutomaticTransferLength() throws Exception {
    setupDomain("example", "tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAutomaticTransferLength(Duration.standardMinutes(15))
            .build());
    doSuccessfulTest(
        "domain_transfer_request.xml", "domain_transfer_request_response_15_minutes.xml");
  }

  @TestOfyAndSql
  void testSuccess_nonDefaultTransferGracePeriod() throws Exception {
    setupDomain("example", "tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setTransferGracePeriodLength(Duration.standardMinutes(5))
            .build());
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_missingPeriod_defaultsToOneYear() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_missing_period.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_canTransferAwayFromSuspendedRegistrar() throws Exception {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    persistResource(
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.SUSPENDED)
            .build());
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_inQuietPeriod() throws Exception {
    setupDomain("example", "tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testFailure_multiYearPeriod() {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    EppException thrown =
        assertThrows(
            TransferPeriodMustBeOneYearException.class,
            () -> doFailingTest("domain_transfer_request_2_years.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserExtension_zeroPeriod_nonZeroAutomaticTransferLength() throws Exception {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    doSuccessfulSuperuserExtensionTest(
        "domain_transfer_request_superuser_extension.xml",
        "domain_transfer_request_response_su_ext_zero_period_nonzero_transfer_length.xml",
        domain.getRegistrationExpirationTime().plusYears(0),
        ImmutableMap.of("PERIOD", "0", "AUTOMATIC_TRANSFER_LENGTH", "5"),
        Optional.empty(),
        Period.create(0, Unit.YEARS),
        Duration.standardDays(5));
  }

  @TestOfyAndSql
  void testSuccess_superuserExtension_zeroPeriod_zeroAutomaticTransferLength() throws Exception {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    doSuccessfulSuperuserExtensionTest(
        "domain_transfer_request_superuser_extension.xml",
        "domain_transfer_request_response_su_ext_zero_period_zero_transfer_length.xml",
        domain.getRegistrationExpirationTime().plusYears(0),
        ImmutableMap.of("PERIOD", "0", "AUTOMATIC_TRANSFER_LENGTH", "0"),
        Optional.empty(),
        Period.create(0, Unit.YEARS),
        Duration.ZERO);
  }

  @TestOfyAndSql
  void testSuccess_superuserExtension_nonZeroPeriod_nonZeroAutomaticTransferLength()
      throws Exception {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    doSuccessfulSuperuserExtensionTest(
        "domain_transfer_request_superuser_extension.xml",
        "domain_transfer_request_response_su_ext_one_year_period_nonzero_transfer_length.xml",
        domain.getRegistrationExpirationTime().plusYears(1),
        ImmutableMap.of("PERIOD", "1", "AUTOMATIC_TRANSFER_LENGTH", "5"),
        Optional.empty(),
        Period.create(1, Unit.YEARS),
        Duration.standardDays(5));
  }

  @TestOfyAndSql
  void testSuccess_superuserExtension_zeroPeriod_autorenewGraceActive() throws Exception {
    setupDomain("example", "tld");
    VKey<BillingEvent.Recurring> existingAutorenewEvent = domain.getAutorenewBillingEvent();
    // Set domain to have auto-renewed just before the transfer request, so that it will have an
    // active autorenew grace period spanning the entire transfer window.
    DateTime autorenewTime = clock.nowUtc().minusDays(1);
    DateTime expirationTime = autorenewTime.plusYears(1);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(expirationTime)
                .addGracePeriod(
                    GracePeriod.createForRecurring(
                        GracePeriodStatus.AUTO_RENEW,
                        domain.getRepoId(),
                        autorenewTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()),
                        "TheRegistrar",
                        existingAutorenewEvent))
                .build());
    clock.advanceOneMilli();
    doSuccessfulSuperuserExtensionTest(
        "domain_transfer_request_superuser_extension.xml",
        "domain_transfer_request_response_su_ext_zero_period_autorenew_grace.xml",
        domain.getRegistrationExpirationTime(),
        ImmutableMap.of("PERIOD", "0", "AUTOMATIC_TRANSFER_LENGTH", "0"),
        Optional.empty(),
        Period.create(0, Unit.YEARS),
        Duration.ZERO);
  }

  @TestOfyAndSql
  void testFailure_superuserExtension_twoYearPeriod() {
    setupDomain("example", "tld");
    eppRequestSource = EppRequestSource.TOOL;
    clock.advanceOneMilli();
    assertThrows(
        InvalidTransferPeriodValueException.class,
        () ->
            runTest(
                "domain_transfer_request_superuser_extension.xml",
                UserPrivileges.SUPERUSER,
                ImmutableMap.of("PERIOD", "2", "AUTOMATIC_TRANSFER_LENGTH", "5")));
  }

  @TestOfyAndSql
  void testFailure_superuserExtension_zeroPeriod_feeTransferExtension() {
    setupDomain("example", "tld");
    eppRequestSource = EppRequestSource.TOOL;
    clock.advanceOneMilli();
    assertThrows(
        TransferPeriodZeroAndFeeTransferExtensionException.class,
        () ->
            runTest(
                "domain_transfer_request_fee_and_superuser_extension.xml",
                UserPrivileges.SUPERUSER,
                ImmutableMap.of("PERIOD", "0", "AUTOMATIC_TRANSFER_LENGTH", "5")));
  }

  @TestOfyAndSql
  void testSuccess_cappedExpiration() throws Exception {
    setupDomain("example", "tld");
    // Set the domain to expire 10 years from now (as if it were just created with a 10-year term).
    domain =
        persistResource(
            domain.asBuilder().setRegistrationExpirationTime(clock.nowUtc().plusYears(10)).build());
    // New expiration time should be capped at exactly 10 years from the transfer server-approve
    // time, so the domain only ends up gaining the 5-day transfer window's worth of extra
    // registration time.
    clock.advanceOneMilli();
    doSuccessfulTest(
        "domain_transfer_request.xml",
        "domain_transfer_request_response_10_year_cap.xml",
        clock.nowUtc().plus(Registry.get("tld").getAutomaticTransferLength()).plusYears(10));
  }

  @TestOfyAndSql
  void testSuccess_domainAuthInfo() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_domain_authinfo.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_customLogicFee() throws Exception {
    setupDomain("expensive-domain", "foo");
    clock.advanceOneMilli();
    doSuccessfulTest(
        "domain_transfer_request_separate_fees.xml",
        "domain_transfer_request_response_fees.xml",
        domain.getRegistrationExpirationTime().plusYears(1),
        new ImmutableMap.Builder<String, String>()
            .put("DOMAIN", "expensive-domain.foo")
            .put("YEARS", "1")
            .put("AMOUNT", "111.00")
            .put("EXDATE", "2002-09-08T22:00:00.0Z")
            .put("FEE_VERSION", "0.6")
            .put("FEE_NS", "fee")
            .build(),
        Optional.of(Money.of(USD, 111)));
  }

  @TestOfyAndSql
  void testFailure_notAuthorizedForTld() {
    setupDomain("example", "tld");
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown =
        assertThrows(
            NotAuthorizedForTldException.class,
            () ->
                doSuccessfulTest(
                    "domain_transfer_request.xml", "domain_transfer_request_response.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    setupDomain("example", "tld");
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    clock.advanceOneMilli();
    // We don't verify the results; just check that the flow doesn't fail.
    runTest("domain_transfer_request.xml", UserPrivileges.SUPERUSER);
  }

  @TestOfyAndSql
  void testSuccess_autorenewGraceActive_onlyAtTransferRequestTime() throws Exception {
    setupDomain("example", "tld");
    // Set the domain to have auto-renewed long enough ago that it is still in the autorenew grace
    // period at the transfer request time, but will have exited it by the automatic transfer time.
    DateTime autorenewTime =
        clock.nowUtc().minus(Registry.get("tld").getAutoRenewGracePeriodLength()).plusDays(1);
    DateTime expirationTime = autorenewTime.plusYears(1);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(expirationTime)
                .addGracePeriod(
                    GracePeriod.createForRecurring(
                        GracePeriodStatus.AUTO_RENEW,
                        domain.getRepoId(),
                        autorenewTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()),
                        "TheRegistrar",
                        domain.getAutorenewBillingEvent()))
                .build());
    clock.advanceOneMilli();
    // The response from DomainTransferRequestFlow returns exDate based on if the transfer were to
    // occur now.
    doSuccessfulTest(
        "domain_transfer_request.xml",
        "domain_transfer_request_response_autorenew_grace_at_request_only.xml",
        expirationTime.plusYears(1));
  }

  @TestOfyAndSql
  void testSuccess_autorenewGraceActive_throughoutTransferWindow() throws Exception {
    setupDomain("example", "tld");
    VKey<BillingEvent.Recurring> existingAutorenewEvent = domain.getAutorenewBillingEvent();
    // Set domain to have auto-renewed just before the transfer request, so that it will have an
    // active autorenew grace period spanning the entire transfer window.
    DateTime autorenewTime = clock.nowUtc().minusDays(1);
    DateTime expirationTime = autorenewTime.plusYears(1);
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(expirationTime)
                .addGracePeriod(
                    GracePeriod.createForRecurring(
                        GracePeriodStatus.AUTO_RENEW,
                        domain.getRepoId(),
                        autorenewTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()),
                        "TheRegistrar",
                        existingAutorenewEvent))
                .build());
    clock.advanceOneMilli();
    // The transfer will subsume the recent autorenew, so there will be no net change in expiration
    // time caused by the transfer, but we must write a Cancellation.
    doSuccessfulTest(
        "domain_transfer_request.xml",
        "domain_transfer_request_response_autorenew_grace_throughout_transfer_window.xml",
        expirationTime,
        new BillingEvent.Cancellation.Builder()
            .setReason(Reason.RENEW)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            // The cancellation happens at the moment of transfer.
            .setEventTime(clock.nowUtc().plus(Registry.get("tld").getAutomaticTransferLength()))
            .setBillingTime(autorenewTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()))
            // The cancellation should refer to the old autorenew billing event.
            .setRecurringEventKey(existingAutorenewEvent));
  }

  @TestOfyAndSql
  void testSuccess_autorenewGraceActive_onlyAtAutomaticTransferTime() throws Exception {
    setupDomain("example", "tld");
    VKey<BillingEvent.Recurring> existingAutorenewEvent = domain.getAutorenewBillingEvent();
    // Set domain to expire in 1 day, so that it will be in the autorenew grace period by the
    // automatic transfer time, even though it isn't yet.
    DateTime expirationTime = clock.nowUtc().plusDays(1);
    domain =
        persistResource(domain.asBuilder().setRegistrationExpirationTime(expirationTime).build());
    clock.advanceOneMilli();
    // The transfer will subsume the future autorenew, meaning that the expected server-approve
    // expiration time will be 1 year beyond the current one, and we must write a Cancellation.
    doSuccessfulTest(
        "domain_transfer_request.xml",
        "domain_transfer_request_response_autorenew_grace_at_transfer_only.xml",
        expirationTime.plusYears(1),
        new BillingEvent.Cancellation.Builder()
            .setReason(Reason.RENEW)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            // The cancellation happens at the moment of transfer.
            .setEventTime(clock.nowUtc().plus(Registry.get("tld").getAutomaticTransferLength()))
            .setBillingTime(
                expirationTime.plus(Registry.get("tld").getAutoRenewGracePeriodLength()))
            // The cancellation should refer to the old autorenew billing event.
            .setRecurringEventKey(existingAutorenewEvent));
  }

  @TestOfyAndSql
  void testSuccess_premiumNotBlocked() throws Exception {
    setupDomain("rich", "example");
    clock.advanceOneMilli();
    // We don't verify the results; just check that the flow doesn't fail.
    runTest("domain_transfer_request_fee.xml", UserPrivileges.NORMAL, RICH_DOMAIN_MAP);
  }

  @TestOfyAndSql
  void testSuccess_premiumNotBlockedInSuperuserMode() throws Exception {
    setupDomain("rich", "example");
    clock.advanceOneMilli();
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setBlockPremiumNames(true).build());
    // We don't verify the results; just check that the flow doesn't fail.
    runTest("domain_transfer_request_fee.xml", UserPrivileges.SUPERUSER, RICH_DOMAIN_MAP);
  }

  private void runWrongCurrencyTest(Map<String, String> substitutions) {
    Map<String, String> fullSubstitutions = Maps.newHashMap();
    fullSubstitutions.putAll(substitutions);
    fullSubstitutions.put("CURRENCY", "EUR");
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyUnitMismatchException.class,
            () -> doFailingTest("domain_transfer_request_fee.xml", fullSubstitutions));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongCurrency_v06() {
    runWrongCurrencyTest(FEE_06_MAP);
  }

  @TestOfyAndSql
  void testFailure_wrongCurrency_v11() {
    runWrongCurrencyTest(FEE_11_MAP);
  }

  @TestOfyAndSql
  void testFailure_wrongCurrency_v12() {
    runWrongCurrencyTest(FEE_12_MAP);
  }

  @TestOfyAndSql
  void testFailure_unknownCurrency() {
    Map<String, String> substitutions = Maps.newHashMap();
    substitutions.putAll(FEE_06_MAP);
    substitutions.put("CURRENCY", "BAD");
    setupDomain("example", "tld");
    setEppInput("domain_transfer_request_fee.xml", substitutions);
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void runWrongFeeAmountTest(Map<String, String> substitutions) {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    EppException thrown =
        assertThrows(
            FeesMismatchException.class,
            () -> doFailingTest("domain_transfer_request_fee.xml", substitutions));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v06() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_06_MAP);
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v11() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_11_MAP);
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v12() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_12_MAP);
  }

  @TestOfyAndSql
  void testFailure_premiumBlocked() {
    setupDomain("rich", "example");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("NewRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown =
        assertThrows(
            PremiumNameBlockedException.class,
            () -> doFailingTest("domain_transfer_request_premium.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeNotProvidedOnPremiumName() {
    setupDomain("rich", "example");
    EppException thrown =
        assertThrows(
            FeesRequiredForPremiumNameException.class,
            () -> doFailingTest("domain_transfer_request_premium.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_noAuthInfo() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            MissingTransferRequestAuthInfoException.class,
            () -> doFailingTest("domain_transfer_request_no_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_badContactPassword() {
    setupDomain("example", "tld");
    // Change the contact's password so it does not match the password in the file.
    contact =
        persistResource(
            contact
                .asBuilder()
                .setAuthInfo(ContactAuthInfo.create(PasswordAuth.create("badpassword")))
                .build());
    EppException thrown =
        assertThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_badContactRepoId() {
    setupDomain("example", "tld");
    // Set the contact to a different ROID, but don't persist it; this is just so the substitution
    // code above will write the wrong ROID into the file.
    contact = contact.asBuilder().setRepoId("DEADBEEF_TLD-ROID").build();
    EppException thrown =
        assertThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_clientApproved() throws Exception {
    setupDomain("example", "tld");
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_clientRejected() throws Exception {
    setupDomain("example", "tld");
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_clientCancelled() throws Exception {
    setupDomain("example", "tld");
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_serverApproved() throws Exception {
    setupDomain("example", "tld");
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_serverCancelled() throws Exception {
    setupDomain("example", "tld");
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doSuccessfulTest("domain_transfer_request.xml", "domain_transfer_request_response.xml");
  }

  @TestOfyAndSql
  void testFailure_pending() {
    setupDomain("example", "tld");
    domain =
        persistResource(
            domain
                .asBuilder()
                .setTransferData(
                    domain
                        .getTransferData()
                        .asBuilder()
                        .setTransferStatus(TransferStatus.PENDING)
                        .setPendingTransferExpirationTime(clock.nowUtc().plusDays(1))
                        .build())
                .build());
    EppException thrown =
        assertThrows(
            AlreadyPendingTransferException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_badDomainPassword() {
    setupDomain("example", "tld");
    // Change the domain's password so it does not match the password in the file.
    domain =
        persistResource(
            domain
                .asBuilder()
                .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("badpassword")))
                .build());
    EppException thrown =
        assertThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("domain_transfer_request_domain_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_sponsoringClient() {
    setupDomain("example", "tld");
    setRegistrarIdForFlow("TheRegistrar");
    EppException thrown =
        assertThrows(
            ObjectAlreadySponsoredException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_deletedDomain() throws Exception {
    setupDomain("example", "tld");
    domain =
        persistResource(domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_invalidDomain() throws Exception {
    setupDomain("example", "tld");
    setEppInput(
        "domain_transfer_request_wildcard.xml",
        ImmutableMap.of("YEARS", "1", "DOMAIN", "--invalid", "EXDATE", "2002-09-08T22:00:00.0Z"));
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    assertTransactionalFlow(true);
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> runFlow(CommitMode.LIVE, UserPrivileges.NORMAL));
    assertThat(thrown).hasMessageThat().contains("(--invalid)");
  }

  @TestOfyAndSql
  void testFailure_nonexistentDomain() {
    createTld("tld");
    contact = persistActiveContact("jd1234");
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", "example.tld"));
  }

  @TestOfyAndSql
  void testFailure_periodInMonths() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            BadPeriodUnitException.class,
            () -> doFailingTest("domain_transfer_request_months.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientTransferProhibited() {
    setupDomain("example", "tld");
    domain =
        persistResource(
            domain.asBuilder().addStatusValue(StatusValue.CLIENT_TRANSFER_PROHIBITED).build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("clientTransferProhibited");
  }

  @TestOfyAndSql
  void testFailure_serverTransferProhibited() {
    setupDomain("example", "tld");
    domain =
        persistResource(
            domain.asBuilder().addStatusValue(StatusValue.SERVER_TRANSFER_PROHIBITED).build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("serverTransferProhibited");
  }

  @TestOfyAndSql
  void testFailure_pendingDelete() {
    setupDomain("example", "tld");
    domain = persistResource(domain.asBuilder().addStatusValue(StatusValue.PENDING_DELETE).build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(
            ResourceStatusProhibitsOperationException.class,
            () -> doFailingTest("domain_transfer_request.xml"));
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    setupDomain("example", "tld");
    clock.advanceOneMilli();
    runTest("domain_transfer_request.xml", UserPrivileges.NORMAL);
    assertIcannReportingActivityFieldLogged("srs-dom-transfer-request");
    assertTldsFieldLogged("tld");
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_getsStored() throws Exception {
    setupDomain("example", "tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAutomaticTransferLength(Duration.standardDays(2))
            .setTransferGracePeriodLength(Duration.standardDays(3))
            .build());
    clock.advanceOneMilli();
    runTest("domain_transfer_request.xml", UserPrivileges.NORMAL);
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REQUEST);
    // We should produce a transfer success record
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld", clock.nowUtc().plusDays(5), TRANSFER_SUCCESSFUL, 1));
  }

  @TestOfyOnly
  void testModification_duringReadOnlyPhase() {
    setupDomain("example", "tld");
    DatabaseHelper.setMigrationScheduleToDatastorePrimaryReadOnly(clock);
    EppException thrown =
        assertThrows(
            ReadOnlyModeEppException.class, () -> doFailingTest("domain_transfer_request.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    DatabaseHelper.removeDatabaseMigrationSchedule();
  }
}
