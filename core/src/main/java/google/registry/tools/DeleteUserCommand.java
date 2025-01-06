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

import static com.google.api.client.util.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Deletes a {@link User}. */
@Parameters(separators = " =", commandDescription = "Delete a user account")
public class DeleteUserCommand extends ConfirmingCommand implements CommandWithConnection {

  private ServiceConnection connection;

  @Inject IamClient iamClient;

  @Inject
  @Config("gSuiteConsoleUserGroupEmailAddress")
  Optional<String> maybeGroupEmailAddress;

  @Nullable
  @Parameter(names = "--email", description = "Email address of the user", required = true)
  String email;

  @Override
  protected String prompt() {
    checkArgumentNotNull(email, "Email must be provided");
    checkArgument(
        tm().transact(() -> tm().exists(VKey.create(User.class, email))),
        "Email does not correspond to a valid user");
    return String.format("Delete user with email %s?", email);
  }

  @Override
  protected String execute() throws Exception {
    tm().transact(
            () -> {
              VKey<User> key = VKey.create(User.class, email);
              checkArgument(tm().exists(key), "Email no longer corresponds to a valid user");
              tm().delete(key);
            });
    User.revokeIapPermission(email, maybeGroupEmailAddress, null, connection, iamClient);
    return String.format("Deleted user with email %s", email);
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }
}
