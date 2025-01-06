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
import static google.registry.model.console.User.grantIapPermission;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.beust.jcommander.Parameters;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.persistence.VKey;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Command to create a new User. */
@Parameters(separators = " =", commandDescription = "Update a user account")
public class CreateUserCommand extends CreateOrUpdateUserCommand implements CommandWithConnection {

  private ServiceConnection connection;

  @Inject IamClient iamClient;

  @Inject
  @Config("gSuiteConsoleUserGroupEmailAddress")
  Optional<String> maybeGroupEmailAddress;

  @Nullable
  @Override
  User getExistingUser(String email) {
    checkArgument(
        !tm().exists(VKey.create(User.class, email)), "A user with email %s already exists", email);
    return null;
  }

  @Override
  protected String execute() throws Exception {
    String ret = super.execute();
    grantIapPermission(email, maybeGroupEmailAddress, null, connection, iamClient);
    return ret;
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }
}
