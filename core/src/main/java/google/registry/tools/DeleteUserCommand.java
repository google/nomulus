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
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameter;
import google.registry.model.console.User;
import google.registry.model.console.UserDao;
import java.util.Optional;
import javax.annotation.Nullable;

/** Deletes a {@link User}. */
public class DeleteUserCommand extends ConfirmingCommand {

  @Nullable
  @Parameter(names = "--email", description = "Email address of the user", required = true)
  String email;

  @Override
  protected String prompt() {
    checkArgumentNotNull(email, "Email must be provided");
    checkArgumentPresent(UserDao.loadUser(email), "Email does not correspond to a valid user");
    return String.format("Delete user with email %s?", email);
  }

  @Override
  protected String execute() throws Exception {
    checkArgumentNotNull(email, "Email must be provided");
    tm().transact(
            () -> {
              Optional<User> optionalUser = UserDao.loadUser(email);
              checkArgumentPresent(optionalUser, "Email no longer corresponds to a valid user");
              tm().delete(optionalUser.get());
            });
    return String.format("Deleted user with email %s", email);
  }
}
