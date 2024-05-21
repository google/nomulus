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

import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import google.registry.tools.server.UpdateUserGroupAction;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;

/** Command to create a new User. */
@Parameters(separators = " =", commandDescription = "Update a user account")
public class CreateUserCommand extends CreateOrUpdateUserCommand implements CommandWithConnection {

  static final String IAP_SECURED_WEB_APP_USER_ROLE = "roles/iap.httpsResourceAccessor";
  static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ServiceConnection connection;

  @Inject IamClient iamClient;

  @Inject
  @Config("gSuiteConsoleUserGroupEmailAddress")
  Optional<String> maybeGroupEmailAddress;

  @Nullable
  @Override
  User getExistingUser(String email) {
    checkArgument(UserDao.loadUser(email).isEmpty(), "A user with email %s already exists", email);
    return null;
  }

  @Override
  protected String execute() throws Exception {
    String ret = super.execute();
    String groupEmailAddress = maybeGroupEmailAddress.orElse(null);
    if (groupEmailAddress != null) {
      logger.atInfo().log("Adding %s to group %s", email, groupEmailAddress);
      connection.sendPostRequest(
          UpdateUserGroupAction.PATH,
          ImmutableMap.of(
              "userEmailAddress",
              email,
              "groupEmailAddress",
              groupEmailAddress,
              "groupUpdateMode",
              "ADD"),
          MediaType.PLAIN_TEXT_UTF_8,
          new byte[0]);
    } else {
      logger.atInfo().log("Granting IAP role to user %s", email);
      iamClient.addBinding(email, IAP_SECURED_WEB_APP_USER_ROLE);
    }
    return ret;
  }

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }
}
