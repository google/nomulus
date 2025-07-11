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
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_PROHIBITED;
import static google.registry.model.common.FeatureFlag.FeatureStatus.ACTIVE;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.ContactSubject.assertAboutContacts;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDeletedContact;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.contact.ContactFlowUtils.BadInternationalizedPostalInfoException;
import google.registry.flows.contact.ContactFlowUtils.DeclineContactDisclosureFieldDisallowedPolicyException;
import google.registry.flows.exceptions.ContactsProhibitedException;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.common.FeatureFlag;
import google.registry.model.contact.Contact;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactCreateFlow}. */
class ContactCreateFlowTest extends ResourceFlowTestCase<ContactCreateFlow, Contact> {

  ContactCreateFlowTest() {
    setEppInput("contact_create.xml");
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z"));
  }

  private void doSuccessfulTest() throws Exception {
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("contact_create_response.xml"));
    // Check that the contact was created and persisted with a history entry.
    Contact contact = reloadResourceByForeignKey();
    assertAboutContacts().that(contact).hasOnlyOneHistoryEntryWhich().hasNoXml();
    assertNoBillingEvents();
    assertLastHistoryContainsResource(contact);
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    dryRunFlowAssertResponse(loadFile("contact_create_response.xml"));
  }

  @Test
  void testSuccess_neverExisted() throws Exception {
    doSuccessfulTest();
  }

  @Test
  void testSuccess_existedButWasDeleted() throws Exception {
    persistDeletedContact(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
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

  @Test
  void testFailure_minimumDatasetPhase2_cannotCreateContacts() throws Exception {
    persistResource(
        new FeatureFlag.Builder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_PROHIBITED)
            .setStatusMap(
                ImmutableSortedMap.of(START_OF_TIME, INACTIVE, clock.nowUtc().minusDays(5), ACTIVE))
            .build());
    EppException thrown = assertThrows(ContactsProhibitedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_resourceContention() throws Exception {
    String targetId = getUniqueIdFromCommand();
    persistResource(
        newContact(targetId)
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    ResourceCreateContentionException thrown =
        assertThrows(ResourceCreateContentionException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Object with given ID (%s) already exists", targetId));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_nonAsciiInLocAddress() throws Exception {
    setEppInput("contact_create_hebrew_loc.xml");
    doSuccessfulTest();
  }

  @Test
  void testFailure_nonAsciiInIntAddress() {
    setEppInput("contact_create_hebrew_int.xml");
    EppException thrown =
        assertThrows(BadInternationalizedPostalInfoException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_declineDisclosure() {
    setEppInput("contact_create_decline_disclosure.xml");
    EppException thrown =
        assertThrows(DeclineContactDisclosureFieldDisallowedPolicyException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-create");
  }
}
