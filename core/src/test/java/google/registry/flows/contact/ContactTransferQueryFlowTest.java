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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.exceptions.NoTransferHistoryToQueryException;
import google.registry.flows.exceptions.NotAuthorizedToViewTransferException;
import google.registry.model.contact.ContactAuthInfo;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ContactTransferQueryFlow}. */
@DualDatabaseTest
class ContactTransferQueryFlowTest
    extends ContactTransferFlowTestCase<ContactTransferQueryFlow, ContactResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithCompare(clock);

  @BeforeEach
  void setUp() {
    setEppInput("contact_transfer_query.xml");
    clock.setTo(DateTime.parse("2000-06-10T22:00:00.0Z"));
    setClientIdForFlow("NewRegistrar");
    setupContactWithPendingTransfer();
  }

  private void doSuccessfulTest(String commandFilename, String expectedXmlFilename)
      throws Exception {
    setEppInput(commandFilename);
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertTransactionalFlow(false);
    runFlowAssertResponse(loadFile(expectedXmlFilename));
    assertAboutContacts().that(reloadResourceByForeignKey(clock.nowUtc().minusDays(1)))
        .hasOneHistoryEntryEachOfTypes(HistoryEntry.Type.CONTACT_TRANSFER_REQUEST);
    assertNoBillingEvents();
  }

  private void doFailingTest(String commandFilename) throws Exception {
    setEppInput(commandFilename);
    eppLoader.replaceAll("JD1234-REP", contact.getRepoId());
    // Setup done; run the test.
    assertTransactionalFlow(false);
    runFlow();
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    doSuccessfulTest("contact_transfer_query.xml", "contact_transfer_query_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_withContactRoid() throws Exception {
    doSuccessfulTest("contact_transfer_query_with_roid.xml", "contact_transfer_query_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_sponsoringClient() throws Exception {
    setClientIdForFlow("TheRegistrar");
    doSuccessfulTest("contact_transfer_query.xml", "contact_transfer_query_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_withAuthinfo() throws Exception {
    setClientIdForFlow("ClientZ");
    doSuccessfulTest("contact_transfer_query_with_authinfo.xml",
        "contact_transfer_query_response.xml");
  }

  @TestOfyAndSql
  void testSuccess_clientApproved() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_APPROVED);
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_client_approved.xml");
  }

  @TestOfyAndSql
  void testSuccess_clientRejected() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_REJECTED);
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_client_rejected.xml");
  }

  @TestOfyAndSql
  void testSuccess_clientCancelled() throws Exception {
    changeTransferStatus(TransferStatus.CLIENT_CANCELLED);
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_client_cancelled.xml");
  }

  @TestOfyAndSql
  void testSuccess_serverApproved() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_APPROVED);
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_server_approved.xml");
  }

  @TestOfyAndSql
  void testSuccess_serverCancelled() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_server_cancelled.xml");
  }

  @TestOfyAndSql
  void testFailure_pendingDeleteContact() throws Exception {
    changeTransferStatus(TransferStatus.SERVER_CANCELLED);
    contact = persistResource(
        contact.asBuilder().setDeletionTime(clock.nowUtc().plusDays(1)).build());
    doSuccessfulTest("contact_transfer_query.xml",
        "contact_transfer_query_response_server_cancelled.xml");
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
            () -> doFailingTest("contact_transfer_query_with_authinfo.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_badContactRoid() {
    // Set the contact to a different ROID, but don't persist it; this is just so the substitution
    // code above will write the wrong ROID into the file.
    contact = contact.asBuilder().setRepoId("DEADBEEF_TLD-ROID").build();
    EppException thrown =
        assertThrows(
            BadAuthInfoForResourceException.class,
            () -> doFailingTest("contact_transfer_query_with_roid.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_neverBeenTransferred() {
    changeTransferStatus(null);
    EppException thrown =
        assertThrows(
            NoTransferHistoryToQueryException.class,
            () -> doFailingTest("contact_transfer_query.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unrelatedClient() {
    setClientIdForFlow("ClientZ");
    EppException thrown =
        assertThrows(
            NotAuthorizedToViewTransferException.class,
            () -> doFailingTest("contact_transfer_query.xml"));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_deletedContact() throws Exception {
    contact =
        persistResource(contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("contact_transfer_query.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_nonexistentContact() throws Exception {
    persistResource(contact.asBuilder().setDeletionTime(clock.nowUtc().minusDays(1)).build());
    ResourceDoesNotExistException thrown =
        assertThrows(
            ResourceDoesNotExistException.class, () -> doFailingTest("contact_transfer_query.xml"));
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-transfer-query");
  }
}
