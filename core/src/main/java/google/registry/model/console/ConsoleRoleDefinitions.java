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

import com.google.common.collect.ImmutableSet;

/**
 * Definitions of the {@link ConsolePermission} sets that each role contains.
 *
 * <p>Note: within the "registry" and "registrar" groupings, permissions expand hierarchically,
 * where each role has all the permissions of the role below it plus some others.
 */
public class ConsoleRoleDefinitions {

  /** Permissions for a registry support agent. */
  static final ImmutableSet<ConsolePermission> SUPPORT_AGENT_PERMISSIONS =
      ImmutableSet.of(
          ConsolePermission.VIEW_REGISTRARS,
          ConsolePermission.VIEW_REGISTRAR_DETAILS,
          ConsolePermission.EDIT_REGISTRAR_DETAILS,
          ConsolePermission.MANAGE_USERS,
          ConsolePermission.MANAGE_ACCREDITATION,
          ConsolePermission.CONFIGURE_EPP_CONNECTION,
          ConsolePermission.DOWNLOAD_DOMAINS,
          ConsolePermission.VIEW_TLD_PORTFOLIO,
          ConsolePermission.ONBOARD_ADDITIONAL_PROGRAMS,
          ConsolePermission.CONTACT_SUPPORT,
          ConsolePermission.ACCESS_BILLING_DETAILS,
          ConsolePermission.ACCESS_DOCUMENTATION,
          ConsolePermission.SUSPEND_DOMAIN_URS,
          ConsolePermission.CHECK_ONBOARDING_STATUS,
          ConsolePermission.VIEW_PREMIUM_RESERVED_LISTS,
          ConsolePermission.UPLOAD_CONTRACTS,
          ConsolePermission.ACCESS_CONTRACTS,
          ConsolePermission.VIEW_OPERATIONAL_DATA,
          ConsolePermission.SEND_ANNOUNCEMENTS,
          ConsolePermission.VIEW_ANNOUNCEMENTS,
          ConsolePermission.VIEW_ACTIVITY_LOG,
          ConsolePermission.REGISTRY_LOCK);

  /** Permissions for a registry support lead. */
  static final ImmutableSet<ConsolePermission> SUPPORT_LEAD_PERMISSIONS =
      new ImmutableSet.Builder<ConsolePermission>()
          .addAll(SUPPORT_AGENT_PERMISSIONS)
          .add(
              ConsolePermission.MANAGE_REGISTRARS,
              ConsolePermission.GET_REGISTRANT_EMAIL,
              ConsolePermission.SUSPEND_DOMAIN,
              ConsolePermission.EXECUTE_EPP_COMMANDS,
              ConsolePermission.MANAGE_DOCUMENTATION)
          .build();

  /** Permissions for a registry full-time employee. */
  static final ImmutableSet<ConsolePermission> FTE_PERMISSIONS =
      new ImmutableSet.Builder<ConsolePermission>()
          .addAll(SUPPORT_LEAD_PERMISSIONS)
          .add(ConsolePermission.CHANGE_NOMULUS_PASSWORD)
          .build();

  /** Permissions for a registrar partner account manager. */
  static final ImmutableSet<ConsolePermission> ACCOUNT_MANAGER_PERMISSIONS =
      ImmutableSet.of(
          ConsolePermission.VIEW_REGISTRAR_DETAILS,
          ConsolePermission.DOWNLOAD_DOMAINS,
          ConsolePermission.VIEW_TLD_PORTFOLIO,
          ConsolePermission.CONTACT_SUPPORT,
          ConsolePermission.ACCESS_DOCUMENTATION,
          ConsolePermission.VIEW_PREMIUM_RESERVED_LISTS,
          ConsolePermission.VIEW_OPERATIONAL_DATA,
          ConsolePermission.VIEW_ANNOUNCEMENTS);

  /** Permissions for a registrar partner account manager that can perform registry locks. */
  static final ImmutableSet<ConsolePermission> ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS =
      new ImmutableSet.Builder<ConsolePermission>()
          .addAll(ACCOUNT_MANAGER_PERMISSIONS)
          .add(ConsolePermission.REGISTRY_LOCK)
          .build();

  /** Permissions for the tech contact of a registrar. */
  static final ImmutableSet<ConsolePermission> TECH_CONTACT_PERMISSIONS =
      new ImmutableSet.Builder<ConsolePermission>()
          .addAll(ACCOUNT_MANAGER_WITH_REGISTRY_LOCK_PERMISSIONS)
          .add(
              ConsolePermission.EDIT_REGISTRAR_DETAILS,
              ConsolePermission.MANAGE_ACCREDITATION,
              ConsolePermission.CONFIGURE_EPP_CONNECTION,
              ConsolePermission.CHANGE_NOMULUS_PASSWORD,
              ConsolePermission.ONBOARD_ADDITIONAL_PROGRAMS,
              ConsolePermission.EXECUTE_EPP_COMMANDS,
              ConsolePermission.ACCESS_BILLING_DETAILS,
              ConsolePermission.CHECK_ONBOARDING_STATUS,
              ConsolePermission.UPLOAD_CONTRACTS,
              ConsolePermission.ACCESS_CONTRACTS,
              ConsolePermission.VIEW_ACTIVITY_LOG)
          .build();

  /** Permissions for the primary registrar contact. */
  static final ImmutableSet<ConsolePermission> PRIMARY_CONTACT_PERMISSIONS =
      new ImmutableSet.Builder<ConsolePermission>()
          .addAll(TECH_CONTACT_PERMISSIONS)
          .add(ConsolePermission.MANAGE_USERS)
          .build();

  private ConsoleRoleDefinitions() {}
}
