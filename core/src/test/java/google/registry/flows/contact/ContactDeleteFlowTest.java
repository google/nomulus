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
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistDeletedContact;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.flows.exceptions.ResourceToDeleteIsReferencedException;
import google.registry.model.contact.ContactResource;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.reporting.HistoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactDeleteFlow}. */
class ContactDeleteFlowTest extends ResourceFlowTestCase<ContactDeleteFlow, ContactResource> {

  @BeforeEach
  void initFlowTest() {
    setEppInput("contact_delete.xml");
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
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response.xml"));
    ContactResource deletedContact = reloadResourceByForeignKey();
    assertAboutContacts().that(deletedContact).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedContact, "TheRegistrar", Trid.create("ABC-12345", "server-trid"), false);
    assertAboutContacts()
        .that(deletedContact)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.CONTACT_PENDING_DELETE);
    assertNoBillingEvents();
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("contact_delete_no_cltrid.xml");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("contact_delete_response_no_cltrid.xml"));
    ContactResource deletedContact = reloadResourceByForeignKey();
    assertAboutContacts().that(deletedContact).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedContact, "TheRegistrar", Trid.create(null, "server-trid"), false);
    assertAboutContacts()
        .that(deletedContact)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.CONTACT_PENDING_DELETE);
    assertNoBillingEvents();
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
        newContactResource(getUniqueIdFromCommand()).asBuilder()
            .setStatusValues(ImmutableSet.of(statusValue))
            .build());
    EppException thrown = assertThrows(exception, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(statusValue.getXmlName());
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("contact_delete_response.xml"));
    ContactResource deletedContact = reloadResourceByForeignKey();
    assertAboutContacts().that(deletedContact).hasStatusValue(StatusValue.PENDING_DELETE);
    assertAsyncDeletionTaskEnqueued(
        deletedContact, "NewRegistrar", Trid.create("ABC-12345", "server-trid"), true);
    assertAboutContacts()
        .that(deletedContact)
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.CONTACT_PENDING_DELETE);
    assertNoBillingEvents();
  }

  @Test
  void testFailure_failfastWhenLinkedToDomain() throws Exception {
    createTld("tld");
    persistResource(
        newDomainBase("example.tld", persistActiveContact(getUniqueIdFromCommand())));
    EppException thrown = assertThrows(ResourceToDeleteIsReferencedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_failfastWhenLinkedToApplication() throws Exception {
    createTld("tld");
    persistResource(
        newDomainBase("example.tld", persistActiveContact(getUniqueIdFromCommand())));
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
}
