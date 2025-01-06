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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.loadExistingUser;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.putInDb;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link UpdateUserCommand}. */
public class UpdateUserCommandTest extends CommandTestCase<UpdateUserCommand> {

  @BeforeEach
  void beforeEach() throws Exception {
    putInDb(
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
    assertThat(loadExistingUser("user@example.test").getRegistryLockEmailAddress())
        .hasValue("registrylockemail@otherexample.test");
  }

  @Test
  void testSuccess_removeRegistryLockEmail() throws Exception {
    putInDb(
        loadExistingUser("user@example.test")
            .asBuilder()
            .setRegistryLockEmailAddress("registrylock@otherexample.test")
            .build());
    runCommandForced("--email", "user@example.test", "--registry_lock_email_address", "");
    assertThat(loadExistingUser("user@example.test").getRegistryLockEmailAddress()).isEmpty();
  }

  @Test
  void testSuccess_admin() throws Exception {
    assertThat(loadExistingUser("user@example.test").getUserRoles().isAdmin()).isFalse();
    runCommandForced("--email", "user@example.test", "--admin", "true");
    assertThat(loadExistingUser("user@example.test").getUserRoles().isAdmin()).isTrue();
    runCommandForced("--email", "user@example.test", "--admin", "false");
    assertThat(loadExistingUser("user@example.test").getUserRoles().isAdmin()).isFalse();
  }

  @Test
  void testSuccess_registrarRoles() throws Exception {
    assertThat(loadExistingUser("user@example.test").getUserRoles().getRegistrarRoles()).isEmpty();
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
    runCommandForced("--email", "user@example.test", "--registrar_roles", "");
    assertThat(loadExistingUser("user@example.test").getUserRoles().getRegistrarRoles()).isEmpty();
  }

  @Test
  void testSuccess_globalRole() throws Exception {
    assertThat(loadExistingUser("user@example.test").getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.NONE);
    runCommandForced("--email", "user@example.test", "--global_role", "FTE");
    assertThat(loadExistingUser("user@example.test").getUserRoles().getGlobalRole())
        .isEqualTo(GlobalRole.FTE);
  }

  @Test
  void testSuccess_removePassword() throws Exception {
    // Empty password value removes the password
    persistResource(
        loadExistingUser("user@example.test")
            .asBuilder()
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.TECH_CONTACT))
                    .build())
            .setRegistryLockEmailAddress("registrylock@example.test")
            .setRegistryLockPassword("password")
            .build());
    assertThat(loadExistingUser("user@example.test").hasRegistryLockPassword()).isTrue();
    runCommandForced("--email", "user@example.test", "--registry_lock_password", "");
    assertThat(loadExistingUser("user@example.test").hasRegistryLockPassword()).isFalse();
  }

  @Test
  void testSuccess_setsPassword() throws Exception {
    persistResource(
        loadExistingUser("user@example.test")
            .asBuilder()
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.TECH_CONTACT))
                    .build())
            .setRegistryLockEmailAddress("registrylock@example.test")
            .setRegistryLockPassword("password")
            .build());
    assertThat(loadExistingUser("user@example.test").verifyRegistryLockPassword("password"))
        .isTrue();
    runCommandForced("--email", "user@example.test", "--registry_lock_password", "foobar");
    assertThat(loadExistingUser("user@example.test").verifyRegistryLockPassword("password"))
        .isFalse();
    assertThat(loadExistingUser("user@example.test").verifyRegistryLockPassword("foobar")).isTrue();
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

  @Test
  void testFailure_setPassword_noEmail() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--email", "user@example.test", "--registry_lock_password", "foobar")))
        .hasMessageThat()
        .isEqualTo(
            "Cannot set/remove registry lock password on a user without a registry lock email"
                + " address");
  }
}
