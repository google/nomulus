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

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.BULK_PRICING;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.NET_ADDS_4_YR;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_APPROVE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
import static google.registry.testing.DatabaseHelper.assertBillingEventsForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.deleteTestDomain;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessage;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.google.common.collect.Streams;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.NonexistentAllocationTokenException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.model.billing.BillingBase;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingCancellation;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.Period.Unit;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainTransferApproveFlow}. */
class DomainTransferApproveFlowTest
    extends DomainTransferFlowTestCase<DomainTransferApproveFlow, Domain> {

  @BeforeEach
  void beforeEach() {
    setEppInput("domain_transfer_approve.xml");
    // Change the registry so that the renew price changes a day minus 1 millisecond before the
    // transfer (right after there will be an autorenew in the test case that has one) and then
    // again a millisecond after the transfer request time. These changes help us ensure that the
    // flows are using prices from the moment of transfer request (or autorenew) and not from the
    // moment that the transfer is approved.
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 1))
                    .put(clock.nowUtc().minusDays(1).plusMillis(1), Money.of(USD, 22))
                    .put(TRANSFER_REQUEST_TIME.plusMillis(1), Money.of(USD, 333))
                    .build())
            .build());
    setRegistrarIdForFlow("TheRegistrar");
    createTld("extra");
    setupDomainWithPendingTransfer("example", "tld");
    clock.advanceOneMilli();
  }

  private void assertTransferApproved(Domain domain, DomainTransferData oldTransferData) {
    assertAboutDomains()
        .that(domain)
        .hasCurrentSponsorRegistrarId("NewRegistrar")
        .and()
        .hasLastTransferTime(clock.nowUtc())
        .and()
        .doesNotHaveStatusValue(StatusValue.PENDING_TRANSFER)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateRegistrarId("TheRegistrar");
    // The domain TransferData should reflect the approved transfer as we expect, with
    // all the speculative server-approve fields nulled out.
    assertThat(domain.getTransferData())
        .isEqualTo(
            oldTransferData
                .copyConstantFieldsToBuilder()
                .setTransferStatus(TransferStatus.CLIENT_APPROVED)
                .setPendingTransferExpirationTime(clock.nowUtc())
                .setTransferredRegistrationExpirationTime(domain.getRegistrationExpirationTime())
                .build());
  }

  private void setEppLoader(String commandFilename) {
    setEppInput(commandFilename);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
  }

  /**
   * Runs a successful test, with the expectedCancellationBillingEvents parameter containing a list
   * of billing event builders that will be filled out with the correct HistoryEntry parent as it is
   * created during the execution of this test.
   */
  private void doSuccessfulTest(
      String tld,
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime,
      int expectedYearsToCharge,
      BillingCancellation.Builder... expectedCancellationBillingEvents)
      throws Exception {
    runSuccessfulFlowWithAssertions(
        tld, commandFilename, expectedXmlFilename, expectedExpirationTime);
    assertHistoryEntriesContainBillingEventsAndGracePeriods(
        tld, expectedYearsToCharge, expectedCancellationBillingEvents);
  }

  private void runSuccessfulFlowWithAssertions(
      String tld,
      String commandFilename,
      String expectedXmlFilename,
      DateTime expectedExpirationTime)
      throws Exception {
    setEppLoader(commandFilename);
    Tld registry = Tld.get(tld);
    domain = reloadResourceByForeignKey();
    // Make sure the implicit billing event is there; it will be deleted by the flow.
    // We also expect to see autorenew events for the gaining and losing registrars.
    assertBillingEventsForResource(
        domain,
        getBillingEventForImplicitTransfer(),
        getGainingClientAutorenewEvent(),
        getLosingClientAutorenewEvent());
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages(domain, "NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages(domain, "TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    // Setup done; run the test.
    DomainTransferData originalTransferData = domain.getTransferData();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));
    // Transfer should have succeeded. Verify correct fields were set.
    domain = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(
            DOMAIN_CREATE, DOMAIN_TRANSFER_REQUEST, DOMAIN_TRANSFER_APPROVE);
    final HistoryEntry historyEntryTransferApproved =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE);
    assertAboutHistoryEntries()
        .that(historyEntryTransferApproved)
        .hasOtherRegistrarId("NewRegistrar");
    assertTransferApproved(domain, originalTransferData);
    assertAboutDomains().that(domain).hasRegistrationExpirationTime(expectedExpirationTime);
    assertThat(loadByKey(domain.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(expectedExpirationTime);
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages(domain, "TheRegistrar", clock.nowUtc().plusMonths(1))).isEmpty();

    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar, as well as one at the domain's
    // autorenew time.
    assertThat(getPollMessages(domain, "NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages(domain, "NewRegistrar", domain.getRegistrationExpirationTime()))
        .hasSize(2);

    PollMessage gainingTransferPollMessage =
        getOnlyPollMessage(domain, "NewRegistrar", clock.nowUtc(), PollMessage.OneTime.class);
    PollMessage gainingAutorenewPollMessage =
        getOnlyPollMessage(
            domain,
            "NewRegistrar",
            domain.getRegistrationExpirationTime(),
            PollMessage.Autorenew.class);
    assertThat(gainingTransferPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(gainingAutorenewPollMessage.getEventTime())
        .isEqualTo(domain.getRegistrationExpirationTime());
    DomainTransferResponse transferResponse =
        gainingTransferPollMessage
            .getResponseData()
            .stream()
            .filter(DomainTransferResponse.class::isInstance)
            .map(DomainTransferResponse.class::cast)
            .collect(onlyElement());
    assertThat(transferResponse.getTransferStatus()).isEqualTo(TransferStatus.CLIENT_APPROVED);
    assertThat(transferResponse.getExtendedRegistrationExpirationTime())
        .isEqualTo(domain.getRegistrationExpirationTime());
    PendingActionNotificationResponse panData =
        gainingTransferPollMessage
            .getResponseData()
            .stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isTrue();

    // After the expected grace time, the grace period should be gone.
    assertThat(
            domain
                .cloneProjectedAtTime(clock.nowUtc().plus(registry.getTransferGracePeriodLength()))
                .getGracePeriods())
        .isEmpty();
  }

  private void assertHistoryEntriesContainBillingEventsAndGracePeriods(
      String tld,
      int expectedYearsToCharge,
      BillingCancellation.Builder... expectedCancellationBillingEvents)
      throws Exception {
    Tld registry = Tld.get(tld);
    domain = reloadResourceByForeignKey();
    final DomainHistory historyEntryTransferApproved =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE, DomainHistory.class);
    // We expect three billing events: one for the transfer, a closed autorenew for the losing
    // client and an open autorenew for the gaining client that begins at the new expiration time.
    BillingEvent transferBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.TRANSFER)
            .setTargetId(domain.getDomainName())
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc().plus(registry.getTransferGracePeriodLength()))
            .setRegistrarId("NewRegistrar")
            .setCost(Money.of(USD, 11).multipliedBy(expectedYearsToCharge))
            .setPeriodYears(expectedYearsToCharge)
            .setDomainHistory(historyEntryTransferApproved)
            .build();
    assertBillingEventsForResource(
        domain,
        Streams.concat(
                Arrays.stream(expectedCancellationBillingEvents)
                    .map(builder -> builder.setDomainHistory(historyEntryTransferApproved).build()),
                Stream.of(
                    transferBillingEvent,
                    getLosingClientAutorenewEvent()
                        .asBuilder()
                        .setRecurrenceEndTime(clock.nowUtc())
                        .build(),
                    getGainingClientAutorenewEvent()
                        .asBuilder()
                        .setEventTime(domain.getRegistrationExpirationTime())
                        .setRecurrenceLastExpansion(
                            domain.getRegistrationExpirationTime().minusYears(1))
                        .setDomainHistory(historyEntryTransferApproved)
                        .build()))
            .toArray(BillingBase[]::new));
    // There should be a grace period for the new transfer billing event.
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.TRANSFER,
                domain.getRepoId(),
                clock.nowUtc().plus(registry.getTransferGracePeriodLength()),
                "NewRegistrar",
                null),
            transferBillingEvent));
  }

  private void assertHistoryEntriesDoNotContainTransferBillingEventsOrGracePeriods(
      BillingCancellation.Builder... expectedCancellationBillingEvents) throws Exception {
    domain = reloadResourceByForeignKey();
    final DomainHistory historyEntryTransferApproved =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE, DomainHistory.class);
    // We expect two billing events: a closed autorenew for the losing client and an open autorenew
    // for the gaining client that begins at the new expiration time.
    assertBillingEventsForResource(
        domain,
        Streams.concat(
                Arrays.stream(expectedCancellationBillingEvents)
                    .map(builder -> builder.setDomainHistory(historyEntryTransferApproved).build()),
                Stream.of(
                    getLosingClientAutorenewEvent()
                        .asBuilder()
                        .setRecurrenceEndTime(clock.nowUtc())
                        .build(),
                    getGainingClientAutorenewEvent()
                        .asBuilder()
                        .setEventTime(domain.getRegistrationExpirationTime())
                        .setRecurrenceLastExpansion(
                            domain.getRegistrationExpirationTime().minusYears(1))
                        .setDomainHistory(historyEntryTransferApproved)
                        .build()))
            .toArray(BillingBase[]::new));
    // There should be no grace period.
    assertGracePeriods(domain.getGracePeriods(), ImmutableMap.of());
  }

  private void doSuccessfulTest(String tld, String commandFilename, String expectedXmlFilename)
      throws Exception {
    clock.advanceOneMilli();
    doSuccessfulTest(
        tld,
        commandFilename,
        expectedXmlFilename,
        domain.getRegistrationExpirationTime().plusYears(1),
        1);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppLoader(commandFilename);
    // Setup done; run the test.
    assertMutatingFlow(true);
    runFlow();
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    setEppLoader("domain_transfer_approve.xml");
    dryRunFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
  }

  @Test
  void testDryRun_bulkPricingDomain() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .build());
    domain = reloadResourceByForeignKey();
    persistResource(
        loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("10.00")))
            .build());
    persistResource(domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build());
    clock.advanceOneMilli();
    setEppInput("domain_transfer_approve_wildcard.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    dryRunFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    doSuccessfulTest("tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml");
  }

  @Test
  void testSuccess_removesBulkToken() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .build());
    domain = reloadResourceByForeignKey();
    persistResource(
        loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("10.00")))
            .build());
    persistResource(domain.asBuilder().setCurrentBulkToken(allocationToken.createVKey()).build());
    clock.advanceOneMilli();
    setEppInput("domain_transfer_approve_wildcard.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime now = clock.nowUtc();
    runFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
    domain = reloadResourceByForeignKey();
    DomainHistory acceptHistory =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE, DomainHistory.class);
    assertBillingEventsForResource(
        domain,
        new BillingEvent.Builder()
            .setBillingTime(now.plusDays(5))
            .setEventTime(now)
            .setRegistrarId("NewRegistrar")
            .setCost(Money.of(USD, new BigDecimal("11.00")))
            .setDomainHistory(acceptHistory)
            .setReason(Reason.TRANSFER)
            .setPeriodYears(1)
            .setTargetId("example.tld")
            .build(),
        getGainingClientAutorenewEvent()
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.DEFAULT)
            .setRenewalPrice(null)
            .setDomainHistory(acceptHistory)
            .build(),
        getLosingClientAutorenewEvent()
            .asBuilder()
            .setRecurrenceEndTime(now)
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("10.00")))
            .build());
    assertThat(domain.getCurrentBulkToken()).isEmpty();
    assertThat(domain.getCurrentSponsorRegistrarId()).isEqualTo("NewRegistrar");
    assertThat(loadByKey(domain.getAutorenewBillingEvent()).getRenewalPriceBehavior())
        .isEqualTo(RenewalPriceBehavior.DEFAULT);
  }

  @Test
  void testSuccess_nonDefaultTransferGracePeriod() throws Exception {
    // We have to set up a new domain in a different TLD so that the billing event will be persisted
    // with the new transfer grace period in mind.
    createTld("net");
    persistResource(
        Tld.get("net")
            .asBuilder()
            .setTransferGracePeriodLength(Duration.standardMinutes(10))
            .build());
    setupDomainWithPendingTransfer("example", "net");
    doSuccessfulTest(
        "net", "domain_transfer_approve_net.xml", "domain_transfer_approve_response_net.xml");
  }

  @Test
  void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_domain_authinfo.xml",
        "domain_transfer_approve_response.xml");
  }

  @Test
  void testSuccess_contactAuthInfo() throws Exception {
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_contact_authinfo.xml",
        "domain_transfer_approve_response.xml");
  }

  @Test
  void testSuccess_autorenewBeforeTransfer() throws Exception {
    domain = reloadResourceByForeignKey();
    DateTime oldExpirationTime = clock.nowUtc().minusDays(1);
    persistResource(domain.asBuilder().setRegistrationExpirationTime(oldExpirationTime).build());
    // The autorenew should be subsumed into the transfer resulting in 1 year of renewal in total.
    clock.advanceOneMilli();
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_domain_authinfo.xml",
        "domain_transfer_approve_response_autorenew.xml",
        oldExpirationTime.plusYears(1),
        1,
        // Expect the grace period for autorenew to be cancelled.
        new BillingCancellation.Builder()
            .setReason(Reason.RENEW)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(clock.nowUtc()) // The cancellation happens at the moment of transfer.
            .setBillingTime(oldExpirationTime.plus(Tld.get("tld").getAutoRenewGracePeriodLength()))
            .setBillingRecurrence(domain.getAutorenewBillingEvent()));
  }

  @Test
  void testSuccess_nonpremiumPriceRenewalBehavior_carriesOver() throws Exception {
    PremiumList pl =
        PremiumListDao.save(
            new PremiumList.Builder()
                .setCurrency(USD)
                .setName("tld")
                .setLabelsToPrices(ImmutableMap.of("example", new BigDecimal("67.89")))
                .build());
    persistResource(Tld.get("tld").asBuilder().setPremiumList(pl).build());
    domain = loadByEntity(domain);
    persistResource(
        loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
            .build());
    setEppInput("domain_transfer_approve_wildcard.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime now = clock.nowUtc();
    runFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
    domain = reloadResourceByForeignKey();
    DomainHistory acceptHistory =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE, DomainHistory.class);
    assertBillingEventsForResource(
        domain,
        new BillingEvent.Builder()
            .setBillingTime(now.plusDays(5))
            .setEventTime(now)
            .setRegistrarId("NewRegistrar")
            .setCost(Money.of(USD, new BigDecimal("11.00")))
            .setDomainHistory(acceptHistory)
            .setReason(Reason.TRANSFER)
            .setPeriodYears(1)
            .setTargetId("example.tld")
            .build(),
        getGainingClientAutorenewEvent()
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
            .setDomainHistory(acceptHistory)
            .build(),
        getLosingClientAutorenewEvent()
            .asBuilder()
            .setRecurrenceEndTime(now)
            .setRenewalPriceBehavior(RenewalPriceBehavior.NONPREMIUM)
            .build());
  }

  @Test
  void testSuccess_specifiedPriceRenewalBehavior_carriesOver() throws Exception {
    PremiumList pl =
        PremiumListDao.save(
            new PremiumList.Builder()
                .setCurrency(USD)
                .setName("tld")
                .setLabelsToPrices(ImmutableMap.of("example", new BigDecimal("67.89")))
                .build());
    persistResource(Tld.get("tld").asBuilder().setPremiumList(pl).build());
    domain = loadByEntity(domain);
    persistResource(
        loadByKey(domain.getAutorenewBillingEvent())
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("43.10")))
            .build());
    setEppInput("domain_transfer_approve_wildcard.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime now = clock.nowUtc();
    runFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
    domain = reloadResourceByForeignKey();
    DomainHistory acceptHistory =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE, DomainHistory.class);
    assertBillingEventsForResource(
        domain,
        new BillingEvent.Builder()
            .setBillingTime(now.plusDays(5))
            .setEventTime(now)
            .setRegistrarId("NewRegistrar")
            .setCost(Money.of(USD, new BigDecimal("43.10")))
            .setDomainHistory(acceptHistory)
            .setReason(Reason.TRANSFER)
            .setPeriodYears(1)
            .setTargetId("example.tld")
            .build(),
        getGainingClientAutorenewEvent()
            .asBuilder()
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("43.10")))
            .setDomainHistory(acceptHistory)
            .build(),
        getLosingClientAutorenewEvent()
            .asBuilder()
            .setRecurrenceEndTime(now)
            .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
            .setRenewalPrice(Money.of(USD, new BigDecimal("43.10")))
            .build());
  }

  @Test
  void testFailure_badContactPassword() {
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
            () -> doFailingTest("domain_transfer_approve_contact_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_badDomainPassword() {
    // Change the domain's password so it does not match the password in the file.
    persistResource(
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("badpassword")))
            .build());
    EppException thrown =
        assertThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("domain_transfer_approve_domain_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_neverBeenTransferred() {
    changeTransferStatus(null);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientApproved() {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientRejected() {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientCancelled() {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverApproved() {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverCancelled() {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gainingClient() {
    setRegistrarIdForFlow("NewRegistrar");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unrelatedClient() {
    setRegistrarIdForFlow("ClientZ");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("domain_transfer_approve.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_deletedDomain() throws Exception {
    persistResource(domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("domain_transfer_approve.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_nonexistentDomain() throws Exception {
    deleteTestDomain(domain, clock.nowUtc());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("domain_transfer_approve.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_notAuthorizedForTld() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown =
        assertThrows(
            NotAuthorizedForTldException.class,
            () ->
                doSuccessfulTest(
                    "tld", "domain_transfer_approve.xml", "domain_transfer_approve_response.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile("domain_transfer_approve_response.xml"));
  }

  // NB: No need to test pending delete status since pending transfers will get cancelled upon
  // entering pending delete phase. So it's already handled in that test case.

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-transfer-approve");
    assertTldsFieldLogged("tld");
  }

  private void setUpGracePeriodDurations() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAutomaticTransferLength(Duration.standardDays(2))
            .setTransferGracePeriodLength(Duration.standardDays(3))
            .build());
  }

  @Test
  void testIcannTransactionRecord_noRecordsToCancel() throws Exception {
    setUpGracePeriodDurations();
    clock.advanceOneMilli();
    runFlow();
    DomainHistory persistedEntry =
        (DomainHistory) getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE);
    // We should only produce a transfer success record for (now + transfer grace period)
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld", clock.nowUtc().plusDays(3), TRANSFER_SUCCESSFUL, 1));
  }

  @Test
  void testIcannTransactionRecord_cancelsPreviousRecords() throws Exception {
    clock.advanceOneMilli();
    setUpGracePeriodDurations();
    DomainTransactionRecord previousSuccessRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), TRANSFER_SUCCESSFUL, 1);
    // We only want to cancel TRANSFER_SUCCESSFUL records
    DomainTransactionRecord notCancellableRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), NET_ADDS_4_YR, 5);
    persistResource(
        new DomainHistory.Builder()
            .setType(DOMAIN_TRANSFER_REQUEST)
            .setDomain(domain)
            .setModificationTime(clock.nowUtc().minusDays(4))
            .setRegistrarId("TheRegistrar")
            .setDomainTransactionRecords(
                ImmutableSet.of(previousSuccessRecord, notCancellableRecord))
            .build());
    runFlow();
    DomainHistory persistedEntry =
        (DomainHistory) getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_APPROVE);
    // We should only produce cancellation records for the original reporting date (now + 1 day) and
    // success records for the new reporting date (now + transferGracePeriod=3 days)
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(
            previousSuccessRecord.asBuilder().setReportAmount(-1).build(),
            DomainTransactionRecord.create(
                "tld", clock.nowUtc().plusDays(3), TRANSFER_SUCCESSFUL, 1));
  }

  @Test
  void testSuccess_superuserExtension_transferPeriodZero() throws Exception {
    domain = reloadResourceByForeignKey();
    DomainTransferData.Builder transferDataBuilder = domain.getTransferData().asBuilder();
    persistResource(
        domain
            .asBuilder()
            .setTransferData(
                transferDataBuilder.setTransferPeriod(Period.create(0, Unit.YEARS)).build())
            .build());
    clock.advanceOneMilli();
    runSuccessfulFlowWithAssertions(
        "tld",
        "domain_transfer_approve.xml",
        "domain_transfer_approve_response_zero_period.xml",
        domain.getRegistrationExpirationTime());
    assertHistoryEntriesDoNotContainTransferBillingEventsOrGracePeriods();
  }

  @Test
  void testSuccess_superuserExtension_transferPeriodZero_autorenewGraceActive() throws Exception {
    Domain domain = reloadResourceByForeignKey();
    VKey<BillingRecurrence> existingAutorenewEvent = domain.getAutorenewBillingEvent();
    // Set domain to have auto-renewed just before the transfer request, so that it will have an
    // active autorenew grace period spanning the entire transfer window.
    DateTime autorenewTime = clock.nowUtc().minusDays(1);
    DateTime expirationTime = autorenewTime.plusYears(1);
    DomainTransferData.Builder transferDataBuilder = domain.getTransferData().asBuilder();
    domain =
        persistResource(
            domain
                .asBuilder()
                .setTransferData(
                    transferDataBuilder.setTransferPeriod(Period.create(0, Unit.YEARS)).build())
                .setRegistrationExpirationTime(expirationTime)
                .addGracePeriod(
                    GracePeriod.createForRecurrence(
                        GracePeriodStatus.AUTO_RENEW,
                        domain.getRepoId(),
                        autorenewTime.plus(Tld.get("tld").getAutoRenewGracePeriodLength()),
                        "TheRegistrar",
                        existingAutorenewEvent))
                .build());
    clock.advanceOneMilli();
    runSuccessfulFlowWithAssertions(
        "tld",
        "domain_transfer_approve.xml",
        "domain_transfer_approve_response_zero_period_autorenew_grace.xml",
        domain.getRegistrationExpirationTime());
    assertHistoryEntriesDoNotContainTransferBillingEventsOrGracePeriods();
  }

  @Test
  void testSuccess_allocationToken() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("example.tld")
            .build());
    doSuccessfulTest(
        "tld",
        "domain_transfer_approve_allocation_token.xml",
        "domain_transfer_approve_response.xml");
  }

  @Test
  void testFailure_invalidAllocationToken() throws Exception {
    setEppInput("domain_transfer_approve_allocation_token.xml");
    EppException thrown = assertThrows(NonexistentAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_allocationTokenIsForDifferentName() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("otherdomain.tld")
            .build());
    setEppInput("domain_transfer_approve_allocation_token.xml");
    assertAboutEppExceptions()
        .that(
            assertThrows(
                AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException.class,
                this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testFailure_allocationTokenNotActive() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(60), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_transfer_approve_allocation_token.xml");
    EppException thrown = assertThrows(AllocationTokenNotInPromotionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_allocationTokenNotValidForRegistrar() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedRegistrarIds(ImmutableSet.of("someClientId"))
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_transfer_approve_allocation_token.xml");
    EppException thrown =
        assertThrows(AllocationTokenNotValidForRegistrarException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_allocationTokenNotValidForTld() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedTlds(ImmutableSet.of("example"))
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_transfer_approve_allocation_token.xml");
    runFlowAssertResponse(loadFile("domain_transfer_approve_response.xml"));
  }

  @Test
  void testFailure_allocationTokenAlreadyRedeemed() throws Exception {
    Domain domain = DatabaseHelper.newDomain("foo.tld");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 505L);
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryId(historyEntryId)
            .build());
    setEppInput("domain_transfer_approve_allocation_token.xml");
    EppException thrown =
        assertThrows(AlreadyRedeemedAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
