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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.ContactResourceSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.newContactResource;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.flows.EppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.contact.ContactFlowUtils.BadInternationalizedPostalInfoException;
import google.registry.flows.contact.ContactFlowUtils.DeclineContactDisclosureFieldDisallowedPolicyException;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.contact.ContactResource;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.TestOfyAndSql;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ContactCreateFlow}. */
@DualDatabaseTest
class ContactCreateFlowTest extends ResourceFlowTestCase<ContactCreateFlow, ContactResource> {

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  ContactCreateFlowTest() {
    setEppInput("contact_create.xml");
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z"));
  }

  private void doSuccessfulTest() throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(loadFile("contact_create_response.xml"));
    // Check that the contact was created and persisted with a history entry.
    ContactResource contact = reloadResourceByForeignKey();
    assertAboutContacts().that(contact).hasOnlyOneHistoryEntryWhich().hasNoXml();
    assertNoBillingEvents();
    if (tm().isOfy()) {
      assertEppResourceIndexEntityFor(contact);
    }
    assertLastHistoryContainsResource(contact);
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    dryRunFlowAssertResponse(loadFile("contact_create_response.xml"));
  }

  @TestOfyAndSql
  void testSuccess_neverExisted() throws Exception {
    doSuccessfulTest();
  }

  @TestOfyAndSql
  void testSuccess_existedButWasDeleted() throws Exception {
    persistDeletedContact(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @TestOfyAndSql
  void testFailure_alreadyExists() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    ResourceAlreadyExistsForThisClientException thrown =
        assertThrows(ResourceAlreadyExistsForThisClientException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_resourceContention() throws Exception {
    String targetId = getUniqueIdFromCommand();
    persistResource(
        newContactResource(targetId)
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar")
            .build());
    ResourceCreateContentionException thrown =
        assertThrows(ResourceCreateContentionException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Object with given ID (%s) already exists", targetId));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_nonAsciiInLocAddress() throws Exception {
    setEppInput("contact_create_hebrew_loc.xml");
    doSuccessfulTest();
  }

  @TestOfyAndSql
  void testFailure_nonAsciiInIntAddress() {
    setEppInput("contact_create_hebrew_int.xml");
    EppException thrown =
        assertThrows(BadInternationalizedPostalInfoException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_declineDisclosure() {
    setEppInput("contact_create_decline_disclosure.xml");
    EppException thrown =
        assertThrows(DeclineContactDisclosureFieldDisallowedPolicyException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-create");
  }
}
