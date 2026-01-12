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

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
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
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.HostSubject.assertAboutHosts;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.base.Ascii;
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
import google.registry.flows.EppRequestSource;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.exceptions.TransferPeriodZeroAndFeeTransferExtensionException;
import google.registry.model.billing.BillingBase;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.host.Host;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DomainTransferRequestFlow} that use the old fee extensions (0.6, 0.11, 0.12).
 */
public class DomainTransferRequestFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainTransferRequestFlow> {

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
          .put("FEE_VERSION", "fee-0.6")
          .put("FEE_NS", "fee")
          .build();
  private static final ImmutableMap<String, String> FEE_11_MAP =
      new ImmutableMap.Builder<String, String>()
          .putAll(BASE_FEE_MAP)
          .put("FEE_VERSION", "fee-0.11")
          .put("FEE_NS", "fee11")
          .build();
  private static final ImmutableMap<String, String> FEE_12_MAP =
      new ImmutableMap.Builder<String, String>()
          .putAll(BASE_FEE_MAP)
          .put("FEE_VERSION", "fee-0.12")
          .put("FEE_NS", "fee12")
          .build();
  private static final ImmutableMap<String, String> RICH_DOMAIN_MAP =
      ImmutableMap.<String, String>builder()
          .put("DOMAIN", "rich.example")
          .put("YEARS", "1")
          .put("AMOUNT", "100.00")
          .put("CURRENCY", "USD")
          .put("FEE_VERSION", "fee-0.12")
          .put("FEE_NS", "fee12")
          .build();

  private static final DateTime TRANSFER_REQUEST_TIME = DateTime.parse("2000-06-06T22:00:00.0Z");
  private static final DateTime TRANSFER_EXPIRATION_TIME =
      TRANSFER_REQUEST_TIME.plus(Tld.DEFAULT_AUTOMATIC_TRANSFER_LENGTH);
  private static final Duration TIME_SINCE_REQUEST = Duration.standardDays(3);
  private static final int EXTENDED_REGISTRATION_YEARS = 1;
  private static final DateTime REGISTRATION_EXPIRATION_TIME =
      DateTime.parse("2001-09-08T22:00:00.0Z");
  private static final DateTime EXTENDED_REGISTRATION_EXPIRATION_TIME =
      REGISTRATION_EXPIRATION_TIME.plusYears(EXTENDED_REGISTRATION_YEARS);

  private Contact contact;
  private Domain domain;
  private Host subordinateHost;
  private DomainHistory historyEntryDomainCreate;

  @BeforeEach
  void beforeEachDomainTransferRequestFlowOldFeeExtensionsTest() {
    setEppInput("domain_transfer_request.xml");
    setRegistrarIdForFlow("NewRegistrar");
    clock.setTo(TRANSFER_REQUEST_TIME.plus(TIME_SINCE_REQUEST));
  }

