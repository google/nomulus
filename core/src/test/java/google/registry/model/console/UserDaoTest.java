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

import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.truth.Truth8;
import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

/** Tests for {@link UserDao}. */
public class UserDaoTest extends EntityTestCase {

  @Test
  void testSuccess_saveAndRetrieve() {
    User user1 =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setGaiaId("gaiaId")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    User user2 =
        new User.Builder()
            .setEmailAddress("foo@bar.com")
            .setGaiaId("otherId")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    UserDao.saveUser(user1);
    UserDao.saveUser(user2);
    assertAboutImmutableObjects()
        .that(user1)
        .isEqualExceptFields(UserDao.loadUser("email@email.com").get(), "id", "updateTimestamp");
    assertAboutImmutableObjects()
        .that(user2)
        .isEqualExceptFields(UserDao.loadUser("foo@bar.com").get(), "id", "updateTimestamp");
  }

  @Test
  void testSuccess_absentUser() {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setGaiaId("gaiaId")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    UserDao.saveUser(user);
    User fromDb = UserDao.loadUser("email@email.com").get();
    // nonexistent one should never exist
    Truth8.assertThat(UserDao.loadUser("nonexistent@email.com")).isEmpty();
    // now try deleting the one that does exist
    tm().transact(() -> tm().delete(fromDb));
    Truth8.assertThat(UserDao.loadUser("email@email.com")).isEmpty();
  }

  @Test
  void testFailure_sameEmail() {
    User user1 =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setGaiaId("gaiaId")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    User user2 =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setGaiaId("otherId")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    UserDao.saveUser(user1);
    assertThrows(IllegalArgumentException.class, () -> UserDao.saveUser(user2));
    assertAboutImmutableObjects()
        .that(user1)
        .isEqualExceptFields(UserDao.loadUser("email@email.com").get(), "id", "updateTimestamp");
  }
}
