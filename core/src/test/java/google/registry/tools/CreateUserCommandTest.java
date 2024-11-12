// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.console.User.IAP_SECURED_WEB_APP_USER_ROLE;
import static google.registry.testing.DatabaseHelper.loadExistingUser;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.net.MediaType;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.server.UpdateUserGroupAction;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CreateUserCommand}. */
public class CreateUserCommandTest extends CommandTestCase<CreateUserCommand> {

  private final IamClient iamClient = mock(IamClient.class);
  private final ServiceConnection connection = mock(ServiceConnection.class);

  @BeforeEach
  void beforeEach() {
    command.iamClient = iamClient;
    command.maybeGroupEmailAddress = Optional.empty();
    command.setConnection(connection);
  }

  @Test
  void testSuccess() throws Exception {
    runCommandForced("--email", "user@example.test");
    User onlyUser = Iterables.getOnlyElement(DatabaseHelper.loadAllOf(User.class));
    assertThat(onlyUser.getEmailAddress()).isEqualTo("user@example.test");
    assertThat(onlyUser.getUserRoles().isAdmin()).isFalse();
    assertThat(onlyUser.getUserRoles().getGlobalRole()).isEqualTo(GlobalRole.NONE);
    assertThat(onlyUser.getUserRoles().getRegistrarRoles()).isEmpty();
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testSuccess_addToGroup() throws Exception {
    command.maybeGroupEmailAddress = Optional.of("group@example.test");
    runCommandForced("--email", "user@example.test");
    User onlyUser = Iterables.getOnlyElement(DatabaseHelper.loadAllOf(User.class));
    assertThat(onlyUser.getEmailAddress()).isEqualTo("user@example.test");
    assertThat(onlyUser.getUserRoles().isAdmin()).isFalse();
    assertThat(onlyUser.getUserRoles().getGlobalRole()).isEqualTo(GlobalRole.NONE);
    assertThat(onlyUser.getUserRoles().getRegistrarRoles()).isEmpty();
    verify(connection)
        .sendPostRequest(
            UpdateUserGroupAction.PATH,
            ImmutableMap.of(
                "userEmailAddress",
                "user@example.test",
                "groupEmailAddress",
                "group@example.test",
                "groupUpdateMode",
                "ADD"),
            MediaType.PLAIN_TEXT_UTF_8,
            new byte[0]);
    verifyNoInteractions(iamClient);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testSuccess_registryLock() throws Exception {
    runCommandForced(
        "--email",
        "user@example.test",
        "--registrar_roles",
        "TheRegistrar=PRIMARY_CONTACT",
        "--registry_lock_email_address",
        "registrylockemail@otherexample.test",
        "--registry_lock_password",
        "password");
    User user = loadExistingUser("user@example.test");
    assertThat(user.getRegistryLockEmailAddress()).hasValue("registrylockemail@otherexample.test");
    assertThat(user.verifyRegistryLockPassword("password")).isTrue();
    assertThat(user.verifyRegistryLockPassword("foobar")).isFalse();
  }

  @Test
  void testSuccess_admin() throws Exception {
    runCommandForced("--email", "user@example.test", "--admin", "true");
    assertThat(loadExistingUser("user@example.test").getUserRoles().isAdmin()).isTrue();
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testSuccess_globalRole() throws Exception {
    runCommandForced("--email", "user@example.test", "--global_role", "FTE");
    assertThat(loadExistingUser("user@example.test").getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.FTE);
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testSuccess_registrarRoles() throws Exception {
    runCommandForced(
        "--email",
        "user@example.test",
        "--registrar_roles",
        "TheRegistrar=ACCOUNT_MANAGER,NewRegistrar=PRIMARY_CONTACT");
    assertThat(loadExistingUser("user@example.test").getUserRoles().getRegistrarRoles())
        .isEqualTo(
            ImmutableMap.of(
                "TheRegistrar",
                RegistrarRole.ACCOUNT_MANAGER,
                "NewRegistrar",
                RegistrarRole.PRIMARY_CONTACT));
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testFailure_alreadyExists() throws Exception {
    runCommandForced("--email", "user@example.test");
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--email", "user@example.test")))
        .hasMessageThat()
        .isEqualTo("A user with email user@example.test already exists");
    verifyNoMoreInteractions(iamClient);
    verifyNoInteractions(connection);
  }

  @Test
  void testFailure_badEmail() throws Exception {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--email", "this is not valid")))
        .hasMessageThat()
        .isEqualTo("Provided email this is not valid is not a valid email address");
  }

  @Test
  void testFailure_badRegistryLockEmail() throws Exception {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--email",
                        "user@example.test",
                        "--registry_lock_email_address",
                        "this is not valid")))
        .hasMessageThat()
        .isEqualTo("Provided email this is not valid is not a valid email address");
  }

  @Test
  void testFailure_registryLockPassword_withoutEmail() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--email", "user@example.test", "--registry_lock_password", "password")))
        .hasMessageThat()
        .isEqualTo(
            "Cannot set/remove registry lock password on a user without a registry lock email"
                + " address");
  }
}