  @Test
  void testFailure_wrongFeeAmount_v06() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_06_MAP);
  }

  @Test
  void testFailure_wrongFeeAmount_v11() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_11_MAP);
  }

  @Test
  void testFailure_wrongFeeAmount_v12() {
    setupDomain("example", "tld");
    runWrongFeeAmountTest(FEE_12_MAP);
  }

  @Test
  void testFailure_appliedFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_applied.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_grace_period.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_06_MAP);
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_11_MAP);
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee_defaults.xml",
        "domain_transfer_request_response_fee.xml",
        FEE_12_MAP);
  }

  @Test
  void testFailure_refundableFee_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            UnsupportedFeeAttributeException.class,
            () -> doFailingTest("domain_transfer_request_fee_refundable.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v06() {
    runWrongCurrencyTest(FEE_06_MAP);
  }

  @Test
  void testFailure_wrongCurrency_v11() {
    runWrongCurrencyTest(FEE_11_MAP);
  }

  @Test
  void testFailure_wrongCurrency_v12() {
    runWrongCurrencyTest(FEE_12_MAP);
  }

  @Test
  void testFailure_feeGivenInWrongScale_v06() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_06_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v11() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_11_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v12() {
    setupDomain("example", "tld");
    EppException thrown =
        assertThrows(
            CurrencyValueScaleException.class,
            () -> doFailingTest("domain_transfer_request_fee_bad_scale.xml", FEE_12_MAP));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_v06() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_06_MAP);
  }

  @Test
  void testSuccess_fee_v11() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_11_MAP);
  }

  @Test
  void testSuccess_fee_v12() throws Exception {
    setupDomain("example", "tld");
    doSuccessfulTest(
        "domain_transfer_request_fee.xml", "domain_transfer_request_response_fee.xml", FEE_12_MAP);
  }

  @Test
  void testSuccess_customLogicFee_v06() throws Exception {
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
            .put("FEE_VERSION", "fee-0.6")
            .put("FEE_NS", "fee")
            .build(),
        Optional.of(Money.of(USD, 111)));
  }

  @Test
  void testSuccess_premiumNotBlocked_v12() throws Exception {
    setupDomain("rich", "example");
    clock.advanceOneMilli();
    // We don't verify the results; just check that the flow doesn't fail.
    runTest("domain_transfer_request_fee.xml", UserPrivileges.NORMAL, RICH_DOMAIN_MAP);
  }

  @Test
  void testFailure_superuserExtension_zeroPeriod_feeTransferExtension_v12() {
    setupDomain("example", "tld");
    eppRequestSource = EppRequestSource.TOOL;
    clock.advanceOneMilli();
    assertThrows(
        TransferPeriodZeroAndFeeTransferExtensionException.class,
        () ->
            runTest(
                "domain_transfer_request_fee_and_superuser_extension.xml",
                UserPrivileges.SUPERUSER,
                new ImmutableMap.Builder<String, String>()
                    .putAll(FEE_12_MAP)
                    .put("PERIOD", "0")
                    .put("AUTOMATIC_TRANSFER_LENGTH", "5")
                    .build()));
  }

  @Test
  void testSuccess_premiumNotBlockedInSuperuserMode_v12() throws Exception {
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
      BillingCancellation.Builder... extraExpectedBillingEvents)
      throws Exception {
    setEppInput(commandFilename, substitutions);
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // For all of the other transfer flow tests, 'now' corresponds to day 3 of the transfer, but
    // for the request test we want that same 'now' to be the initial request time, so we shift
    // the transfer timeline 3 days later by adjusting the implicit transfer time here.
    Tld registry = Tld.get(domain.getTld());
    DateTime implicitTransferTime = clock.nowUtc().plus(registry.getAutomaticTransferLength());
    // Setup done; run the test.
    assertMutatingFlow(true);
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
        .hasOtherRegistrarId("TheRegistrar");
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
        /* expectTransferBillingEvent= */ true,
        extraExpectedBillingEvents);

    assertPollMessagesEmitted(expectedExpirationTime, implicitTransferTime);
    assertAboutDomainAfterAutomaticTransfer(
        expectedExpirationTime, implicitTransferTime, Period.create(1, Unit.YEARS));
    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .path(ResaveEntityAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, domain.createVKey().stringify())
            .param(PARAM_REQUESTED_TIME, clock.nowUtc().toString())
            .scheduleTime(clock.nowUtc().plus(registry.getAutomaticTransferLength())));
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

  private void doSuccessfulTest(
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      BillingCancellation.Builder... extraExpectedBillingEvents)
      throws Exception {
    doSuccessfulTest(
        commandFilename,
        expectedXmlFilename,
        expectedExpirationTime,
        ImmutableMap.of(),
        Optional.empty(),
        extraExpectedBillingEvents);
  }

  private void runWrongFeeAmountTest(Map<String, String> substitutions) {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    EppException thrown =
        assertThrows(
            FeesMismatchException.class,
            () -> doFailingTest("domain_transfer_request_fee.xml", substitutions));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void runTest(
      String commandFilename, UserPrivileges userPrivileges, Map<String, String> substitutions)
      throws Exception {
    setEppInput(commandFilename, substitutions);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertMutatingFlow(true);
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

  /** Adds a domain with no pending transfer on it. */
  void setupDomain(String label, String tld) {
    createTld(tld);
    contact = persistActiveContact("jd1234");
    domain =
        persistDomainWithDependentResources(
            label,
            tld,
            contact,
            clock.nowUtc(),
            DateTime.parse("1999-04-03T22:00:00.0Z"),
            REGISTRATION_EXPIRATION_TIME);
    subordinateHost =
        persistResource(
            new Host.Builder()
                .setRepoId("2-".concat(Ascii.toUpperCase(tld)))
                .setHostName("ns1." + label + "." + tld)
                .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
                .setCreationRegistrarId("TheRegistrar")
                .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
                .setSuperordinateDomain(domain.createVKey())
                .build());
    domain =
        persistResource(
            domain.asBuilder().addSubordinateHost(subordinateHost.getHostName()).build());
    historyEntryDomainCreate =
        getOnlyHistoryEntryOfType(domain, DOMAIN_CREATE, DomainHistory.class);
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
            transferApprovedPollMessage.getResponseData().stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_APPROVED);
    PendingActionNotificationResponse panData =
        transferApprovedPollMessage.getResponseData().stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid().getClientTransactionId()).hasValue("ABC-12345");
    assertThat(panData.getActionResult()).isTrue();

    // Two poll messages on the losing registrar's side at the implicit transfer time: a
    // transfer pending message, and a transfer approved message (both OneTime messages).
    assertThat(getPollMessages("TheRegistrar", implicitTransferTime)).hasSize(2);
    PollMessage losingTransferPendingPollMessage =
        getPollMessages("TheRegistrar", clock.nowUtc()).stream()
            .filter(pollMessage -> TransferStatus.PENDING.getMessage().equals(pollMessage.getMsg()))
            .collect(onlyElement());
    PollMessage losingTransferApprovedPollMessage =
        getPollMessages("TheRegistrar", implicitTransferTime).stream()
            .filter(Predicates.not(Predicates.equalTo(losingTransferPendingPollMessage)))
            .collect(onlyElement());
    assertThat(losingTransferPendingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(losingTransferApprovedPollMessage.getEventTime()).isEqualTo(implicitTransferTime);
    assertThat(
            losingTransferPendingPollMessage.getResponseData().stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.PENDING);
    assertThat(
            losingTransferApprovedPollMessage.getResponseData().stream()
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

  private void assertHistoryEntriesContainBillingEventsAndGracePeriods(
      DateTime expectedExpirationTime,
      DateTime implicitTransferTime,
      Optional<Money> transferCost,
      ImmutableSet<GracePeriod> originalGracePeriods,
      boolean expectTransferBillingEvent,
      BillingCancellation.Builder... extraExpectedBillingEvents) {
    Tld registry = Tld.get(domain.getTld());
    final DomainHistory historyEntryTransferRequest =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REQUEST, DomainHistory.class);

    // Construct the billing events we expect to exist, starting with the (optional) billing
    // event for the transfer itself.
    Optional<BillingEvent> optionalTransferBillingEvent;
    if (expectTransferBillingEvent) {
      // For normal transfers, a BillingEvent should be created AUTOMATIC_TRANSFER_DAYS in the
      // future, for the case when the transfer is implicitly acked.
      optionalTransferBillingEvent =
          Optional.of(
              new BillingEvent.Builder()
                  .setReason(Reason.TRANSFER)
                  .setTargetId(domain.getDomainName())
                  .setEventTime(implicitTransferTime)
                  .setBillingTime(
                      implicitTransferTime.plus(registry.getTransferGracePeriodLength()))
                  .setRegistrarId("NewRegistrar")
                  .setCost(transferCost.orElse(Money.of(USD, 11)))
                  .setPeriodYears(1)
                  .setDomainHistory(historyEntryTransferRequest)
                  .build());
    } else {
      // Superuser transfers with no bundled renewal have no transfer billing event.
      optionalTransferBillingEvent = Optional.empty();
    }
    // Construct the autorenew events for the losing/existing client and the gaining one. Note that
    // all of the other transfer flow tests happen on day 3 of the transfer, but the initial
    // request by definition takes place on day 1, so we need to edit the times in the
    // autorenew events from the base test case.
    BillingRecurrence losingClientAutorenew =
        getLosingClientAutorenewEvent()
            .asBuilder()
            .setRecurrenceEndTime(implicitTransferTime)
            .build();
    BillingRecurrence gainingClientAutorenew =
        getGainingClientAutorenewEvent()
            .asBuilder()
            .setEventTime(expectedExpirationTime)
            .setRecurrenceLastExpansion(expectedExpirationTime.minusYears(1))
            .build();
    // Construct extra billing events expected by the specific test.
    ImmutableSet<BillingBase> extraBillingBases =
        Stream.of(extraExpectedBillingEvents)
            .map(builder -> builder.setDomainHistory(historyEntryTransferRequest).build())
            .collect(toImmutableSet());
    // Assert that the billing events we constructed above actually exist in the database.
    ImmutableSet<BillingBase> expectedBillingBases =
        Streams.concat(
                Stream.of(losingClientAutorenew, gainingClientAutorenew),
                optionalTransferBillingEvent.stream())
            .collect(toImmutableSet());
    assertBillingEvents(Sets.union(expectedBillingBases, extraBillingBases));
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
    ImmutableSet<BillingBase> expectedServeApproveBillingBases =
        Streams.concat(Stream.of(gainingClientAutorenew), optionalTransferBillingEvent.stream())
            .collect(toImmutableSet());
    assertBillingEventsEqual(
        Iterables.filter(
            loadByKeys(domain.getTransferData().getServerApproveEntities()), BillingBase.class),
        Sets.union(expectedServeApproveBillingBases, extraBillingBases));
    // The domain's autorenew billing event should still point to the losing client's event.
    BillingRecurrence domainAutorenewEvent = loadByKey(domain.getAutorenewBillingEvent());
    assertThat(domainAutorenewEvent.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(domainAutorenewEvent.getRecurrenceEndTime()).isEqualTo(implicitTransferTime);
    // The original grace periods should remain untouched.
    assertThat(domain.getGracePeriods()).containsExactlyElementsIn(originalGracePeriods);
    // If we fast forward AUTOMATIC_TRANSFER_DAYS, the transfer should have cleared out all other
    // grace periods, but expect a transfer grace period (if there was a transfer billing event).
    Domain domainAfterAutomaticTransfer = domain.cloneProjectedAtTime(implicitTransferTime);
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

  private BillingRecurrence getGainingClientAutorenewEvent() {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setRegistrarId("NewRegistrar")
        .setEventTime(EXTENDED_REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(END_OF_TIME)
        .setDomainHistory(
            getOnlyHistoryEntryOfType(
                domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST, DomainHistory.class))
        .build();
  }

  /** Get the autorenew event that the losing client will have after a SERVER_APPROVED transfer. */
  private BillingRecurrence getLosingClientAutorenewEvent() {
    return new BillingRecurrence.Builder()
        .setReason(Reason.RENEW)
        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
        .setTargetId(domain.getDomainName())
        .setRegistrarId("TheRegistrar")
        .setEventTime(REGISTRATION_EXPIRATION_TIME)
        .setRecurrenceEndTime(TRANSFER_EXPIRATION_TIME)
        .setDomainHistory(historyEntryDomainCreate)
        .build();
  }

  private void assertAboutDomainAfterAutomaticTransfer(
      DateTime expectedExpirationTime, DateTime implicitTransferTime, Period expectedPeriod)
      throws Exception {
    Tld registry = Tld.get(domain.getTld());
    Domain domainAfterAutomaticTransfer = domain.cloneProjectedAtTime(implicitTransferTime);
    assertTransferApproved(domainAfterAutomaticTransfer, implicitTransferTime, expectedPeriod);
    assertAboutDomains()
        .that(domainAfterAutomaticTransfer)
        .hasRegistrationExpirationTime(expectedExpirationTime)
        .and()
        .hasLastEppUpdateTime(implicitTransferTime)
        .and()
        .hasLastEppUpdateRegistrarId("NewRegistrar");
    assertThat(loadByKey(domainAfterAutomaticTransfer.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(expectedExpirationTime);
    // And after the expected grace time, the grace period should be gone.
    Domain afterGracePeriod =
        domain.cloneProjectedAtTime(
            clock
                .nowUtc()
                .plus(registry.getAutomaticTransferLength())
                .plus(registry.getTransferGracePeriodLength()));
    assertThat(afterGracePeriod.getGracePeriods()).isEmpty();
  }

  private void assertTransferApproved(
      Domain domain, DateTime automaticTransferTime, Period expectedPeriod) throws Exception {
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

  private void assertTransferRequested(
      Domain domain,
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
        .hasLastEppUpdateRegistrarId("NewRegistrar");
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
}
