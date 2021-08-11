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
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistContactWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.poll.PendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.tld.Registry;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferResponse;
import google.registry.model.transfer.TransferStatus;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.TestSqlOnly;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ContactDeleteFlow}. */
@DualDatabaseTest
class ContactDeleteFlowTest extends ResourceFlowTestCase<ContactDeleteFlow, ContactResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithCompare(clock);

  @BeforeEach
  void initFlowTest() {
    setEppInput("contact_delete.xml");
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    if (tm().isOfy()) {
      dryRunFlowAssertResponse(loadFile("contact_delete_response_pending.xml"));
    } else {
      dryRunFlowAssertResponse(loadFile("contact_delete_response.xml"));
    }
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    if (tm().isOfy()) {
      runFlowAssertResponse(loadFile("contact_delete_response_pending.xml"));
      assertOfyDeleteSuccess();
    } else {
      runFlowAssertResponse(loadFile("contact_delete_response.xml"));
      assertSqlDeleteSuccess();
    }
  }

  @TestSqlOnly
  void testSuccess_pendingTransfer_sql() throws Exception {
    DateTime transferRequestTime = clock.nowUtc().minusDays(3);
    TransferData oldTransferData =
        persistContactWithPendingTransfer(
                persistActiveContact(getUniqueIdFromCommand()),
                transferRequestTime,
                transferRequestTime.plus(Registry.DEFAULT_TRANSFER_GRACE_PERIOD),
                clock.nowUtc())
            .getTransferData();
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response.xml"));
    assertSqlDeleteSuccess(Type.CONTACT_DELETE, Type.CONTACT_TRANSFER_REQUEST);
    ContactResource softDeletedContact = reloadResourceByForeignKey(clock.nowUtc().minusMillis(1));
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

  @TestOfyAndSql
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("contact_delete_no_cltrid.xml");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    if (tm().isOfy()) {
      runFlowAssertResponse(loadFile("contact_delete_response_no_cltrid_pending.xml"));
      assertOfyDeleteSuccess("TheRegistrar", null, false);
    } else {
      runFlowAssertResponse(loadFile("contact_delete_response_no_cltrid.xml"));
      assertSqlDeleteSuccess();
    }
  }

  @TestOfyAndSql
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedContact(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_existedButWasClientDeleteProhibited() throws Exception {
    doFailingStatusTest(
        StatusValue.CLIENT_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @TestOfyAndSql
  void testFailure_existedButWasServerDeleteProhibited() throws Exception {
    doFailingStatusTest(
        StatusValue.SERVER_DELETE_PROHIBITED, ResourceStatusProhibitsOperationException.class);
  }

  @TestOfyAndSql
  void testFailure_existedButWasPendingDelete() throws Exception {
    doFailingStatusTest(
        StatusValue.PENDING_DELETE, ResourceStatusProhibitsOperationException.class);
  }

  private void doFailingStatusTest(StatusValue statusValue, Class<? extends EppException> exception)
      throws Exception {
    persistResource(
        newContactResource(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(statusValue))
            .build());
    EppException thrown = assertThrows(exception, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(statusValue.getXmlName());
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    if (tm().isOfy()) {
      runFlowAssertResponse(
          CommitMode.LIVE,
          UserPrivileges.SUPERUSER,
          loadFile("contact_delete_response_pending.xml"));
      assertOfyDeleteSuccess("NewRegistrar", "ABC-12345", true);
    } else {
      runFlowAssertResponse(
          CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("contact_delete_response.xml"));
      assertSqlDeleteSuccess();
    }
  }

  @TestOfyAndSql
  void testFailure_failfastWhenLinkedToDomain() throws Exception {
    createTld("tld");
    persistResource(newDomainBase("example.tld", persistActiveContact(getUniqueIdFromCommand())));
    EppException thrown = assertThrows(ResourceToDeleteIsReferencedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-delete");
  }

  private void assertOfyDeleteSuccess(String clientId, String clientTrid, boolean isSuperuser)
      throws Exception {
    ContactResource deletedContact = reloadResourceByForeignKey();
    assertAsyncDeletionTaskEnqueued(
        deletedContact, clientId, Trid.create(clientTrid, "server-trid"), isSuperuser);
    assertAboutContacts()
        .that(deletedContact)
        .hasStatusValue(StatusValue.PENDING_DELETE)
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(Type.CONTACT_PENDING_DELETE);
    assertNoBillingEvents();
    assertLastHistoryContainsResource(deletedContact);
  }

  private void assertOfyDeleteSuccess() throws Exception {
    assertOfyDeleteSuccess("TheRegistrar", "ABC-12345", false);
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
    assertNoTasksEnqueued(QUEUE_ASYNC_DELETE);
    assertNoBillingEvents();
  }

  private void assertSqlDeleteSuccess() throws Exception {
    assertSqlDeleteSuccess(Type.CONTACT_DELETE);
  }
}
