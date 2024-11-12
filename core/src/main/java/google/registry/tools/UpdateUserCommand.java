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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import org.jetbrains.annotations.Nullable;

/** Updates a user, assuming that the user in question already exists. */
@Parameters(separators = " =", commandDescription = "Update a user account")
public class UpdateUserCommand extends CreateOrUpdateUserCommand {

  @Parameter(names = "--remove_registry_lock_password", description = "Removes the registry ")
  private boolean removeRegistryLockPassword;

  @Parameter(
      names = "--registry_lock_password",
      description =
          "BREAK-GLASS ONLY: sets the registry lock password for this user. DO NOT USE except in"
              + " exceptional circumstances")
  private String registryLockPassword;

  @Override
  User getExistingUser(String email) {
    return checkArgumentPresent(
        tm().loadByKeyIfPresent(VKey.create(User.class, email)), "User %s not found", email);
  }

  @Override
  protected void updateBuilderIfNecessary(User.Builder builder, @Nullable User user) {
    checkArgument(user != null, "User cannot be null");
    if (!removeRegistryLockPassword && registryLockPassword == null) {
      return;
    }
    checkArgument(
        user.getRegistryLockEmailAddress().isPresent(),
        "Cannot set/remove registry lock password on a user without a registry lock email address");
    if (removeRegistryLockPassword) {
      checkArgument(
          registryLockPassword == null, "Cannot both remove and set a registry lock password");
      builder.removeRegistryLockPassword();
    } else {
      checkArgument(!registryLockPassword.isEmpty(), "Non-null password cannot be empty");
      builder.removeRegistryLockPassword().setRegistryLockPassword(registryLockPassword);
    }
  }
}
