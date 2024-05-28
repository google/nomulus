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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.CreateUserCommand.IAP_SECURED_WEB_APP_USER_ROLE;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
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

/** Deletes a {@link User}. */
@Parameters(separators = " =", commandDescription = "Delete a user account")
public class DeleteUserCommand extends ConfirmingCommand implements CommandWithConnection {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private ServiceConnection connection;
  @Inject IamClient iamClient;

  @Inject
  @Config("gSuiteConsoleUserGroupEmailAddress")
  Optional<String> maybeGroupEmailAddress;

  @Nullable
  @Parameter(names = "--email", description = "Email address of the user", required = true)
  String email;

  @Override
  public void setConnection(ServiceConnection connection) {
    this.connection = connection;
  }

  @Override
  protected String prompt() {
    checkArgumentNotNull(email, "Email must be provided");
    checkArgumentPresent(UserDao.loadUser(email), "Email does not correspond to a valid user");
    return String.format("Delete user with email %s?", email);
  }

  @Override
  protected String execute() throws Exception {
    tm().transact(
            () -> {
              Optional<User> optionalUser = UserDao.loadUser(email);
              checkArgumentPresent(optionalUser, "Email no longer corresponds to a valid user");
              tm().delete(optionalUser.get());
            });
    String groupEmailAddress = maybeGroupEmailAddress.orElse(null);
    if (groupEmailAddress != null) {
      logger.atInfo().log("Removing %s from group %s", email, groupEmailAddress);
      connection.sendPostRequest(
          UpdateUserGroupAction.PATH,
          ImmutableMap.of(
              "userEmailAddress",
              email,
              "groupEmailAddress",
              groupEmailAddress,
              "groupUpdateMode",
              "REMOVE"),
          MediaType.PLAIN_TEXT_UTF_8,
          new byte[0]);
    } else {
      logger.atInfo().log("Removing IAP role from user %s", email);
      iamClient.removeBinding(email, IAP_SECURED_WEB_APP_USER_ROLE);
    }
    return String.format("Deleted user with email %s", email);
  }
}
