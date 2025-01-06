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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.model.console.User.IAP_SECURED_WEB_APP_USER_ROLE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.MediaType;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.EntityTestCase;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.DatabaseHelper;
import google.registry.tools.IamClient;
import google.registry.tools.ServiceConnection;
import google.registry.tools.server.UpdateUserGroupAction;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for {@link User}. */
public class UserTest extends EntityTestCase {

  UserTest() {
    super(JpaEntityCoverageCheck.ENABLED);
  }

  @Test
  void testPersistence_lookupByEmail() {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    tm().transact(
            () -> {
              assertAboutImmutableObjects()
                  .that(
                      tm().query("FROM User WHERE emailAddress = 'email@email.com'", User.class)
                          .getSingleResult())
                  .isEqualExceptFields(user, "id", "updateTimestamp");
              assertThat(
                      tm().query("FROM User WHERE emailAddress = 'nobody@email.com'", User.class)
                          .getResultList())
                  .isEmpty();
            });
  }

  @Test
  void testFailure_asyncAndSyncModeConflict() {
    assertThrows(
        IllegalArgumentException.class,
        () -> User.grantIapPermission("email@example.com", Optional.empty(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            User.grantIapPermission(
                "email@example.com",
                Optional.empty(),
                mock(CloudTasksUtils.class),
                mock(ServiceConnection.class),
                null));
    assertThrows(
        IllegalArgumentException.class,
        () -> User.revokeIapPermission("email@example.com", Optional.empty(), null, null, null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            User.revokeIapPermission(
                "email@example.com",
                Optional.empty(),
                mock(CloudTasksUtils.class),
                mock(ServiceConnection.class),
                null));
  }

  @Test
  void testFailure_badInputs() {
    User.Builder builder = new User.Builder();
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

  @Test
  void testGrantIapPermissionAsync() {
    CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();
    IamClient iamClient = mock(IamClient.class);
    CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();

    // Individual permission.
    User.grantIapPermission(
        "email@example.com", Optional.empty(), cloudTasksUtils, null, iamClient);
    cloudTasksHelper.assertNoTasksEnqueued();
    verify(iamClient).addBinding("email@example.com", IAP_SECURED_WEB_APP_USER_ROLE);

    // Group membership.
    User.grantIapPermission(
        "email@example.com", Optional.of("console@example.com"), cloudTasksUtils, null, iamClient);
    cloudTasksHelper.assertTasksEnqueued(
        "console-user-group-update",
        new TaskMatcher()
            .service("TOOLS")
            .method(HttpMethod.POST)
            .path("/_dr/admin/updateUserGroup")
            .param("userEmailAddress", "email@example.com")
            .param("groupEmailAddress", "console@example.com")
            .param("groupUpdateMode", "ADD"));
    verifyNoMoreInteractions(iamClient);
  }

  @Test
  void testGrantIapPermissionSync() throws Exception {
    ServiceConnection connection = mock(ServiceConnection.class);
    IamClient iamClient = mock(IamClient.class);

    // Individual permission.
    User.grantIapPermission("email@example.com", Optional.empty(), null, connection, iamClient);
    verifyNoInteractions(connection);
    verify(iamClient).addBinding("email@example.com", IAP_SECURED_WEB_APP_USER_ROLE);

    // Group membership.
    User.grantIapPermission(
        "email@example.com", Optional.of("console@example.com"), null, connection, iamClient);
    verify(connection)
        .sendPostRequest(
            UpdateUserGroupAction.PATH,
            ImmutableMap.of(
                "userEmailAddress",
                "email@example.com",
                "groupEmailAddress",
                "console@example.com",
                "groupUpdateMode",
                "ADD"),
            MediaType.PLAIN_TEXT_UTF_8,
            new byte[0]);
    verifyNoMoreInteractions(iamClient);
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testRevokeIapPermissionAsync() {
    CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();
    IamClient iamClient = mock(IamClient.class);
    CloudTasksUtils cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();

    // Individual permission.
    User.revokeIapPermission(
        "email@example.com", Optional.empty(), cloudTasksUtils, null, iamClient);
    cloudTasksHelper.assertNoTasksEnqueued();
    verify(iamClient).removeBinding("email@example.com", IAP_SECURED_WEB_APP_USER_ROLE);

    // Group membership.
    User.revokeIapPermission(
        "email@example.com", Optional.of("console@example.com"), cloudTasksUtils, null, iamClient);
    cloudTasksHelper.assertTasksEnqueued(
        "console-user-group-update",
        new TaskMatcher()
            .service("TOOLS")
            .method(HttpMethod.POST)
            .path("/_dr/admin/updateUserGroup")
            .param("userEmailAddress", "email@example.com")
            .param("groupEmailAddress", "console@example.com")
            .param("groupUpdateMode", "REMOVE"));
    verifyNoMoreInteractions(iamClient);
  }

  @Test
  void testRevokeIapPermissionSync() throws Exception {
    ServiceConnection connection = mock(ServiceConnection.class);
    IamClient iamClient = mock(IamClient.class);

    // Individual permission.
    User.revokeIapPermission("email@example.com", Optional.empty(), null, connection, iamClient);
    verifyNoInteractions(connection);
    verify(iamClient).removeBinding("email@example.com", IAP_SECURED_WEB_APP_USER_ROLE);

    // Group membership.
    User.revokeIapPermission(
        "email@example.com", Optional.of("console@example.com"), null, connection, iamClient);
    verify(connection)
        .sendPostRequest(
            UpdateUserGroupAction.PATH,
            ImmutableMap.of(
                "userEmailAddress",
                "email@example.com",
                "groupEmailAddress",
                "console@example.com",
                "groupUpdateMode",
                "REMOVE"),
            MediaType.PLAIN_TEXT_UTF_8,
            new byte[0]);
    verifyNoMoreInteractions(iamClient);
    verifyNoMoreInteractions(connection);
  }
}
