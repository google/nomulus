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

import static google.registry.model.console.ConsoleRoleDefinitions.ACCOUNT_MANAGER_PERMISSIONS;
import static google.registry.model.console.ConsoleRoleDefinitions.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS;
import static google.registry.model.console.ConsoleRoleDefinitions.PRIMARY_CONTACT_PERMISSIONS;
import static google.registry.model.console.ConsoleRoleDefinitions.TECH_CONTACT_PERMISSIONS;

import com.google.common.collect.ImmutableSet;

/** Roles for registrar partners that apply to only one registrar. */
public enum RegistrarRole {

  /** The user is a standard account manager at a registrar. */
  ACCOUNT_MANAGER(ACCOUNT_MANAGER_PERMISSIONS),
  /** The user is an account manager that can perform registry locks. */
  ACCOUNT_MANAGER_WITH_REGISTRY_LOCK(ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS),
  /** The user is a technical contact of a registrar. */
  TECH_CONTACT(TECH_CONTACT_PERMISSIONS),
  /** The user is the primary contact at a registrar. */
  PRIMARY_CONTACT(PRIMARY_CONTACT_PERMISSIONS);

  private final ImmutableSet<ConsolePermission> permissions;

  RegistrarRole(ImmutableSet<ConsolePermission> permissions) {
    this.permissions = permissions;
  }

  public boolean hasPermission(ConsolePermission permission) {
    return permissions.contains(permission);
  }
}
