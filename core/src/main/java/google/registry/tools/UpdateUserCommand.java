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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.PreconditionsUtils.checkArgumentPresent;

import com.beust.jcommander.Parameters;
import google.registry.model.console.User;
import google.registry.persistence.VKey;

/** Updates a user, assuming that the user in question already exists. */
@Parameters(separators = " =", commandDescription = "Update a user account")
public class UpdateUserCommand extends CreateOrUpdateUserCommand {

  @Override
  User getExistingUser(String email) {
    return checkArgumentPresent(
        tm().loadByKeyIfPresent(VKey.create(User.class, email)), "User %s not found", email);
  }
}
