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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.AddRemoveSameValueException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.ResourceFlowUtils.StatusNotClientSettableException;
import google.registry.flows.contact.ContactFlowUtils.BadInternationalizedPostalInfoException;
import google.registry.flows.contact.ContactFlowUtils.DeclineContactDisclosureFieldDisallowedPolicyException;
import google.registry.flows.exceptions.ContactsProhibitedException;
import google.registry.flows.exceptions.ResourceHasClientUpdateProhibitedException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.common.FeatureFlag;
import google.registry.model.contact.Contact;
import google.registry.model.contact.ContactAddress;
import google.registry.model.contact.PostalInfo;
import google.registry.model.contact.PostalInfo.Type;
import google.registry.model.eppcommon.StatusValue;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ContactUpdateFlow}. */
class ContactUpdateFlowTest extends ResourceFlowTestCase<ContactUpdateFlow, Contact> {

  ContactUpdateFlowTest() {
    setEppInput("contact_update.xml");
  }

  private void doSuccessfulTest() throws Exception {
    clock.advanceOneMilli();
    assertMutatingFlow(true);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Contact contact = reloadResourceByForeignKey();
    // Check that the contact was updated. This value came from the xml.
    assertAboutContacts()
        .that(contact)
        .hasAuthInfoPwd("2fooBAR")
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasNoXml();
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
    persistActiveContact(getUniqueIdFromCommand());
    dryRunFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    doSuccessfulTest();
  }

