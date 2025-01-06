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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableMap;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.tools.params.KeyValueMapParameter.StringToRegistrarRoleMap;
import java.util.Optional;
import javax.annotation.Nullable;

/** Shared base class for commands that create or modify a {@link User}. */
public abstract class CreateOrUpdateUserCommand extends ConfirmingCommand {

  @Parameter(names = "--email", description = "Email address of the user", required = true)
  String email;

  @Nullable
  @Parameter(
      names = "--registry_lock_email_address",
      description =
          "Optional external email address to use for registry lock confirmation emails, or empty"
              + " to remove the field.")
  private String registryLockEmailAddress;

  @Nullable
  @Parameter(
      names = "--registry_lock_password",
      description =
          "Sets the registry lock password for this user, or removes it (allowing the user to"
              + " re-set it). Do not set the password explicitly unless in exceptional"
              + " circumstances.")
  private String registryLockPassword;

  @Nullable
  @Parameter(
      names = "--admin",
      description = "Whether or not the user in question is an admin",
      arity = 1)
  private Boolean isAdmin;

  @Nullable
  @Parameter(
      names = "--global_role",
      description = "Global role, e.g. SUPPORT_LEAD, to apply to the user")
  private GlobalRole globalRole;

  @Nullable
  @Parameter(
      names = "--registrar_roles",
      converter = StringToRegistrarRoleMap.class,
      validateWith = StringToRegistrarRoleMap.class,
      description =
          "Comma-delimited mapping of registrar name to role that the user has on that registrar")
  private ImmutableMap<String, RegistrarRole> registrarRolesMap;

  @Nullable
  abstract User getExistingUser(String email);

  @Override
  protected String execute() throws Exception {
    checkArgumentNotNull(email, "Email must be provided");
    tm().transact(this::executeInTransaction);
    return String.format("Saved user with email %s", email);
  }

  private void executeInTransaction() {
    User user = getExistingUser(email);
    UserRoles.Builder userRolesBuilder =
        (user == null) ? new UserRoles.Builder() : user.getUserRoles().asBuilder();

    Optional.ofNullable(globalRole).ifPresent(userRolesBuilder::setGlobalRole);
    Optional.ofNullable(registrarRolesMap).ifPresent(userRolesBuilder::setRegistrarRoles);
    Optional.ofNullable(isAdmin).ifPresent(userRolesBuilder::setIsAdmin);

    User.Builder builder =
        (user == null) ? new User.Builder().setEmailAddress(email) : user.asBuilder();
    builder.setUserRoles(userRolesBuilder.build());

    // An empty registryLockEmailAddress indicates that we should remove the field
    if (registryLockEmailAddress != null) {
      if (registryLockEmailAddress.isEmpty()) {
        builder.setRegistryLockEmailAddress(null);
      } else {
        builder.setRegistryLockEmailAddress(registryLockEmailAddress);
      }
    }
    // Ditto the registry lock password
    if (registryLockPassword != null) {
      if (registryLockEmailAddress != null) {
        // Edge case, make sure we're not removing an email and setting a password at the same time
        checkArgument(
            !registryLockEmailAddress.isEmpty(),
            "Cannot set/remove registry lock password on a user without a registry lock email"
                + " address");
      } else {
        checkArgument(
            user != null && user.getRegistryLockEmailAddress().isPresent(),
            "Cannot set/remove registry lock password on a user without a registry lock email"
                + " address");
      }
      builder.removeRegistryLockPassword();
      if (!registryLockPassword.isEmpty()) {
        builder.setRegistryLockPassword(registryLockPassword);
      }
    }
    tm().put(builder.build());
  }
}
