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
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import google.registry.model.console.UserRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link UpdateUserCommand}. */
public class UpdateUserCommandTest extends CommandTestCase<UpdateUserCommand> {

  @BeforeEach
  void beforeEach() throws Exception {
    UserDao.saveUser(
        new User.Builder()
            .setEmailAddress("user@example.test")
            .setUserRoles(new UserRoles.Builder().build())
            .build());
  }

  @Test
  void testSuccess_registryLockEmail() throws Exception {
    runCommandForced(
        "--email",
        "user@example.test",
        "--registry_lock_email_address",
        "registrylockemail@otherexample.test");
    assertThat(UserDao.loadUser("user@example.test").get().getRegistryLockEmailAddress())
        .hasValue("registrylockemail@otherexample.test");
  }

  @Test
  void testSuccess_removeRegistryLockEmail() throws Exception {
    UserDao.saveUser(
        UserDao.loadUser("user@example.test")
            .get()
            .asBuilder()
            .setRegistryLockEmailAddress("registrylock@otherexample.test")
            .build());
    runCommandForced("--email", "user@example.test", "--registry_lock_email_address", "");
    assertThat(UserDao.loadUser("user@example.test").get().getRegistryLockEmailAddress()).isEmpty();
  }

  @Test
  void testSuccess_admin() throws Exception {
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().isAdmin()).isFalse();
    runCommandForced("--email", "user@example.test", "--admin", "true");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().isAdmin()).isTrue();
    runCommandForced("--email", "user@example.test", "--admin", "false");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().isAdmin()).isFalse();
  }

  @Test
  void testSuccess_registrarRoles() throws Exception {
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getRegistrarRoles())
        .isEmpty();
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
    runCommandForced("--email", "user@example.test", "--registrar_roles", "");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getRegistrarRoles())
        .isEmpty();
  }

  @Test
  void testSuccess_globalRole() throws Exception {
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.NONE);
    runCommandForced("--email", "user@example.test", "--global_role", "FTE");
    assertThat(UserDao.loadUser("user@example.test").get().getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.FTE);
  }

  @Test
  void testFailure_doesntExist() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--email", "nonexistent@example.test")))
        .hasMessageThat()
        .isEqualTo("User nonexistent@example.test not found");
  }

  @Test
  void testFailure_badRegistryLockEmail() {
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
}
