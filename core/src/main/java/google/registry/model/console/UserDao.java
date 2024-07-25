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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import java.util.Optional;

/** Data access object for {@link User} objects to simplify saving and retrieval. */
public class UserDao {

  /** Retrieves the one user with this email address if it exists. */
  public static Optional<User> loadUser(String emailAddress) {
    return tm().transact(
            () ->
                tm().query("FROM User WHERE emailAddress = :emailAddress", User.class)
                    .setParameter("emailAddress", emailAddress)
                    .getResultStream()
                    .findFirst());
  }

  /** Saves the given user, checking that no existing user already exists with this email. */
  public static void saveUser(User user) {
    tm().transact(() -> tm().put(user));
  }
}
