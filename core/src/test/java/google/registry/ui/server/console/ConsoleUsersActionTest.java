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
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.Directory.Users;
import com.google.api.services.directory.Directory.Users.Insert;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeResponse;
import google.registry.util.StringGenerator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleUsersActionTest {

  private static final Gson GSON = RequestModule.provideGson();

  private final Directory directory = mock(Directory.class);
  private final Users users = mock(Users.class);
  private final Insert insert = mock(Insert.class);

  private StringGenerator passwordGenerator =
      new DeterministicStringGenerator("abcdefghijklmnopqrstuvwxyz");

  private ConsoleApiParams consoleApiParams;

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    User dbUser1 =
        new User.Builder()
            .setEmailAddress("test1@test.com")
            .setUserRoles(
                new UserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build();
    User dbUser2 =
        new User.Builder()
            .setEmailAddress("test2@test.com")
            .setUserRoles(
                new UserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build();
    User dbUser3 =
        new User.Builder()
            .setEmailAddress("test3@test.com")
            .setUserRoles(
                new UserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of("NewRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build();
    DatabaseHelper.persistResources(ImmutableList.of(dbUser1, dbUser2, dbUser3));
  }

  @Test
  void testSuccess_registrarAccess() throws IOException {
    UserRoles userRoles =
        new UserRoles.Builder()
            .setGlobalRole(GlobalRole.NONE)
            .setIsAdmin(false)
            .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
            .build();

    User user =
        new User.Builder().setEmailAddress("email@email.com").setUserRoles(userRoles).build();

    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUsersAction action =
        createAction(Optional.of(ConsoleApiParamsUtils.createFake(authResult)), Optional.of("GET"));
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getPayload())
        .isEqualTo(
            "[{\"emailAddress\":\"test1@test.com\",\"role\":\"PRIMARY_CONTACT\"},{\"emailAddress\":\"test2@test.com\",\"role\":\"PRIMARY_CONTACT\"}]");
  }

  @Test
  void testFailure_noPermission() throws IOException {
    UserRoles userRoles =
        new UserRoles.Builder()
            .setGlobalRole(GlobalRole.NONE)
            .setIsAdmin(false)
            .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
            .build();

    User user =
        new User.Builder().setEmailAddress("email@email.com").setUserRoles(userRoles).build();

    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUsersAction action =
        createAction(Optional.of(ConsoleApiParamsUtils.createFake(authResult)), Optional.of("GET"));
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testSuccess_createsUser() throws IOException {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)), Optional.of("POST"));
    when(directory.users()).thenReturn(users);
    when(users.insert(any(com.google.api.services.directory.model.User.class))).thenReturn(insert);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .contains(
            "{\"password\":\"abcdefghijklmnop\",\"emailAddress\":\"email-1@email.com\",\"role\":\"ACCOUNT_MANAGER\"}");
  }

  @Test
  void testFailure_limitedTo3NewUsers() throws IOException {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    DatabaseHelper.createAdminUser("email-1@email.com");
    DatabaseHelper.createAdminUser("email-2@email.com");
    DatabaseHelper.createAdminUser("email-3@email.com");
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)), Optional.of("POST"));
    when(directory.users()).thenReturn(users);
    when(users.insert(any(com.google.api.services.directory.model.User.class))).thenReturn(insert);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).contains("Extra users amount is limited to 3");
  }

  private ConsoleUsersAction createAction(
      Optional<ConsoleApiParams> maybeConsoleApiParams, Optional<String> method)
      throws IOException {
    consoleApiParams =
        maybeConsoleApiParams.orElseGet(
            () -> ConsoleApiParamsUtils.createFake(AuthResult.NOT_AUTHENTICATED));
    when(consoleApiParams.request().getMethod()).thenReturn(method.orElse("GET"));
    return new ConsoleUsersAction(
        consoleApiParams, GSON, directory, passwordGenerator, "TheRegistrar");
  }
}
