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

package google.registry.model.console;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

/** Tests for {@link UserRoles}. */
public class UserRolesTest {

  @Test
  void testDefaults() {
    UserRoles userRoles = new UserRoles.Builder().build();
    for (ConsolePermission permission : ConsolePermission.values()) {
      assertThat(userRoles.getGlobalRole().hasPermission(permission)).isFalse();
    }
    assertThat(userRoles.getRegistrarRoles()).isEmpty();
    assertThat(userRoles.isAdmin()).isFalse();
  }

  @Test
  void testAdmin_overridesAll() {
    UserRoles userRoles = new UserRoles.Builder().setIsAdmin(true).build();
    for (ConsolePermission permission : ConsolePermission.values()) {
      assertThat(userRoles.hasGlobalPermission(permission)).isTrue();
      assertThat(userRoles.hasPermission("TheRegistrar", permission)).isTrue();
    }
  }

  @Test
  void testRegistrarPermission_withGlobal() {
    UserRoles userRoles = new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build();
    for (ConsolePermission permission : ConsolePermission.values()) {
      assertThat(userRoles.hasGlobalPermission(permission)).isTrue();
      assertThat(userRoles.hasPermission("TheRegistrar", permission)).isTrue();
    }
  }

  @Test
  void testRegistrarRoles() {
    UserRoles userRoles =
        new UserRoles.Builder()
            .setGlobalRole(GlobalRole.NONE)
            .setIsAdmin(false)
            .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
            .build();
    assertThat(userRoles.hasPermission("TheRegistrar", ConsolePermission.MANAGE_USERS)).isTrue();
    assertThat(userRoles.hasPermission("TheRegistrar", ConsolePermission.SUSPEND_DOMAIN)).isFalse();
    assertThat(userRoles.hasPermission("nonexistent", ConsolePermission.MANAGE_USERS)).isFalse();
  }

  @Test
  void testFailure_globalOrPerRegistrar() {
    UserRoles.Builder builder =
        new UserRoles.Builder()
            .setGlobalRole(GlobalRole.SUPPORT_AGENT)
            .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT));
    assertThat(assertThrows(IllegalArgumentException.class, builder::build))
        .hasMessageThat()
        .isEqualTo("Users cannot have both global and per-registrar roles");
  }
}
