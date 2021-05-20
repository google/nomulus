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
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.NET_RENEWS_3_YR;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_NACKED;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REJECT;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessage;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainTransferRejectFlow}. */
@DualDatabaseTest
class DomainTransferRejectFlowTest
    extends DomainTransferFlowTestCase<DomainTransferRejectFlow, DomainBase> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithCompare(clock);

  @BeforeEach
  void setUp() {
    setEppInput("domain_transfer_reject.xml");
    setClientIdForFlow("TheRegistrar");
    setupDomainWithPendingTransfer("example", "tld");
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Make sure the implicit billing event is there; it will be deleted by the flow.
    // We also expect to see autorenew events for the gaining and losing registrars.
    assertBillingEvents(
        getBillingEventForImplicitTransfer(),
        getGainingClientAutorenewEvent(),
        getLosingClientAutorenewEvent());
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    DateTime originalExpirationTime = domain.getRegistrationExpirationTime();
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    TransferData originalTransferData = domain.getTransferData();
    runFlowAssertResponse(loadFile(expectedXmlFilename));
    // Transfer should have been rejected. Verify correct fields were set.
    domain = reloadResourceByForeignKey();
    assertTransferFailed(domain, TransferStatus.CLIENT_REJECTED, originalTransferData);
    assertAboutDomains()
        .that(domain)
        .hasRegistrationExpirationTime(originalExpirationTime)
        .and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc())
        .and()
        .hasOneHistoryEntryEachOfTypes(
            DOMAIN_CREATE, DOMAIN_TRANSFER_REQUEST, DOMAIN_TRANSFER_REJECT)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("TheRegistrar");
    final HistoryEntry historyEntryTransferRejected =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REJECT);
    assertAboutHistoryEntries().that(historyEntryTransferRejected).hasOtherClientId("NewRegistrar");
    assertLastHistoryContainsResource(domain);
    // The only billing event left should be the original autorenew event, now reopened.
    assertBillingEvents(
        getLosingClientAutorenewEvent().asBuilder().setRecurrenceEndTime(END_OF_TIME).build());
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).isEmpty();
    // The poll message in the future to the gaining registrar should be gone too, but there
    // should be one at the current time to the gaining registrar.
    PollMessage gainingPollMessage = getOnlyPollMessage("NewRegistrar");
    assertThat(gainingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(
            gainingPollMessage
                .getResponseData()
                .stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.CLIENT_REJECTED);
    PendingActionNotificationResponse panData =
        gainingPollMessage
            .getResponseData()
            .stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
    // The original grace periods should remain untouched.
    assertThat(domain.getGracePeriods()).isEqualTo(originalGracePeriods);
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    doSuccessfulTest("domain_transfer_reject.xml", "domain_transfer_reject_response.xml");
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    setEppInput("domain_transfer_reject.xml");
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    dryRunFlowAssertResponse(loadFile("domain_transfer_reject_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest(
        "domain_transfer_reject_domain_authinfo.xml", "domain_transfer_reject_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_contactAuthInfo() throws Exception {
    doSuccessfulTest(
        "domain_transfer_reject_contact_authinfo.xml", "domain_transfer_reject_response.xml");
  }

  @TestOfyAndSql
  void testFailure_notAuthorizedForTld() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown =
        assertThrows(
            NotAuthorizedForTldException.class,
            () ->
                doSuccessfulTest(
                    "domain_transfer_reject.xml", "domain_transfer_reject_response.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("domain_transfer_reject_response.xml"));
  }

  @TestOfyAndSql
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
            () -> doFailingTest("domain_transfer_reject_contact_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_badDomainPassword() {
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
            () -> doFailingTest("domain_transfer_reject_domain_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_neverBeenTransferred() {
    changeTransferStatus(null);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientApproved() {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientRejected() {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientCancelled() {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_serverApproved() {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_serverCancelled() {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gainingClient() {
    setClientIdForFlow("NewRegistrar");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unrelatedClient() {
    setClientIdForFlow("ClientZ");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_deletedDomain() throws Exception {
    domain =
        persistResource(domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_nonexistentDomain() throws Exception {
    persistDomainAsDeleted(domain, clock.nowUtc());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("domain_transfer_reject.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  // NB: No need to test pending delete status since pending transfers will get cancelled upon
  // entering pending delete phase. So it's already handled in that test case.

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-transfer-reject");
    assertTldsFieldLogged("tld");
  }

  private void setUpGracePeriodDurations() {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAutomaticTransferLength(Duration.standardDays(2))
            .setTransferGracePeriodLength(Duration.standardDays(3))
            .build());
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_noRecordsToCancel() throws Exception {
    setUpGracePeriodDurations();
    runFlow();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REJECT);
    // We should only produce transfer nacked records, reported now
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(DomainTransactionRecord.create("tld", clock.nowUtc(), TRANSFER_NACKED, 1));
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_cancelsPreviousRecords() throws Exception {
    setUpGracePeriodDurations();
    DomainTransactionRecord previousSuccessRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), TRANSFER_SUCCESSFUL, 1);
    // We only want to cancel TRANSFER_SUCCESSFUL records
    DomainTransactionRecord notCancellableRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), NET_RENEWS_3_YR, 5);
    persistResource(
        new DomainHistory.Builder()
            .setType(DOMAIN_TRANSFER_REQUEST)
            .setDomain(domain)
            .setModificationTime(clock.nowUtc().minusDays(4))
            .setClientId("TheRegistrar")
            .setDomainTransactionRecords(
                ImmutableSet.of(previousSuccessRecord, notCancellableRecord))
            .build());
    runFlow();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_REJECT);
    // We should only produce cancellation records for the original success records and nack records
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(
            previousSuccessRecord.asBuilder().setReportAmount(-1).build(),
            DomainTransactionRecord.create("tld", clock.nowUtc(), TRANSFER_NACKED, 1));
  }
}
