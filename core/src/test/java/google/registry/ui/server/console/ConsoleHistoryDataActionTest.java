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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createAdminUser;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.ConsoleUpdateHistory.Type;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleHistoryDataActionTest extends ConsoleActionBaseTestCase {

  private static final Gson GSON = new Gson();
  private User adminUser;
  private User noPermissionUser;
  private ConsoleUpdateHistory historyItem1;
  private ConsoleUpdateHistory historyItem2;
  private ConsoleUpdateHistory historyItem3;

  @BeforeEach
  void beforeEach() {
    adminUser = createAdminUser("admin@example.com");
    noPermissionUser =
        new User.Builder()
            .setEmailAddress("no.perms@example.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                    .build())
            .build();

    historyItem1 =
        new ConsoleUpdateHistory.Builder()
            .setType(Type.REGISTRAR_UPDATE)
            .setActingUser(adminUser)
            .setDescription("TheRegistrar|Some change")
            .setModificationTime(DateTime.now())
            .setUrl("/test")
            .setMethod("POST")
            .build();

    historyItem2 =
        new ConsoleUpdateHistory.Builder()
            .setType(Type.REGISTRAR_UPDATE)
            .setActingUser(noPermissionUser)
            .setDescription("TheRegistrar|Another change")
            .setModificationTime(DateTime.now())
            .setUrl("/test")
            .setMethod("POST")
            .build();

    historyItem3 =
        new ConsoleUpdateHistory.Builder()
            .setType(Type.REGISTRAR_UPDATE)
            .setActingUser(adminUser)
            .setDescription("OtherRegistrar|Some change")
            .setModificationTime(DateTime.now())
            .setUrl("/test")
            .setMethod("POST")
            .build();

    DatabaseHelper.persistResources(
        ImmutableList.of(adminUser, noPermissionUser, historyItem1, historyItem2, historyItem3));
  }

  @Test
  void testSuccess_getByRegistrar() {
    ConsoleHistoryDataAction action =
        createAction(AuthResult.createUser(adminUser), "TheRegistrar", Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    List<Map<String, Object>> payload = GSON.fromJson(response.getPayload(), List.class);
    assertThat(payload.stream().map(record -> record.get("description")).collect(toImmutableList()))
        .containsExactly("TheRegistrar|Some change", "TheRegistrar|Another change");
  }

  @Test
  void testSuccess_getByUser() {
    ConsoleHistoryDataAction action =
        createAction(
            AuthResult.createUser(adminUser), "TheRegistrar", Optional.of("admin@example.com"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    List<Map<String, Object>> payload = GSON.fromJson(response.getPayload(), List.class);
    assertThat(payload.stream().map(record -> record.get("description")).collect(toImmutableList()))
        .containsExactly("TheRegistrar|Some change", "OtherRegistrar|Some change");
  }

  @Test
  void testSuccess_noResults() {
    ConsoleHistoryDataAction action =
        createAction(AuthResult.createUser(adminUser), "NoHistoryRegistrar", Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("[]");
  }

  @Test
  void testFailure_getByRegistrar_noPermission() {
    ConsoleHistoryDataAction action =
        createAction(AuthResult.createUser(noPermissionUser), "TheRegistrar", Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_getByUser_noPermission() {
    ConsoleHistoryDataAction action =
        createAction(
            AuthResult.createUser(noPermissionUser),
            "TheRegistrar",
            Optional.of("admin@example.com"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .contains("User doesn't have a permission to check audit activity by user");
  }

  @Test
  void testFailure_emptyRegistrarId() {
    ConsoleHistoryDataAction action =
        createAction(AuthResult.createUser(adminUser), "", Optional.empty());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).contains("Empty registrarId param");
  }

  private ConsoleHistoryDataAction createAction(
      AuthResult authResult, String registrarId, Optional<String> consoleUserEmail) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn("GET");
    response = (FakeResponse) consoleApiParams.response();
    return new ConsoleHistoryDataAction(consoleApiParams, registrarId, consoleUserEmail);
  }
}
