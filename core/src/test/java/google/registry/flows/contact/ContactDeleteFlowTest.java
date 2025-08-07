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
import static google.registry.testing.ContactSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistContactWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.model.contact.Contact;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DatabaseHelper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactDeleteFlow}. */
class ContactDeleteFlowTest extends ResourceFlowTestCase<ContactDeleteFlow, Contact> {

  @BeforeEach
  void initFlowTest() {
    setEppInput("contact_delete.xml");
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    dryRunFlowAssertResponse(loadFile("contact_delete_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testSuccess_pendingTransfer_sql() throws Exception {
    DateTime transferRequestTime = clock.nowUtc().minusDays(3);
    TransferData oldTransferData =
        persistContactWithPendingTransfer(
                persistActiveContact(getUniqueIdFromCommand()),
                transferRequestTime,
                transferRequestTime.plus(Tld.DEFAULT_TRANSFER_GRACE_PERIOD),
                clock.nowUtc())
            .getTransferData();
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response.xml"));
    assertSqlDeleteSuccess(Type.CONTACT_DELETE, Type.CONTACT_TRANSFER_REQUEST);
    Contact softDeletedContact = reloadResourceByForeignKey(clock.nowUtc().minusMillis(1));
    assertThat(softDeletedContact.getTransferData())
        .isEqualTo(
            oldTransferData
                .copyConstantFieldsToBuilder()
                .setTransferStatus(TransferStatus.SERVER_CANCELLED)
                .setPendingTransferExpirationTime(softDeletedContact.getDeletionTime())
                .build());
    PollMessage gainingPollMessage =
        Iterables.getOnlyElement(getPollMessages("NewRegistrar", clock.nowUtc()));
    assertThat(gainingPollMessage.getEventTime()).isEqualTo(clock.nowUtc());
    assertThat(
            gainingPollMessage.getResponseData().stream()
                .filter(TransferResponse.class::isInstance)
                .map(TransferResponse.class::cast)
                .collect(onlyElement())
                .getTransferStatus())
        .isEqualTo(TransferStatus.SERVER_CANCELLED);
    PendingActionNotificationResponse panData =
        gainingPollMessage.getResponseData().stream()
            .filter(PendingActionNotificationResponse.class::isInstance)
            .map(PendingActionNotificationResponse.class::cast)
            .collect(onlyElement());
    assertThat(panData.getTrid())
        .isEqualTo(Trid.create("transferClient-trid", "transferServer-trid"));
    assertThat(panData.getActionResult()).isFalse();
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("contact_delete_no_cltrid.xml");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response_no_cltrid.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedContact(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_existedButWasClientDeleteProhibited() throws Exception {
    doFailingStatusTest(
        StatusValue.CLIENT_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @Test
  void testFailure_existedButWasServerDeleteProhibited() throws Exception {
    doFailingStatusTest(
        StatusValue.SERVER_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @Test
  void testFailure_existedButWasPendingDelete() throws Exception {
    doFailingStatusTest(
        StatusValue.PENDING_DELETE, ResourceStatusProhibitsOperationException.class);
  }

  private void doFailingStatusTest(StatusValue statusValue, Class<? extends EppException> exception)
      throws Exception {
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(statusValue))
            .build());
    EppException thrown = assertThrows(exception, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(statusValue.getXmlName());
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_clientDeleteProhibited_superuser() throws Exception {
    persistResource(
        persistActiveContact(getUniqueIdFromCommand())
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_DELETE_PROHIBITED)
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("contact_delete_response.xml"));
  }

  @Test
  void testSuccess_serverDeleteProhibited_superuser() throws Exception {
    persistResource(
        persistActiveContact(getUniqueIdFromCommand())
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED)
            .build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("contact_delete_response.xml"));
  }

  @Test
  void testFailure_pendingDelete_superuser() throws Exception {
    persistResource(
        persistActiveContact(getUniqueIdFromCommand())
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    assertAboutEppExceptions()
        .that(
            assertThrows(
                ResourceStatusProhibitsOperationException.class,
                () -> runFlow(CommitMode.LIVE, UserPrivileges.SUPERUSER)))
        .marshalsToXml();
  }

  @Test
  void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("contact_delete_response.xml"));
    assertSqlDeleteSuccess();
  }

  @Test
  void testFailure_failfastWhenLinkedToDomain() throws Exception {
    createTld("tld");
    persistResource(
        DatabaseHelper.newDomain("example.tld", persistActiveContact(getUniqueIdFromCommand())));
    EppException thrown = assertThrows(ResourceToDeleteIsReferencedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-delete");
  }

  private void assertSqlDeleteSuccess(HistoryEntry.Type... historyEntryTypes) throws Exception {
    assertThat(reloadResourceByForeignKey()).isNull();
    assertAboutContacts()
        .that(reloadResourceByForeignKey(clock.nowUtc().minusMillis(1)))
        .isNotActiveAt(clock.nowUtc())
        .and()
        .hasNullLocalizedPostalInfo()
        .and()
        .hasNullInternationalizedPostalInfo()
        .and()
        .hasNullEmailAddress()
        .and()
        .hasNullVoiceNumber()
        .and()
        .hasNullFaxNumber()
        .and()
        .hasExactlyStatusValues(StatusValue.OK)
        .and()
        .hasOneHistoryEntryEachOfTypes(historyEntryTypes);
    assertNoBillingEvents();
  }

  private void assertSqlDeleteSuccess() throws Exception {
    assertSqlDeleteSuccess(Type.CONTACT_DELETE);
  }
}
