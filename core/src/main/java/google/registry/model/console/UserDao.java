// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import java.util.Optional;

/** Data access object for {@link User} objects to simplify saving and retrieval. */
public class UserDao {

  /** Retrieves the one user with this email address if it exists. */
  public static Optional<User> loadUser(String emailAddress) {
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .query("FROM User WHERE emailAddress = :emailAddress", User.class)
                    .setParameter("emailAddress", emailAddress)
                    .getResultStream()
                    .findFirst());
  }

  /** Saves the given user, checking that no existing user already exists with this email. */
  public static void saveUser(User user) {
    jpaTm()
        .transact(
            () -> {
              // Check for an existing user (the unique constraint protects us, but this gives a
              // nicer exception)
              Optional<User> maybeSavedUser = loadUser(user.getEmailAddress());
              if (maybeSavedUser.isPresent()) {
                User savedUser = maybeSavedUser.get();
                checkArgument(
                    savedUser.getId().equals(user.getId()),
                    String.format(
                        "Attempted save of User with email address %s and ID %s, user with that"
                            + " email already exists with ID %s",
                        user.getEmailAddress(), user.getId(), savedUser.getId()));
              }
              jpaTm().put(user);
            });
  }
}
