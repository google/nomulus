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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.testing.TestDataHelper.loadFile;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import google.registry.config.RegistryEnvironment;
import google.registry.export.sheet.SyncRegistrarsSheetAction;
import google.registry.model.registrar.Registrar;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.testing.CertificateSamples;
import google.registry.testing.SystemPropertyExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.CidrAddressBlock;
import google.registry.util.EmailMessage;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Tests for {@link RegistrarSettingsAction}. */
class RegistrarSettingsActionTest extends RegistrarSettingsActionTestCase {

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  @Test
  void testSuccess_updateRegistrarInfo_andSendsNotificationEmail() throws Exception {
    String expectedEmailBody = loadFile(getClass(), "update_registrar_email.txt");
    // This update changes some values on the admin contact and makes it a tech contact as well,
    // while deleting the existing tech contact (by omission).
    action.handleJsonRequest(readJsonFromFile("update_registrar.json", getLastUpdateTime()));
    verify(rsp, never()).setStatus(anyInt());
    verifyNotificationEmailsSent();
    ArgumentCaptor<EmailMessage> contentCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(contentCaptor.capture());
    assertThat(contentCaptor.getValue().body()).isEqualTo(expectedEmailBody);
    assertTasksEnqueued("sheet", new TaskMatcher()
        .url(SyncRegistrarsSheetAction.PATH)
        .method("GET")
        .header("Host", "backend.hostname"));
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
  }

  @Test
  void testFailure_updateRegistrarInfo_duplicateContacts() {
    Map<String, Object> response = action.handleJsonRequest(
        readJsonFromFile("update_registrar_duplicate_contacts.json", getLastUpdateTime()));
    assertThat(response).containsExactly(
        "status", "ERROR",
        "results", ImmutableList.of(),
        "message",
        "One email address (etphonehome@example.com) cannot be used for multiple contacts");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: ContactRequirementException");
  }

