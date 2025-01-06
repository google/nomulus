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

import org.junit.jupiter.api.Test;

/** Tests for {@link ConsoleRoleDefinitions}. */
public class ConsoleRoleDefinitionsTest {

  @Test
  void testHierarchicalPermissions_registry() {
    // Note: we can't use Truth's IterableSubject to check all the subset/superset restrictions
    // because it is generic to iterables and doesn't know about sets.
    assertThat(
            ConsoleRoleDefinitions.SUPPORT_LEAD_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.SUPPORT_AGENT_PERMISSIONS))
        .isTrue();
    assertThat(
            ConsoleRoleDefinitions.SUPPORT_AGENT_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.SUPPORT_LEAD_PERMISSIONS))
        .isFalse();

    assertThat(
            ConsoleRoleDefinitions.FTE_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.SUPPORT_LEAD_PERMISSIONS))
        .isTrue();
    assertThat(
            ConsoleRoleDefinitions.SUPPORT_LEAD_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.FTE_PERMISSIONS))
        .isFalse();
  }

  @Test
  void testHierarchicalPermissions_registrar() {
    // Note: we can't use Truth's IterableSubject to check all the subset/superset restrictions
    // because it is generic to iterables and doesn't know about sets.
    assertThat(
            ConsoleRoleDefinitions.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.ACCOUNT_MANAGER_PERMISSIONS))
        .isTrue();
    assertThat(
            ConsoleRoleDefinitions.ACCOUNT_MANAGER_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS))
        .isFalse();

    assertThat(
            ConsoleRoleDefinitions.TECH_CONTACT_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS))
        .isTrue();
    assertThat(
            ConsoleRoleDefinitions.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.TECH_CONTACT_PERMISSIONS))
        .isFalse();

    assertThat(
            ConsoleRoleDefinitions.PRIMARY_CONTACT_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.TECH_CONTACT_PERMISSIONS))
        .isTrue();
    assertThat(
            ConsoleRoleDefinitions.TECH_CONTACT_PERMISSIONS.containsAll(
                ConsoleRoleDefinitions.PRIMARY_CONTACT_PERMISSIONS))
        .isFalse();
  }
}
