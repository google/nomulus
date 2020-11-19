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

package google.registry.flows.contact;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.deleteResource;
import static google.registry.testing.DatabaseHelper.getOnlyPollMessage;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.NotPendingTransferException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactTransferRejectFlow}. */
class ContactTransferRejectFlowTest
    extends ContactTransferFlowTestCase<ContactTransferRejectFlow, ContactResource> {

  @BeforeEach
  void setUp() {
    setEppInput("contact_transfer_reject.xml");
    setClientIdForFlow("TheRegistrar");
    setupContactWithPendingTransfer();
    clock.advanceOneMilli();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);
    // Look in the future and make sure the poll messages for implicit ack are there.
    assertThat(getPollMessages("NewRegistrar", clock.nowUtc().plusMonths(1)))
        .hasSize(1);
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)))
        .hasSize(1);

    // Setup done; run the test.
    contact = reloadResourceByForeignKey();
    TransferData originalTransferData = contact.getTransferData();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile(expectedXmlFilename));

    // Transfer should have failed. Verify correct fields were set.
    contact = reloadResourceByForeignKey();
    assertAboutContacts().that(contact)
        .hasCurrentSponsorClientId("TheRegistrar").and()
        .hasLastTransferTimeNotEqualTo(clock.nowUtc()).and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.CONTACT_TRANSFER_REQUEST,
            HistoryEntry.Type.CONTACT_TRANSFER_REJECT);
    assertThat(contact.getTransferData())
        .isEqualTo(
            originalTransferData.copyConstantFieldsToBuilder()
                .setTransferStatus(TransferStatus.CLIENT_REJECTED)
                .setPendingTransferExpirationTime(clock.nowUtc())
                .build());
    // The poll message (in the future) to the losing registrar for implicit ack should be gone.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1)))
        .isEmpty();
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
    assertNoBillingEvents();
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    // Setup done; run the test.
    assertTransactionalFlow(true);
    runFlow();
  }

  @Test
  void testDryRun() throws Exception {
    setEppInput("contact_transfer_reject.xml");
    dryRunFlowAssertResponse(loadFile("contact_transfer_reject_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    doSuccessfulTest("contact_transfer_reject.xml", "contact_transfer_reject_response.xml");
  }

  @Test
  void testSuccess_domainAuthInfo() throws Exception {
    doSuccessfulTest("contact_transfer_reject_with_authinfo.xml",
        "contact_transfer_reject_response.xml");
  }

  @Test
  void testFailure_badPassword() {
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
            () -> doFailingTest("contact_transfer_reject_with_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_neverBeenTransferred() {
    changeTransferStatus(null);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientApproved() {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientRejected() {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_clientCancelled() {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverApproved() {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverCancelled() {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    EppException thrown =
        assertThrows(
            NotPendingTransferException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gainingClient() {
    setClientIdForFlow("NewRegistrar");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unrelatedClient() {
    setClientIdForFlow("ClientZ");
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class, () -> doFailingTest("contact_transfer_reject.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_deletedContact() throws Exception {
    contact =
        persistResource(contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_reject.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_nonexistentContact() throws Exception {
    deleteResource(contact);
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class,
            () -> doFailingTest("contact_transfer_reject.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-transfer-reject");
  }
}
