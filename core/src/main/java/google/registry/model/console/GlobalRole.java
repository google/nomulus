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

import static google.registry.model.console.ConsoleRoleDefinitions.FTE_PERMISSIONS;
import static google.registry.model.console.ConsoleRoleDefinitions.SUPPORT_AGENT_PERMISSIONS;
import static google.registry.model.console.ConsoleRoleDefinitions.SUPPORT_LEAD_PERMISSIONS;

import com.google.common.collect.ImmutableSet;

/** Roles for registry employees that apply across all registrars. */
public enum GlobalRole {

  /** The user has no global role, i.e. they're a registrar partner. */
  NONE(ImmutableSet.of()),
  /** The user is a registry support agent. */
  SUPPORT_AGENT(SUPPORT_AGENT_PERMISSIONS),
  /** The user is a registry support lead. */
  SUPPORT_LEAD(SUPPORT_LEAD_PERMISSIONS),
  /** The user is a registry full-time employee. */
  FTE(FTE_PERMISSIONS);

  private final ImmutableSet<ConsolePermission> permissions;

  GlobalRole(ImmutableSet<ConsolePermission> permissions) {
    this.permissions = permissions;
  }

  public boolean hasPermission(ConsolePermission permission) {
    return permissions.contains(permission);
  }
}
