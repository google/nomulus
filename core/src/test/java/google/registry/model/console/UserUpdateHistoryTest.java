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

package google.registry.model.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.loadAllOf;

import com.google.common.collect.Iterables;
import google.registry.model.EntityTestCase;
import google.registry.testing.DatabaseHelper;
import org.junit.jupiter.api.Test;

/** Tests for {@link UserUpdateHistory}. */
public class UserUpdateHistoryTest extends EntityTestCase {

  UserUpdateHistoryTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence() {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    User otherUser = DatabaseHelper.createAdminUser("otherEmail@email.com");
    UserUpdateHistory history =
        new UserUpdateHistory.Builder()
            .setUser(otherUser)
            .setActingUser(user)
            .setModificationTime(fakeClock.nowUtc())
            .setType(ConsoleUpdateHistory.Type.USER_UPDATE)
            .setMethod("POST")
            .setUrl("someUrl")
            .build();
    tm().transact(() -> tm().put(history));

    // Change the acted-upon user and make sure that nothing changed in the history DB
    tm().transact(
            () ->
                tm().put(
                        otherUser
                            .asBuilder()
                            .setUserRoles(
                                otherUser
                                    .getUserRoles()
                                    .asBuilder()
                                    .setGlobalRole(GlobalRole.SUPPORT_LEAD)
                                    .build())
                            .build()));

    UserUpdateHistory fromDb = Iterables.getOnlyElement(loadAllOf(UserUpdateHistory.class));
    assertThat(fromDb.getUser().getUserRoles().getGlobalRole()).isEqualTo(GlobalRole.FTE);
  }
}
