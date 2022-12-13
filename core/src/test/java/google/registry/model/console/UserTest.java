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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.EntityTestCase;
import org.junit.jupiter.api.Test;

/** Tests for {@link User}. */
public class UserTest extends EntityTestCase {

  UserTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence_lookupByGaiaId() {
    User user =
        new User.Builder()
            .setGaiaId("gaiaId")
            .setEmailAddress("email@email.com")
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(true).build())
            .build();
    tm().transact(() -> tm().put(user));
    tm().transact(
            () -> {
              assertAboutImmutableObjects()
                  .that(
                      tm().query("FROM User WHERE gaiaId = 'gaiaId'", User.class).getSingleResult())
                  .isEqualExceptFields(user, "id", "updateTimestamp");
              assertThat(
                      tm().query("FROM User WHERE gaiaId = 'badGaiaId'", User.class)
                          .getResultList())
                  .isEmpty();
            });
  }

  @Test
  void testFailure_badInputs() {
    User.Builder builder = new User.Builder();
    assertThat(assertThrows(IllegalArgumentException.class, () -> builder.setGaiaId(null)))
        .hasMessageThat()
        .isEqualTo("Gaia ID cannot be null or empty");
    assertThat(assertThrows(IllegalArgumentException.class, () -> builder.setEmailAddress("")))
        .hasMessageThat()
        .isEqualTo("Provided email  is not a valid email address");
    assertThat(assertThrows(NullPointerException.class, () -> builder.setEmailAddress(null)))
        .hasMessageThat()
        .isEqualTo("Provided email was null");
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> builder.setEmailAddress("invalidEmail")))
        .hasMessageThat()
        .isEqualTo("Provided email invalidEmail is not a valid email address");
    assertThat(assertThrows(IllegalArgumentException.class, () -> builder.setUserRoles(null)))
        .hasMessageThat()
        .isEqualTo("User roles cannot be null");

    assertThat(assertThrows(IllegalArgumentException.class, builder::build))
        .hasMessageThat()
        .isEqualTo("Gaia ID cannot be null");
    builder.setGaiaId("gaiaId");
    assertThat(assertThrows(IllegalArgumentException.class, builder::build))
        .hasMessageThat()
        .isEqualTo("Email address cannot be null");
    builder.setEmailAddress("email@email.com");
    assertThat(assertThrows(IllegalArgumentException.class, builder::build))
        .hasMessageThat()
        .isEqualTo("User roles cannot be null");

    builder.setUserRoles(new UserRoles.Builder().build());
    builder.build();
  }

  @Test
  void testRegistryLockPassword() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    new User.Builder()
                        .setUserRoles(new UserRoles.Builder().build())
                        .setRegistryLockPassword("foobar")))
        .hasMessageThat()
        .isEqualTo("User has no registry lock permission");

    User user =
        new User.Builder()
            .setGaiaId("gaiaId")
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();
    assertThat(user.hasRegistryLockPassword()).isFalse();

    user = user.asBuilder().setRegistryLockPassword("foobar").build();
    assertThat(user.hasRegistryLockPassword()).isTrue();
    assertThat(user.verifyRegistryLockPassword("foobar")).isTrue();

    user = user.asBuilder().removeRegistryLockPassword().build();
    assertThat(user.hasRegistryLockPassword()).isFalse();
    assertThat(user.verifyRegistryLockPassword("foobar")).isFalse();
  }
}
