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
import static google.registry.tools.CreateUserCommand.IAP_SECURED_WEB_APP_USER_ROLE;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CreateUserCommand}. */
public class CreateUserCommandTest extends CommandTestCase<CreateUserCommand> {

  private final IamClient iamClient = mock(IamClient.class);

  @BeforeEach
  void beforeEach() {
    command.iamClient = iamClient;
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
  }

  @Test
  void testSuccess_admin() throws Exception {
    runCommandForced("--email", "user@example.test", "--admin", "true");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().isAdmin()).isTrue();
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
  }

  @Test
  void testSuccess_globalRole() throws Exception {
    runCommandForced("--email", "user@example.test", "--global_role", "FTE");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.FTE);
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
  }

  @Test
  void testSuccess_registrarRoles() throws Exception {
    runCommandForced(
        "--email",
        "user@example.test",
        "--registrar_roles",
        "TheRegistrar=ACCOUNT_MANAGER,NewRegistrar=PRIMARY_CONTACT");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getRegistrarRoles())
        .isEqualTo(
            ImmutableMap.of(
                "TheRegistrar",
                RegistrarRole.ACCOUNT_MANAGER,
                "NewRegistrar",
                RegistrarRole.PRIMARY_CONTACT));
    verify(iamClient).addBinding("user@example.test", IAP_SECURED_WEB_APP_USER_ROLE);
    verifyNoMoreInteractions(iamClient);
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
  }
}
