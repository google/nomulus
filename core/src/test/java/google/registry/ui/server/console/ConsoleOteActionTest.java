// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.OteAccountBuilderTest.verifyIapPermission;
import static google.registry.model.OteAccountBuilderTest.verifyUser;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2_HASH;
import static google.registry.testing.DatabaseHelper.loadExistingUser;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.reflect.TypeToken;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.OteStatsTestHelper;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeResponse;
import google.registry.tools.IamClient;
import google.registry.ui.server.console.ConsoleOteAction.OteCreateData;
import google.registry.ui.server.console.settings.SecurityAction;
import google.registry.util.CidrAddressBlock;
import google.registry.util.StringGenerator;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleOteActionTest extends ConsoleActionBaseTestCase {

  private final IamClient iamClient = mock(IamClient.class);
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  private StringGenerator passwordGenerator =
      new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");

  private User user =
      new User.Builder()
          .setEmailAddress("marla.singer@example.com")
          .setUserRoles(new UserRoles())
          .build();

  @BeforeEach
  void beforeEach() throws Exception {
    persistPremiumList("default_sandbox_list", USD, "sandbox,USD 1000");
  }

  @Test
  void testFailure_missingGlobalPermission() {
    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.POST,
            authResult,
            "testRegistrarId",
            Optional.of("someRandomString"),
            Optional.of(new OteCreateData("testRegistrarId", "tescontact@registry.example")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_invalidParamsNoRegistrarId() {
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.POST,
            authResult,
            "testRegistrarId",
            Optional.of("someRandomString"),
            Optional.of(new OteCreateData("", "test@email.com")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("OT&E create body is invalid");
  }

  @Test
  void testFailure_invalidParamsNoEmail() {
    user =
        user.asBuilder()
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();
    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.POST,
            authResult,
            "testRegistrarId",
            Optional.of("someRandomString"),
            Optional.of(new OteCreateData("testRegistrarId", "")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("OT&E create body is invalid");
  }

  @Test
  void testSuccess_oteCreated() {
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.POST,
            authResult,
            "theregistrar",
            Optional.of("someRandomString@email.test"),
            Optional.of(new OteCreateData("theregistrar", "contact@registry.example")));
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    action.run();
    Map<String, Object> obsResponse = GSON.fromJson(response.getPayload(), new TypeToken<>() {});
    assertThat(
            ImmutableMap.of(
                "theregistrar-1", "theregistrar-sunrise",
                "theregistrar-3", "theregistrar-ga",
                "theregistrar-4", "theregistrar-ga",
                "theregistrar-5", "theregistrar-eap",
                "password", "abcdefghijklmnop"))
        .containsExactlyEntriesIn(obsResponse);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyUser("theregistrar-1", "contact@registry.example");
    verifyUser("theregistrar-3", "contact@registry.example");
    verifyUser("theregistrar-4", "contact@registry.example");
    verifyUser("theregistrar-5", "contact@registry.example");
    verifyIapPermission(
        "contact@registry.example",
        Optional.of("someRandomString@email.test"),
        Optional.of("consoleIapServiceId"),
        cloudTasksHelper,
        iamClient);
  }

  @Test
  void testSuccess_oteCreated_userCanConfigureSecurityAndCheckStatus() throws IOException {
    AuthResult fteAuthResult = AuthResult.createUser(fteUser);
    ConsoleOteAction createOteAction =
        createAction(
            Action.Method.POST,
            fteAuthResult,
            "theregistrar",
            Optional.of("someRandomString@email.test"),
            Optional.of(new OteCreateData("theregistrar", "contact@registry.example")));
    createOteAction.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    createOteAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    User createdOteUser = loadExistingUser("contact@registry.example");
    AuthResult oteUserAuth = AuthResult.createUser(createdOteUser);

    // Newly provisioned OT&E TECH_CONTACT user can check their own OT&E status
    ConsoleOteAction statusAction =
        createAction(
            Action.Method.GET, oteUserAuth, "theregistrar-1", Optional.empty(), Optional.empty());
    statusAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    // Newly provisioned OT&E TECH_CONTACT user cannot mint new OT&E accounts
    ConsoleOteAction forbiddenCreateAction =
        createAction(
            Action.Method.POST,
            oteUserAuth,
            "anotherreg",
            Optional.empty(),
            Optional.of(new OteCreateData("anotherreg", "other@registry.example")));
    forbiddenCreateAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);

    // Newly provisioned OT&E TECH_CONTACT user can self-service upload mTLS cert & IP allowlist
    ConsoleApiParams securityParams = ConsoleApiParamsUtils.createFake(oteUserAuth);
    when(securityParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    FakeResponse securityResponse = (FakeResponse) securityParams.response();
    CertificateChecker certificateChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_INSTANT, 20825, Instant.parse("2020-09-01T00:00:00Z"), 398),
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            oteUserAuth, "adminRegistrarId", Optional.empty(), () -> null);
    String securityJson =
        GSON.toJson(
            ImmutableMap.of(
                "registrarId",
                "theregistrar-1",
                "clientCertificate",
                SAMPLE_CERT2,
                "ipAddressAllowList",
                ImmutableList.of("192.168.1.1/32")));
    when(securityParams.request().getInputStream())
        .thenReturn(createServletInputStream(securityJson));
    byte[] payloadBytes = RequestModule.providePayloadAsBytes(securityParams.request());
    String payloadString =
        RequestModule.providePayloadAsString(payloadBytes, securityParams.request());
    SecurityAction securityAction =
        new SecurityAction(
            securityParams,
            certificateChecker,
            registrarAccessor,
            "theregistrar-1",
            RequestModule.provideJsonBody(payloadString, GSON));
    securityAction.run();
    assertThat(securityResponse.getStatus()).isEqualTo(SC_OK);
    Registrar persistedRegistrar = loadRegistrar("theregistrar-1");
    assertThat(persistedRegistrar.getClientCertificateHash()).hasValue(SAMPLE_CERT2_HASH);
    assertThat(persistedRegistrar.getIpAddressAllowList())
        .containsExactly(CidrAddressBlock.create("192.168.1.1/32"));
  }

  @Test
  void testFailure_getStatusMissingPermission() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("theregistrar");
    User viewerUser =
        user.asBuilder()
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("theregistrar-1", RegistrarRole.ACCOUNT_MANAGER))
                    .build())
            .build();
    ConsoleOteAction viewerAction =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(viewerUser),
            "theregistrar-1",
            Optional.empty(),
            Optional.empty());
    viewerAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);

    User otherRegistrarUser =
        user.asBuilder()
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("otherregistrar-1", RegistrarRole.TECH_CONTACT))
                    .build())
            .build();
    ConsoleOteAction crossRegistrarAction =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(otherRegistrarUser),
            "theregistrar-1",
            Optional.empty(),
            Optional.empty());
    crossRegistrarAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_getStatusNonOteRegistrar() {
    ConsoleOteAction action =
        createAction(
            Action.Method.GET,
            AuthResult.createUser(fteUser),
            "TheRegistrar",
            Optional.empty(),
            Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Registrar with ID TheRegistrar is not an OT&E registrar");
  }

  @Test
  void testFail_statusMissingParam() {
    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.GET,
            authResult,
            "",
            Optional.of("someRandomString@email.test"),
            Optional.of(new OteCreateData("theregistrar", "contact@registry.example")));
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Missing registrarId parameter");
  }

  @Test
  void testSuccess_finishedOte() throws Exception {
    OteStatsTestHelper.setupCompleteOte("theregistrar");
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.GET, authResult, "theregistrar-1", Optional.empty(), Optional.empty());
    action.run();

    List<Map<String, ?>> responseMaps = GSON.fromJson(response.getPayload(), new TypeToken<>() {});
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertTrue(
        responseMaps.stream().allMatch(status -> Boolean.TRUE.equals(status.get("completed"))));
  }

  @Test
  void testSuccess_unfinishedOte() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("theregistrar");
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.GET, authResult, "theregistrar-1", Optional.empty(), Optional.empty());
    action.run();

    List<Map<String, ?>> responseMaps = GSON.fromJson(response.getPayload(), new TypeToken<>() {});
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            responseMaps.stream()
                .filter(status -> Boolean.FALSE.equals(status.get("completed")))
                .map(status -> status.get("description"))
                .collect(Collectors.toList()))
        .containsExactlyElementsIn(
            ImmutableList.of("domain creates idn", "domain restores", "host deletes"));
  }

  private ConsoleOteAction createAction(
      Action.Method method,
      AuthResult authResult,
      String registrarId,
      Optional<String> maybeGroupEmailAddress,
      Optional<OteCreateData> oteCreateData) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    response = (FakeResponse) consoleApiParams.response();
    return new ConsoleOteAction(
        consoleApiParams,
        iamClient,
        passwordGenerator,
        oteCreateData,
        maybeGroupEmailAddress,
        Optional.of("consoleIapServiceId"),
        registrarId);
  }
}
