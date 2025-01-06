// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.OteStatsTestHelper;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.tools.IamClient;
import google.registry.ui.server.console.ConsoleOteAction.OteCreateData;
import google.registry.util.StringGenerator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.json.simple.JSONArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleOteActionTest {

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private static final Gson GSON = GsonUtils.provideGson();
  private final IamClient iamClient = mock(IamClient.class);
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();
  private FakeResponse response;
  private ConsoleApiParams consoleApiParams;

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
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_invalidParamsNoRegistrarId() {
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
            Optional.of(new OteCreateData("", "test@email.com")));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("OT&E create body is invalid");
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
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("OT&E create body is invalid");
  }

  @Test
  void testSuccess_oteCreated() {
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
            "theregistrar",
            Optional.of("someRandomString@email.test"),
            Optional.of(new OteCreateData("theregistrar", "contact@registry.example")));
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    action.run();
    String response = ((FakeResponse) consoleApiParams.response()).getPayload();
    var obsResponse = GSON.fromJson(response, Map.class);
    assertThat(
            ImmutableMap.of(
                "theregistrar-1", "theregistrar-sunrise",
                "theregistrar-3", "theregistrar-ga",
                "theregistrar-4", "theregistrar-ga",
                "theregistrar-5", "theregistrar-eap",
                "password", "abcdefghijklmnop"))
        .containsExactlyEntriesIn(obsResponse);
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    verifyIapPermission(
        "contact@registry.example",
        Optional.of("someRandomString@email.test"),
        cloudTasksHelper,
        iamClient);
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
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(((FakeResponse) consoleApiParams.response()).getPayload())
        .isEqualTo("Missing registrarId parameter");
  }

  @Test
  void testSuccess_finishedOte() throws Exception {
    OteStatsTestHelper.setupCompleteOte("theregistrar");
    user =
        user.asBuilder()
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();
    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.GET, authResult, "theregistrar-1", Optional.empty(), Optional.empty());
    action.run();

    List<Map<String, ?>> response =
        GSON.fromJson(((FakeResponse) consoleApiParams.response()).getPayload(), JSONArray.class);
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertTrue(response.stream().allMatch(status -> Boolean.TRUE.equals(status.get("completed"))));
  }

  @Test
  void testSuccess_unfinishedOte() throws Exception {
    OteStatsTestHelper.setupIncompleteOte("theregistrar");
    user =
        user.asBuilder()
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();
    AuthResult authResult = AuthResult.createUser(user);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    ConsoleOteAction action =
        createAction(
            Action.Method.GET, authResult, "theregistrar-1", Optional.empty(), Optional.empty());
    action.run();

    List<Map<String, ?>> response =
        GSON.fromJson(((FakeResponse) consoleApiParams.response()).getPayload(), JSONArray.class);
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    assertThat(
            response.stream()
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
    response = new FakeResponse();
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(method.toString());
    return new ConsoleOteAction(
        consoleApiParams,
        iamClient,
        registrarId,
        maybeGroupEmailAddress,
        passwordGenerator,
        oteCreateData);
  }
}
