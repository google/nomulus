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
import static google.registry.tools.server.UpdateUserGroupAction.GROUP_UPDATE_QUEUE;

import com.google.cloud.tasks.v2.Task;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import google.registry.batch.CloudTasksUtils;
import google.registry.persistence.VKey;
import google.registry.request.Action.Service;
import google.registry.tools.IamClient;
import google.registry.tools.server.UpdateUserGroupAction;
import google.registry.tools.server.UpdateUserGroupAction.Mode;
import google.registry.util.RegistryEnvironment;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.persistence.Access;
import javax.persistence.AccessType;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/** A console user, either a registry employee or a registrar partner. */
@Embeddable
@Entity
@Table
public class User extends UserBase {

  public static final String IAP_SECURED_WEB_APP_USER_ROLE = "roles/iap.httpsResourceAccessor";
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @VisibleForTesting public static final AtomicLong ID_GENERATOR_FOR_TESTING = new AtomicLong();

  /**
   * Grants the user permission to pass IAP.
   *
   * <p>Depending on if a console user group is set up, the permission is granted either
   * individually or via group membership.
   */
  public static void grantIapPermission(
      String emailAddress,
      Optional<String> groupEmailAddress,
      CloudTasksUtils cloudTasksUtils,
      IamClient iamClient) {
    if (RegistryEnvironment.isInTestServer()) {
      return;
    }
    if (groupEmailAddress.isEmpty()) {
      logger.atInfo().log("Granting IAP role to user %s", emailAddress);
      iamClient.addBinding(emailAddress, IAP_SECURED_WEB_APP_USER_ROLE);
    } else {
      logger.atInfo().log("Adding %s to group %s", emailAddress, groupEmailAddress.get());
      modifyGroupMembershipAsync(
          emailAddress, groupEmailAddress.get(), cloudTasksUtils, UpdateUserGroupAction.Mode.ADD);
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
      CloudTasksUtils cloudTasksUtils,
      IamClient iamClient) {
    if (RegistryEnvironment.isInTestServer()) {
      return;
    }
    if (groupEmailAddress.isEmpty()) {
      logger.atInfo().log("Removing IAP role from user %s", emailAddress);
      iamClient.removeBinding(emailAddress, IAP_SECURED_WEB_APP_USER_ROLE);
    } else {
      logger.atInfo().log("Removing %s from group %s", emailAddress, groupEmailAddress.get());
      modifyGroupMembershipAsync(
          emailAddress, groupEmailAddress.get(), cloudTasksUtils, Mode.REMOVE);
    }
  }

  private static void modifyGroupMembershipAsync(
      String userEmailAddress,
      String groupEmailAddress,
      CloudTasksUtils cloudTasksUtils,
      Mode mode) {
    Task task =
        cloudTasksUtils.createPostTask(
            UpdateUserGroupAction.PATH,
            Service.TOOLS,
            ImmutableMultimap.of(
                "userEmailAddress",
                userEmailAddress,
                "groupEmailAddress",
                groupEmailAddress,
                "groupUpdateMode",
                mode.name()));
    cloudTasksUtils.enqueue(GROUP_UPDATE_QUEUE, task);
  }

  @Override
  @Access(AccessType.PROPERTY)
  public Long getId() {
    return super.getId();
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
    return VKey.create(User.class, getId());
  }

  /** Builder for constructing immutable {@link User} objects. */
  public static class Builder extends UserBase.Builder<User, Builder> {

    public Builder() {}

    public Builder(User user) {
      super(user);
    }

    @Override
    public User build() {
      // Sets the ID temporarily until we can get rid of the non-null constraint (and the field)
      if (getInstance().getId() == null || getInstance().getId().equals(0L)) {
        // In tests, we cannot guarantee that the database is fully set up -- so don't use it to
        // generate a new long
        if (RegistryEnvironment.get() == RegistryEnvironment.UNITTEST) {
          getInstance().setId(ID_GENERATOR_FOR_TESTING.getAndIncrement());
        } else {
          getInstance().setId(tm().reTransact(tm()::allocateId));
        }
      }
      return super.build();
    }
  }
}
