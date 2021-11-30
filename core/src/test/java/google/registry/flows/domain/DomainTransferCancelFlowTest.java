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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.RESTORED_DOMAINS;
import static google.registry.model.reporting.DomainTransactionRecord.TransactionReportField.TRANSFER_SUCCESSFUL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_CANCEL;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createPollMessageForImplicitTransfer;
import static google.registry.testing.DatabaseHelper.deleteTestDomain;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ReadOnlyModeEppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.flows.exceptions.NotTransferInitiatorException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferResponse.DomainTransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestOfyOnly;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainTransferCancelFlow}. */
@DualDatabaseTest
class DomainTransferCancelFlowTest
    extends DomainTransferFlowTestCase<DomainTransferCancelFlow, DomainBase> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  @BeforeEach
  void beforeEach() {
    setEppInput("domain_transfer_cancel.xml");
    setRegistrarIdForFlow("NewRegistrar");
    setupDomainWithPendingTransfer("example", "tld");
  }

  private void doSuccessfulTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);

    // Replace the ROID in the xml file with the one generated in our test.
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Make sure the implicit billing event is there; it will be deleted by the flow.
    // We also expect to see autorenew events for the gaining and losing registrars.
    assertBillingEvents(
        getBillingEventForImplicitTransfer(),
        getGainingClientAutorenewEvent(),
        getLosingClientAutorenewEvent());
    // We should see poll messages for the implicit ack case going to both registrars, and an
    // autorenew poll message for the new registrar.
    HistoryEntry historyEntryDomainTransferRequest =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_TRANSFER_REQUEST);
    assertPollMessages(
        "NewRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("NewRegistrar")
            .setEventTime(EXTENDED_REGISTRATION_EXPIRATION_TIME)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainTransferRequest)
            .build(),
        createPollMessageForImplicitTransfer(
            domain,
            historyEntryDomainTransferRequest,
            "NewRegistrar",
            TRANSFER_REQUEST_TIME,
            TRANSFER_EXPIRATION_TIME,
            EXTENDED_REGISTRATION_EXPIRATION_TIME));
    assertPollMessages(
        "TheRegistrar",
        createPollMessageForImplicitTransfer(
            domain,
            historyEntryDomainTransferRequest,
            "TheRegistrar",
            TRANSFER_REQUEST_TIME,
            TRANSFER_EXPIRATION_TIME,
            EXTENDED_REGISTRATION_EXPIRATION_TIME));
    clock.advanceOneMilli();

    // Setup done; run the test.
    assertTransactionalFlow(true);
    DateTime originalExpirationTime = domain.getRegistrationExpirationTime();
    ImmutableSet<GracePeriod> originalGracePeriods = domain.getGracePeriods();
    DomainTransferData originalTransferData = domain.getTransferData();
    runFlowAssertResponse(loadFile("domain_transfer_cancel_response.xml"));

    // Transfer should have been cancelled. Verify correct fields were set.
    domain = reloadResourceByForeignKey();
    assertLastHistoryContainsResource(domain);
    assertTransferFailed(domain, TransferStatus.CLIENT_CANCELLED, originalTransferData);
    assertAboutDomains()
        .that(domain)
        .hasRegistrationExpirationTime(originalExpirationTime)
        .and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc());
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(
            DOMAIN_CREATE, DOMAIN_TRANSFER_REQUEST, DOMAIN_TRANSFER_CANCEL)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("NewRegistrar");
    final HistoryEntry historyEntryTransferCancel =
        getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_CANCEL);
    assertAboutHistoryEntries()
        .that(historyEntryTransferCancel)
        .hasRegistrarId("NewRegistrar")
        .and()
        .hasOtherClientId("TheRegistrar");
    // The only billing event left should be the original autorenew event, now reopened.
    assertBillingEvents(
        getLosingClientAutorenewEvent().asBuilder().setRecurrenceEndTime(END_OF_TIME).build());
    // The poll message (in the future) to the gaining registrar for implicit ack should be gone.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1))).isEmpty();
    // The poll message in the future to the losing registrar should be gone too, but there should
    // be two at the current time to the losing registrar - one for the original autorenew event,
    // and another for the transfer being cancelled.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(originalExpirationTime)
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(getOnlyHistoryEntryOfType(domain, DOMAIN_CREATE))
            .build(),
        new PollMessage.OneTime.Builder()
            .setRegistrarId("TheRegistrar")
            .setEventTime(clock.nowUtc())
            .setResponseData(
                ImmutableList.of(
                    new DomainTransferResponse.Builder()
                        .setFullyQualifiedDomainName(getUniqueIdFromCommand())
                        .setTransferStatus(TransferStatus.CLIENT_CANCELLED)
                        .setTransferRequestTime(TRANSFER_REQUEST_TIME)
                        .setGainingRegistrarId("NewRegistrar")
                        .setLosingRegistrarId("TheRegistrar")
                        .setPendingTransferExpirationTime(clock.nowUtc())
                        .build()))
            .setMsg("Transfer cancelled.")
            .setParent(getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_CANCEL))
            .build());

    // The original grace periods should remain untouched.
    assertThat(domain.getGracePeriods()).containsExactlyElementsIn(originalGracePeriods);
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
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    setEppInput("domain_transfer_cancel.xml");
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    dryRunFlowAssertResponse(loadFile("domain_transfer_cancel_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    doSuccessfulTest("domain_transfer_cancel.xml");
  }

  @TestOfyAndSql
  void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest("domain_transfer_cancel_domain_authinfo.xml");
  }

  @TestOfyAndSql
  void testSuccess_contactAuthInfo() throws Exception {
    doSuccessfulTest("domain_transfer_cancel_contact_authinfo.xml");
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
            () -> doFailingTest("domain_transfer_cancel_contact_authinfo.xml"));
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
            () -> doFailingTest("domain_transfer_cancel_domain_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_neverBeenTransferred() {
    changeTransferStatus(null);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientApproved() {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientRejected() {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_clientCancelled() {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_serverApproved() {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_serverCancelled() {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_sponsoringClient() {
    setRegistrarIdForFlow("TheRegistrar");
    EppException thrown =
        assertThrows(
            NotTransferInitiatorException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unrelatedClient() {
    setRegistrarIdForFlow("ClientZ");
    EppException thrown =
        assertThrows(
            NotTransferInitiatorException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_deletedDomain() throws Exception {
    domain =
        persistResource(domain.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_nonexistentDomain() throws Exception {
    deleteTestDomain(domain, clock.nowUtc());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("domain_transfer_cancel.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_notAuthorizedForTld() {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown =
        assertThrows(
            NotAuthorizedForTldException.class,
            () -> doSuccessfulTest("domain_transfer_cancel.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("NewRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("domain_transfer_cancel_response.xml"));
  }

  // NB: No need to test pending delete status since pending transfers will get cancelled upon
  // entering pending delete phase. So it's already handled in that test case.

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-transfer-cancel");
    assertTldsFieldLogged("tld");
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_noRecordsToCancel() throws Exception {
    clock.advanceOneMilli();
    runFlow();
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_CANCEL);
    // No cancellation records should be produced
    assertThat(persistedEntry.getDomainTransactionRecords()).isEmpty();
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_cancelsPreviousRecords() throws Exception {
    clock.advanceOneMilli();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setAutomaticTransferLength(Duration.standardDays(2))
            .setTransferGracePeriodLength(Duration.standardDays(3))
            .build());
    DomainTransactionRecord previousSuccessRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), TRANSFER_SUCCESSFUL, 1);
    // We only want to cancel TRANSFER_SUCCESSFUL records
    DomainTransactionRecord notCancellableRecord =
        DomainTransactionRecord.create("tld", clock.nowUtc().plusDays(1), RESTORED_DOMAINS, 5);
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
    HistoryEntry persistedEntry = getOnlyHistoryEntryOfType(domain, DOMAIN_TRANSFER_CANCEL);
    // We should only produce a cancellation record for the original transfer success
    assertThat(persistedEntry.getDomainTransactionRecords())
        .containsExactly(previousSuccessRecord.asBuilder().setReportAmount(-1).build());
  }

  @TestOfyOnly
  void testModification_duringReadOnlyPhase() {
    DatabaseHelper.setMigrationScheduleToDatastorePrimaryReadOnly(clock);
    EppException thrown = assertThrows(ReadOnlyModeEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    DatabaseHelper.removeDatabaseMigrationSchedule();
  }
}
