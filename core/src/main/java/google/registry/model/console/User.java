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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.tools.server.UpdateUserGroupAction.GROUP_UPDATE_QUEUE;

import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import google.registry.batch.CloudTasksUtils;
import google.registry.persistence.VKey;
import google.registry.request.Action;
import google.registry.tools.IamClient;
import google.registry.tools.ServiceConnection;
import google.registry.tools.server.UpdateUserGroupAction;
import google.registry.tools.server.UpdateUserGroupAction.Mode;
import google.registry.util.RegistryEnvironment;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.IOException;
import java.util.Optional;
import javax.annotation.Nullable;

/** A console user, either a registry employee or a registrar partner. */
@Embeddable
@Entity
@Table
public class User extends UserBase {

  public static final String IAP_SECURED_WEB_APP_USER_ROLE = "roles/iap.httpsResourceAccessor";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Grants the user permission to pass IAP.
   *
   * <p>Depending on if a console user group is set up, the permission is granted either
   * individually or via group membership.
   */
  public static void grantIapPermission(
      String emailAddress,
      Optional<String> groupEmailAddress,
      @Nullable CloudTasksUtils cloudTasksUtils,
      @Nullable ServiceConnection connection,
      IamClient iamClient) {
    if (RegistryEnvironment.isInTestServer()) {
      return;
    }
    checkArgument(
        cloudTasksUtils != null || connection != null,
        "At least one of cloudTasksUtils or connection must be set");
    checkArgument(
        cloudTasksUtils == null || connection == null,
        "Only one of cloudTasksUtils or connection can be set");
    if (groupEmailAddress.isEmpty()) {
      logger.atInfo().log("Granting IAP role to user %s", emailAddress);
      iamClient.addBinding(emailAddress, IAP_SECURED_WEB_APP_USER_ROLE);
    } else {
      logger.atInfo().log("Adding %s to group %s", emailAddress, groupEmailAddress.get());
      if (cloudTasksUtils != null) {
        modifyGroupMembershipAsync(
            emailAddress, groupEmailAddress.get(), cloudTasksUtils, UpdateUserGroupAction.Mode.ADD);
      } else {
        modifyGroupMembershipSync(
            emailAddress, groupEmailAddress.get(), connection, UpdateUserGroupAction.Mode.ADD);
      }
    }
  }

  /**
   * Revoke the user's permission to pass IAP.
   *
   * <p>Depending on if a console user group is set up, the permission is revoked either
   * individually or via group membership.
   */
  public static void revokeIapPermission(
      String emailAddress,
      Optional<String> groupEmailAddress,
      @Nullable CloudTasksUtils cloudTasksUtils,
      @Nullable ServiceConnection connection,
      IamClient iamClient) {
    if (RegistryEnvironment.isInTestServer()) {
      return;
    }
    checkArgument(
        cloudTasksUtils != null || connection != null,
        "At least one of cloudTasksUtils or connection must be set");
    checkArgument(
        cloudTasksUtils == null || connection == null,
        "Only one of cloudTasksUtils or connection can be set");
    if (groupEmailAddress.isEmpty()) {
      logger.atInfo().log("Removing IAP role from user %s", emailAddress);
      iamClient.removeBinding(emailAddress, IAP_SECURED_WEB_APP_USER_ROLE);
    } else {
      logger.atInfo().log("Removing %s from group %s", emailAddress, groupEmailAddress.get());
      if (cloudTasksUtils != null) {
        modifyGroupMembershipAsync(
            emailAddress, groupEmailAddress.get(), cloudTasksUtils, Mode.REMOVE);
      } else {
        modifyGroupMembershipSync(emailAddress, groupEmailAddress.get(), connection, Mode.REMOVE);
      }
    }
  }

  private static void modifyGroupMembershipAsync(
      String userEmailAddress,
      String groupEmailAddress,
      CloudTasksUtils cloudTasksUtils,
      Mode mode) {
    Task task =
        cloudTasksUtils.createTask(
            UpdateUserGroupAction.class,
            Action.Method.POST,
            ImmutableMultimap.of(
                "userEmailAddress",
                userEmailAddress,
                "groupEmailAddress",
                groupEmailAddress,
                "groupUpdateMode",
                mode.name()));
    cloudTasksUtils.enqueue(GROUP_UPDATE_QUEUE, task);
  }

  private static void modifyGroupMembershipSync(
      String userEmailAddress, String groupEmailAddress, ServiceConnection connection, Mode mode) {
    try {
      connection.sendPostRequest(
          UpdateUserGroupAction.PATH,
          ImmutableMap.of(
              "userEmailAddress",
              userEmailAddress,
              "groupEmailAddress",
              groupEmailAddress,
              "groupUpdateMode",
              mode.name()),
          MediaType.PLAIN_TEXT_UTF_8,
          new byte[0]);
    } catch (IOException e) {
      throw new RuntimeException("Cannot send request to server", e);
    }
  }

  @Id
  @Override
  @Access(AccessType.PROPERTY)
  public String getEmailAddress() {
    return super.getEmailAddress();
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  @Override
  public VKey<User> createVKey() {
    return VKey.create(User.class, getEmailAddress());
  }

  /** Builder for constructing immutable {@link User} objects. */
  public static class Builder extends UserBase.Builder<User, Builder> {

    public Builder() {}

    public Builder(User user) {
      super(user);
    }
  }
}