  @Test
  void testFailure_minimumDatasetPhase2_cannotUpdateContacts() throws Exception {
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
  void testSuccess_updatingInternationalizedPostalInfoDeletesLocalized() throws Exception {
    Contact contact =
        persistResource(
            newContact(getUniqueIdFromCommand())
                .asBuilder()
                .setLocalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(Type.LOCALIZED)
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("111 8th Ave", "4th Floor"))
                                .setCity("New York")
                                .setState("NY")
                                .setZip("10011")
                                .setCountryCode("US")
                                .build())
                        .build())
                .build());
    clock.advanceOneMilli();
    // The test xml updates the internationalized postal info and should therefore implicitly delete
    // the localized one since they are treated as a pair for update purposes.
    assertAboutContacts().that(contact)
        .hasNonNullLocalizedPostalInfo().and()
        .hasNullInternationalizedPostalInfo();

    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertAboutContacts().that(reloadResourceByForeignKey())
        .hasNullLocalizedPostalInfo().and()
        .hasInternationalizedPostalInfo(new PostalInfo.Builder()
            .setType(Type.INTERNATIONALIZED)
            .setAddress(new ContactAddress.Builder()
                .setStreet(ImmutableList.of("124 Example Dr.", "Suite 200"))
                .setCity("Dulles")
                .setState("VA")
                .setZip("20166-6503")
                .setCountryCode("US")
                .build())
            .build());
  }

  @Test
  void testSuccess_updatingLocalizedPostalInfoDeletesInternationalized() throws Exception {
    setEppInput("contact_update_localized.xml");
    Contact contact =
        persistResource(
            newContact(getUniqueIdFromCommand())
                .asBuilder()
                .setInternationalizedPostalInfo(
                    new PostalInfo.Builder()
                        .setType(Type.INTERNATIONALIZED)
                        .setAddress(
                            new ContactAddress.Builder()
                                .setStreet(ImmutableList.of("111 8th Ave", "4th Floor"))
                                .setCity("New York")
                                .setState("NY")
                                .setZip("10011")
                                .setCountryCode("US")
                                .build())
                        .build())
                .build());
    clock.advanceOneMilli();
    // The test xml updates the localized postal info and should therefore implicitly delete
    // the internationalized one since they are treated as a pair for update purposes.
    assertAboutContacts().that(contact)
        .hasNonNullInternationalizedPostalInfo().and()
        .hasNullLocalizedPostalInfo();

    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertAboutContacts().that(reloadResourceByForeignKey())
        .hasNullInternationalizedPostalInfo().and()
        .hasLocalizedPostalInfo(new PostalInfo.Builder()
            .setType(Type.LOCALIZED)
            .setAddress(new ContactAddress.Builder()
                .setStreet(ImmutableList.of("124 Example Dr.", "Suite 200"))
                .setCity("Dulles")
                .setState("VA")
                .setZip("20166-6503")
                .setCountryCode("US")
                .build())
            .build());
  }

  @Test
  void testSuccess_partialPostalInfoUpdate() throws Exception {
    setEppInput("contact_update_partial_postalinfo.xml");
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setLocalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(Type.LOCALIZED)
                    .setName("A. Person")
                    .setOrg("Company Inc.")
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 4th st", "5th Floor"))
                            .setCity("City")
                            .setState("AB")
                            .setZip("12345")
                            .setCountryCode("US")
                            .build())
                    .build())
            .build());
    clock.advanceOneMilli();
    // The test xml updates the address of the postal info and should leave the name untouched.
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertAboutContacts().that(reloadResourceByForeignKey()).hasLocalizedPostalInfo(
        new PostalInfo.Builder()
            .setType(Type.LOCALIZED)
            .setName("A. Person")
            .setOrg("Company Inc.")
            .setAddress(new ContactAddress.Builder()
                .setStreet(ImmutableList.of("456 5th st"))
                .setCity("Place")
                .setState("CD")
                .setZip("54321")
                .setCountryCode("US")
                .build())
            .build());
  }

  @Test
  void testSuccess_updateOnePostalInfo_touchOtherPostalInfoPreservesIt() throws Exception {
    setEppInput("contact_update_partial_postalinfo_preserve_int.xml");
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setLocalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(Type.LOCALIZED)
                    .setName("A. Person")
                    .setOrg("Company Inc.")
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("123 4th st", "5th Floor"))
                            .setCity("City")
                            .setState("AB")
                            .setZip("12345")
                            .setCountryCode("US")
                            .build())
                    .build())
            .setInternationalizedPostalInfo(
                new PostalInfo.Builder()
                    .setType(Type.INTERNATIONALIZED)
                    .setName("B. Person")
                    .setOrg("Company Co.")
                    .setAddress(
                        new ContactAddress.Builder()
                            .setStreet(ImmutableList.of("100 200th Dr.", "6th Floor"))
                            .setCity("Town")
                            .setState("CD")
                            .setZip("67890")
                            .setCountryCode("US")
                            .build())
                    .build())
            .build());
    clock.advanceOneMilli();
    // The test xml updates the address of the localized postal info. It also sets the name of the
    // internationalized postal info to the same value it previously had, which causes it to be
    // preserved. If the xml had not mentioned the internationalized one at all it would have been
    // deleted.
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    assertAboutContacts().that(reloadResourceByForeignKey())
        .hasLocalizedPostalInfo(
            new PostalInfo.Builder()
                .setType(Type.LOCALIZED)
                .setName("A. Person")
                .setOrg("Company Inc.")
                .setAddress(new ContactAddress.Builder()
                    .setStreet(ImmutableList.of("456 5th st"))
                    .setCity("Place")
                    .setState("CD")
                    .setZip("54321")
                    .setCountryCode("US")
                    .build())
                .build())
        .and()
        .hasInternationalizedPostalInfo(
            new PostalInfo.Builder()
                .setType(Type.INTERNATIONALIZED)
                .setName("B. Person")
                .setOrg("Company Co.")
                .setAddress(new ContactAddress.Builder()
                    .setStreet(ImmutableList.of("100 200th Dr.", "6th Floor"))
                    .setCity("Town")
                    .setState("CD")
                    .setZip("67890")
                    .setCountryCode("US")
                    .build())
                .build());
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
  void testFailure_statusValueNotClientSettable() throws Exception {
    setEppInput("contact_update_prohibited_status.xml");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown = assertThrows(StatusNotClientSettableException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserStatusValueNotClientSettable() throws Exception {
    setEppInput("contact_update_prohibited_status.xml");
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
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
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess_clientUpdateProhibited_removed() throws Exception {
    setEppInput("contact_update_remove_client_update_prohibited.xml");
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    doSuccessfulTest();
    assertAboutContacts()
        .that(reloadResourceByForeignKey())
        .doesNotHaveStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED);
  }

  @Test
  void testSuccess_superuserClientUpdateProhibited_notRemoved() throws Exception {
    setEppInput("contact_update_prohibited_status.xml");
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    clock.advanceOneMilli();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
    assertAboutContacts()
        .that(reloadResourceByForeignKey())
        .hasStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED)
        .and()
        .hasStatusValue(StatusValue.SERVER_DELETE_PROHIBITED);
  }

  @Test
  void testFailure_clientUpdateProhibited_notRemoved() throws Exception {
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.CLIENT_UPDATE_PROHIBITED))
            .build());
    EppException thrown =
        assertThrows(ResourceHasClientUpdateProhibitedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_serverUpdateProhibited() throws Exception {
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_UPDATE_PROHIBITED))
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverUpdateProhibited");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_pendingDeleteProhibited() throws Exception {
    persistResource(
        newContact(getUniqueIdFromCommand())
            .asBuilder()
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_nonAsciiInLocAddress() throws Exception {
    setEppInput("contact_update_hebrew_loc.xml");
    persistActiveContact(getUniqueIdFromCommand());
    doSuccessfulTest();
  }

  @Test
  void testFailure_nonAsciiInIntAddress() throws Exception {
    setEppInput("contact_update_hebrew_int.xml");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown =
        assertThrows(BadInternationalizedPostalInfoException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_declineDisclosure() throws Exception {
    setEppInput("contact_update_decline_disclosure.xml");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown =
        assertThrows(DeclineContactDisclosureFieldDisallowedPolicyException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_addRemoveSameValue() throws Exception {
    setEppInput("contact_update_add_remove_same.xml");
    persistActiveContact(getUniqueIdFromCommand());
    EppException thrown = assertThrows(AddRemoveSameValueException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistActiveContact(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-cont-update");
  }
}