  /**
   * Make sure that if someone spoofs a different registrar (they don't have access to), we fail.
   * Also relevant if the person's privilege were revoked after the page load.
   */
  @Test
  void testFailure_readRegistrarInfo_notAuthorized() {
    setUserWithoutAccess();
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of("id", CLIENT_ID));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "TestUserId doesn't have access to registrar TheRegistrar");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "read", "[]", "ERROR: ForbiddenException");
  }

  /** This is the default read test for the registrar settings actions. */
  @Test
  void testSuccess_readRegistrarInfo_authorizedReadWrite() {
    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of("id", CLIENT_ID));
    assertThat(response)
        .containsExactly(
            "status", "SUCCESS",
            "message", "Success",
            "results", ImmutableList.of(loadRegistrar(CLIENT_ID).toJsonMap()));
    assertMetric(CLIENT_ID, "read", "[OWNER]", "SUCCESS");
  }

  @Test
  void testUpdate_emptyJsonObject_errorLastUpdateTimeFieldRequired() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.remove("lastUpdateTime");

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", args));

    assertThat(response).containsExactly(
        "status", "ERROR",
        "field", "lastUpdateTime",
        "results", ImmutableList.of(),
        "message", "This field is required.");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testUpdate_noEmail_errorEmailFieldRequired() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.remove("emailAddress");

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", args));

    assertThat(response).containsExactly(
        "status", "ERROR",
        "field", "emailAddress",
        "results", ImmutableList.of(),
        "message", "This field is required.");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testFailure_updateRegistrarInfo_notAuthorized() {
    setUserWithoutAccess();

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", ImmutableMap.of("lastUpdateTime", getLastUpdateTime())));

    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "TestUserId doesn't have access to registrar TheRegistrar");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[]", "ERROR: ForbiddenException");
  }

  @Test
  void testUpdate_badEmail_errorEmailField() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("emailAddress", "lolcat");

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", args));

    assertThat(response).containsExactly(
        "status", "ERROR",
        "field", "emailAddress",
        "results", ImmutableList.of(),
        "message", "Please enter a valid email address.");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testPost_nonParsableTime_getsAngry() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("lastUpdateTime", "cookies");

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", args));

    assertThat(response).containsExactly(
        "status", "ERROR",
        "field", "lastUpdateTime",
        "results", ImmutableList.of(),
        "message", "Not a valid ISO date-time string.");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  @Test
  void testPost_nonAsciiCharacters_getsAngry() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("emailAddress", "ヘ(◕。◕ヘ)@example.com");

    Map<String, Object> response = action.handleJsonRequest(ImmutableMap.of(
        "op", "update",
        "id", CLIENT_ID,
        "args", args));

    assertThat(response).containsExactly(
        "status", "ERROR",
        "field", "emailAddress",
        "results", ImmutableList.of(),
        "message", "Please only use ASCII-US characters.");
    assertNoTasksEnqueued("sheet");
    assertMetric(CLIENT_ID, "update", "[OWNER]", "ERROR: FormFieldException");
  }

  /**
   * Makes sure a field update succeeds IF AND ONLY IF we have the "correct" role.
   *
   * Each of the Registrar fields can be changed only by a single {@link Role}. We make sure that
   * trying to update the field works if the user has the "correct" role, but fails if it doesn't.
   */
  private <T> void doTestUpdate(
      Role correctRole,
      Function<Registrar, T> getter,
      T newValue,
      BiFunction<Registrar.Builder, T, Registrar.Builder> setter) {
    doTestUpdateWithCorrectRole_succeeds(correctRole, getter, newValue, setter);
    doTestUpdateWithoutCorrectRole_fails(correctRole, getter, newValue, setter);
  }

  private <T> void doTestUpdateWithCorrectRole_succeeds(
      Role role,
      Function<Registrar, T> getter,
      T newValue,
      BiFunction<Registrar.Builder, T, Registrar.Builder> setter) {
    // Set the user to only have the current role for this registrar
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of(CLIENT_ID, role));
    // Load the registrar as it is currently in datastore, and make sure the requested update will
    // actually change it
    Registrar registrar = loadRegistrar(CLIENT_ID);
    assertThat(getter.apply(registrar)).isNotEqualTo(newValue);

    // Call the action to perform the requested update, then load the "updated" registrar and
    // return the "datastore" registrar to its original state (for the next iteration)
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", setter.apply(registrar.asBuilder(), newValue).build().toJsonMap()));
    Registrar updatedRegistrar = loadRegistrar(CLIENT_ID);
    persistResource(registrar);

    // This role is authorized to perform this change, make sure the change succeeded
    // We got a success result:
    assertThat(response).containsEntry("status", "SUCCESS");
    assertThat(response).containsEntry("results", ImmutableList.of(updatedRegistrar.toJsonMap()));
    // The updatedRegistrar had its value changed:
    // (We check it separately from the next assert to get better error message on failure)
    assertThat(getter.apply(updatedRegistrar)).isEqualTo(newValue);
    // ONLY that value changed:
    assertThat(updatedRegistrar).isEqualTo(setter.apply(registrar.asBuilder(), newValue).build());
    // We increased the correct metric
    assertMetric(CLIENT_ID, "update", String.format("[%s]", role), "SUCCESS");
  }

  private <T> void doTestUpdateWithoutCorrectRole_fails(
      Role correctRole,
      Function<Registrar, T> getter,
      T newValue,
      BiFunction<Registrar.Builder, T, Registrar.Builder> setter) {
    // Set the user to only have the current role for this registrar
    ImmutableSet<Role> allExceptCorrectRoles =
        Sets.difference(ImmutableSet.copyOf(Role.values()), ImmutableSet.of(correctRole))
            .immutableCopy();
    ImmutableSetMultimap<String, Role> accessMap =
        new ImmutableSetMultimap.Builder<String, Role>()
            .putAll(CLIENT_ID, allExceptCorrectRoles)
            .build();
    action.registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(accessMap);
    // Load the registrar as it is currently in datastore, and make sure the requested update will
    // actually change it
    Registrar registrar = loadRegistrar(CLIENT_ID);
    assertThat(getter.apply(registrar)).isNotEqualTo(newValue);

    // Call the action to perform the requested update, then load the "updated" registrar and
    // returned the "datastore" registrar to its original state (for the next iteration)
    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op",
                "update",
                "id",
                CLIENT_ID,
                "args",
                setter.apply(registrar.asBuilder(), newValue).build().toJsonMap()));
    Registrar updatedRegistrar = loadRegistrar(CLIENT_ID);
    persistResource(registrar);

    // This role is NOT authorized to perform this change, make sure the change failed
    // We got an error response with the correct message
    assertThat(response).containsEntry("status", "ERROR");
    assertThat(response).containsEntry("results", ImmutableList.of());
    assertThat(response.get("message").toString())
        .containsMatch("Unauthorized: only .* can change fields .*");
    // Make sure the value hasn't changed
    // (We check it separately from the next assert to get better error message on failure)
    assertThat(getter.apply(updatedRegistrar)).isEqualTo(getter.apply(registrar));
    // Make sure no other values have changed either
    assertThat(updatedRegistrar).isEqualTo(registrar);
    // We increased the correct metric
    assertMetric(
        CLIENT_ID, "update", allExceptCorrectRoles.toString(), "ERROR: ForbiddenException");
  }

  @Test
  void testUpdate_whoisServer() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getWhoisServer,
        "new-whois.example",
        Registrar.Builder::setWhoisServer);
  }

  @Test
  void testUpdate_phoneNumber() {
    doTestUpdate(
        Role.OWNER, Registrar::getPhoneNumber, "+1.2345678900", Registrar.Builder::setPhoneNumber);
  }

  @Test
  void testUpdate_faxNumber() {
    doTestUpdate(
        Role.OWNER, Registrar::getFaxNumber, "+1.2345678900", Registrar.Builder::setFaxNumber);
  }

  @Test
  void testUpdate_url() {
    doTestUpdate(Role.OWNER, Registrar::getUrl, "new-url.example", Registrar.Builder::setUrl);
  }

  @Test
  void testUpdate_ipAddressAllowList() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getIpAddressAllowList,
        ImmutableList.of(CidrAddressBlock.create("1.1.1.0/24")),
        Registrar.Builder::setIpAddressAllowList);
  }

  @Test
  void testUpdate_clientCertificate() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getClientCertificate,
        CertificateSamples.SAMPLE_CERT,
        (builder, s) -> builder.setClientCertificate(s, clock.nowUtc()));
  }

  @Test
  void testUpdate_failoverClientCertificate() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getFailoverClientCertificate,
        CertificateSamples.SAMPLE_CERT,
        (builder, s) -> builder.setFailoverClientCertificate(s, clock.nowUtc()));
  }

  @Test
  void testUpdate_allowedTlds() {
    doTestUpdate(
        Role.ADMIN,
        Registrar::getAllowedTlds,
        ImmutableSet.of("newtld", "currenttld"),
        (builder, s) -> builder.setAllowedTlds(s));
  }

  @Test
  void testUpdate_allowedTlds_failedWhenNoWhoisAbuseContactExists() {
    setUserAdmin();
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("allowedTlds", ImmutableList.of("newtld", "currenttld"));

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", args));

    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Cannot add allowed TLDs if there is no WHOIS abuse contact set.");
    assertMetric(CLIENT_ID, "update", "[ADMIN]", "ERROR: IllegalArgumentException");
    assertNoTasksEnqueued("sheet");
  }

  @Test
  void testUpdate_allowedTlds_failedWhenTldNotExist() {
    setUserAdmin();
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("allowedTlds", ImmutableList.of("invalidtld", "currenttld"));

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", args));

    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "TLDs do not exist: invalidtld");
    assertMetric(CLIENT_ID, "update", "[ADMIN]", "ERROR: IllegalArgumentException");
    assertNoTasksEnqueued("sheet");
  }

  @Test
  void testUpdate_allowedTlds_failedWhenRemovingTld() {
    setUserAdmin();
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("allowedTlds", ImmutableList.of("newtld"));

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", args));

    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Can't remove allowed TLDs using the console.");
    assertMetric(CLIENT_ID, "update", "[ADMIN]", "ERROR: ForbiddenException");
    assertNoTasksEnqueued("sheet");
  }

  @Test
  void testUpdate_allowedTlds_noChange_successWhenUserIsNotAdmin() {
    Map<String, Object> args = Maps.newHashMap(loadRegistrar(CLIENT_ID).toJsonMap());
    args.put("allowedTlds", ImmutableList.of("currenttld"));

    Map<String, Object> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "op", "update",
                "id", CLIENT_ID,
                "args", args));

    assertThat(response)
        .containsExactly(
            "status", "SUCCESS",
            "message", "Saved TheRegistrar",
            "results", ImmutableList.of(loadRegistrar(CLIENT_ID).toJsonMap()));
    assertMetric(CLIENT_ID, "update", "[OWNER]", "SUCCESS");
  }

  @Test
  void testUpdate_localizedAddress_city() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getLocalizedAddress,
        loadRegistrar(CLIENT_ID).getLocalizedAddress().asBuilder().setCity("newCity").build(),
        Registrar.Builder::setLocalizedAddress);
  }

  @Test
  void testUpdate_localizedAddress_countryCode() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getLocalizedAddress,
        loadRegistrar(CLIENT_ID).getLocalizedAddress().asBuilder().setCountryCode("GB").build(),
        Registrar.Builder::setLocalizedAddress);
  }

  @Test
  void testUpdate_localizedAddress_state() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getLocalizedAddress,
        loadRegistrar(CLIENT_ID).getLocalizedAddress().asBuilder().setState("NJ").build(),
        Registrar.Builder::setLocalizedAddress);
  }

  @Test
  void testUpdate_localizedAddress_street() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getLocalizedAddress,
        loadRegistrar(CLIENT_ID)
            .getLocalizedAddress()
            .asBuilder()
            .setStreet(ImmutableList.of("new street"))
            .build(),
        Registrar.Builder::setLocalizedAddress);
  }

  @Test
  void testUpdate_localizedAddress_zip() {
    doTestUpdate(
        Role.OWNER,
        Registrar::getLocalizedAddress,
        loadRegistrar(CLIENT_ID).getLocalizedAddress().asBuilder().setZip("new zip").build(),
        Registrar.Builder::setLocalizedAddress);
  }

  private static String getLastUpdateTime() {
    return loadRegistrar(CLIENT_ID).getLastUpdateTime().toString();
  }

  static Map<String, Object> readJsonFromFile(String filename, String lastUpdateTime) {
    String contents =
        loadFile(
            RegistrarSettingsActionTestCase.class,
            filename,
            ImmutableMap.of("LAST_UPDATE_TIME", lastUpdateTime));
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> json = (Map<String, Object>) JSONValue.parseWithException(contents);
      return json;
    } catch (ParseException ex) {
      throw new RuntimeException(ex);
    }
  }
}
