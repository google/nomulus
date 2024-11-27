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
import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.Directory.Users;
import com.google.api.services.directory.Directory.Users.Delete;
import com.google.api.services.directory.Directory.Users.Insert;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeResponse;
import google.registry.tools.IamClient;
import google.registry.ui.server.console.ConsoleUsersAction.UserData;
import google.registry.util.StringGenerator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ConsoleUsersActionTest {

  private static final Gson GSON = RequestModule.provideGson();

  private final Directory directory = mock(Directory.class);
  private final Users users = mock(Users.class);
  private final Insert insert = mock(Insert.class);
  private final Delete delete = mock(Delete.class);
  private final IamClient iamClient = mock(IamClient.class);

  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

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
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("GET"),
            Optional.empty());
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
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("GET"),
            Optional.empty());
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
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("POST"),
            Optional.empty());
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    when(directory.users()).thenReturn(users);
    when(users.insert(any(com.google.api.services.directory.model.User.class))).thenReturn(insert);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_CREATED);
    assertThat(response.getPayload())
        .contains(
            "{\"emailAddress\":\"TheRegistrar-user1@email.com\",\"role\":\"ACCOUNT_MANAGER\",\"password\":\"abcdefghijklmnop\"}");
  }

  @Test
  void testFailure_noPermissionToDeleteUser() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("DELETE"),
            Optional.of(
                new UserData("test3@test.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));
    when(directory.users()).thenReturn(users);
    when(users.delete(any(String.class))).thenReturn(delete);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    assertThat(response.getPayload())
        .contains("Can't update user not associated with registrarId TheRegistrar");
  }

  @Test
  void testFailure_userDoesntExist() throws IOException {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("DELETE"),
            Optional.of(
                new UserData("email-1@email.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));
    when(directory.users()).thenReturn(users);
    when(users.delete(any(String.class))).thenReturn(delete);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).contains("User email-1@email.com doesn't exist");
  }

  @Test
  void testSuccess_deletesUser() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("DELETE"),
            Optional.of(
                new UserData("test2@test.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    when(directory.users()).thenReturn(users);
    when(users.delete(any(String.class))).thenReturn(delete);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(DatabaseHelper.loadByKeyIfPresent(VKey.create(User.class, "test2@test.com")))
        .isEmpty();
  }

  @Test
  void testSuccess_removesRole() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());
    DatabaseHelper.persistResource(
        new User.Builder()
            .setEmailAddress("test4@test.com")
            .setUserRoles(
                new UserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of(
                            "TheRegistrar",
                            RegistrarRole.PRIMARY_CONTACT,
                            "SomeRegistrar",
                            RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build());

    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("DELETE"),
            Optional.of(
                new UserData("test4@test.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));

    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    when(directory.users()).thenReturn(users);
    when(users.delete(any(String.class))).thenReturn(delete);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    Optional<User> actualUser =
        DatabaseHelper.loadByKeyIfPresent(VKey.create(User.class, "test4@test.com"));
    assertThat(actualUser).isPresent();
    assertThat(actualUser.get().getUserRoles().getRegistrarRoles().containsKey("TheRegistrar"))
        .isFalse();
  }

  @Test
  void testFailure_limitedTo4UsersPerRegistrar() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());

    DatabaseHelper.persistResources(
        IntStream.range(3, 5)
            .mapToObj(
                i ->
                    new User.Builder()
                        .setEmailAddress(String.format("test%s@test.com", i))
                        .setUserRoles(
                            new UserRoles()
                                .asBuilder()
                                .setRegistrarRoles(
                                    ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                                .build())
                        .build())
            .collect(Collectors.toList()));

    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("POST"),
            Optional.empty());
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    when(directory.users()).thenReturn(users);
    when(users.insert(any(com.google.api.services.directory.model.User.class))).thenReturn(insert);
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).contains("Total users amount per registrar is limited to 4");
  }

  @Test
  void testSuccess_updatesUserRole() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());

    assertThat(
            DatabaseHelper.loadByKey(VKey.create(User.class, "test2@test.com"))
                .getUserRoles()
                .getRegistrarRoles()
                .get("TheRegistrar"))
        .isEqualTo(RegistrarRole.PRIMARY_CONTACT);
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("PUT"),
            Optional.of(
                new UserData("test2@test.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(
            DatabaseHelper.loadByKey(VKey.create(User.class, "test2@test.com"))
                .getUserRoles()
                .getRegistrarRoles()
                .get("TheRegistrar"))
        .isEqualTo(RegistrarRole.ACCOUNT_MANAGER);
  }

  @Test
  void testFailure_noPermissionToUpdateUser() throws IOException {
    User user1 = DatabaseHelper.loadByKey(VKey.create(User.class, "test1@test.com"));
    AuthResult authResult =
        AuthResult.createUser(
            user1
                .asBuilder()
                .setUserRoles(user1.getUserRoles().asBuilder().setIsAdmin(true).build())
                .build());
    ConsoleUsersAction action =
        createAction(
            Optional.of(ConsoleApiParamsUtils.createFake(authResult)),
            Optional.of("PUT"),
            Optional.of(
                new UserData("test3@test.com", RegistrarRole.ACCOUNT_MANAGER.toString(), null)));
    action.run();
    var response = ((FakeResponse) consoleApiParams.response());
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    assertThat(response.getPayload())
        .contains("Can't update user not associated with registrarId TheRegistrar");
  }

  private ConsoleUsersAction createAction(
      Optional<ConsoleApiParams> maybeConsoleApiParams,
      Optional<String> method,
      Optional<UserData> userData)
      throws IOException {
    consoleApiParams =
        maybeConsoleApiParams.orElseGet(
            () -> ConsoleApiParamsUtils.createFake(AuthResult.NOT_AUTHENTICATED));
    when(consoleApiParams.request().getMethod()).thenReturn(method.orElse("GET"));
    return new ConsoleUsersAction(
        consoleApiParams,
        directory,
        iamClient,
        "email.com",
        Optional.of("someRandomString"),
        passwordGenerator,
        userData,
        "TheRegistrar");
  }
}
