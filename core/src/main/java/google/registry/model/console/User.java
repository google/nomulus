// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.tools.server.UpdateUserGroupAction.GROUP_UPDATE_QUEUE;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.MediaType;
import com.google.gson.annotations.Expose;
import google.registry.batch.CloudTasksUtils;
import google.registry.model.Buildable;
import google.registry.model.UpdateAutoTimestampEntity;
import google.registry.request.Action;
import google.registry.tools.IamClient;
import google.registry.tools.ServiceConnection;
import google.registry.tools.server.UpdateUserGroupAction;
import google.registry.util.PasswordUtils;
import google.registry.util.RegistryEnvironment;
import jakarta.persistence.Column;
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
public class User extends UpdateAutoTimestampEntity implements Buildable {

  public static final String IAP_SECURED_WEB_APP_USER_ROLE = "roles/iap.httpsResourceAccessor";

  private static final long serialVersionUID = 6936728603828566721L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Email address of the user in question. */
  @Id @Expose String emailAddress;

  /** Optional external email address to use for registry lock confirmation emails. */
  @Column String registryLockEmailAddress;

  /** Roles (which grant permissions) associated with this user. */
  @Expose
  @Column(nullable = false)
  UserRoles userRoles;

  /**
   * A hashed password that exists iff this contact is registry-lock-enabled. The hash is a base64
   * encoded SHA256 string.
   */
  String registryLockPasswordHash;

  /** Randomly generated hash salt. */
  String registryLockPasswordSalt;

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
            emailAddress,
            groupEmailAddress.get(),
            cloudTasksUtils,
            UpdateUserGroupAction.Mode.REMOVE);
      } else {
        modifyGroupMembershipSync(
            emailAddress, groupEmailAddress.get(), connection, UpdateUserGroupAction.Mode.REMOVE);
      }
    }
  }

  private static void modifyGroupMembershipAsync(
      String userEmailAddress,
      String groupEmailAddress,
      CloudTasksUtils cloudTasksUtils,
      UpdateUserGroupAction.Mode mode) {
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
      String userEmailAddress,
      String groupEmailAddress,
      ServiceConnection connection,
      UpdateUserGroupAction.Mode mode) {
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

  /**
   * Sets the user email address.
   *
   * <p>This should only be used for restoring an object being loaded in a PostLoad method
   * (effectively, when it is still under construction by Hibernate). In all other cases, the object
   * should be regarded as immutable and changes should go through a Builder.
   *
   * <p>In addition to this special case use, this method must exist to satisfy Hibernate.
   */
  void setEmailAddress(String emailAddress) {
    this.emailAddress = emailAddress;
  }

  public String getEmailAddress() {
    return emailAddress;
  }

  public Optional<String> getRegistryLockEmailAddress() {
    return Optional.ofNullable(registryLockEmailAddress);
  }

  public UserRoles getUserRoles() {
    return userRoles;
  }

  public boolean hasRegistryLockPassword() {
    return !isNullOrEmpty(registryLockPasswordHash) && !isNullOrEmpty(registryLockPasswordSalt);
  }

  public boolean verifyRegistryLockPassword(String registryLockPassword) {
    if (isNullOrEmpty(registryLockPassword)
        || isNullOrEmpty(registryLockPasswordSalt)
        || isNullOrEmpty(registryLockPasswordHash)) {
      return false;
    }
    return PasswordUtils.verifyPassword(
        registryLockPassword, registryLockPasswordHash, registryLockPasswordSalt);
  }

  /**
   * Whether the user has the registry lock permission on any registrar or globally.
   *
   * <p>If so, they should be allowed to (re)set their registry lock password.
   */
  public boolean hasAnyRegistryLockPermission() {
    if (userRoles == null) {
      return false;
    }
    if (userRoles.isAdmin() || userRoles.hasGlobalPermission(ConsolePermission.REGISTRY_LOCK)) {
      return true;
    }
    return userRoles.getRegistrarRoles().values().stream()
        .anyMatch(role -> role.hasPermission(ConsolePermission.REGISTRY_LOCK));
  }

  @Override
  public Builder<? extends User, ?> asBuilder() {
    return new Builder<>(clone(this));
  }

  /** Builder for constructing immutable {@link User} objects. */
  public static class Builder<T extends User, B extends Builder<T, B>>
      extends GenericBuilder<T, B> {

    public Builder() {}

    public Builder(T abstractUser) {
      super(abstractUser);
    }

    @Override
    public T build() {
      checkArgumentNotNull(getInstance().emailAddress, "Email address cannot be null");
      checkArgumentNotNull(getInstance().userRoles, "User roles cannot be null");
      return super.build();
    }

    public B setEmailAddress(String emailAddress) {
      getInstance().emailAddress = checkValidEmail(emailAddress);
      return thisCastToDerived();
    }

    public B setRegistryLockEmailAddress(@Nullable String registryLockEmailAddress) {
      getInstance().registryLockEmailAddress =
          registryLockEmailAddress == null ? null : checkValidEmail(registryLockEmailAddress);
      return thisCastToDerived();
    }

    public B setUserRoles(UserRoles userRoles) {
      checkArgumentNotNull(userRoles, "User roles cannot be null");
      getInstance().userRoles = userRoles;
      return thisCastToDerived();
    }

    public B removeRegistryLockPassword() {
      getInstance().registryLockPasswordHash = null;
      getInstance().registryLockPasswordSalt = null;
      return thisCastToDerived();
    }

    public B setRegistryLockPassword(String registryLockPassword) {
      checkArgument(
          getInstance().hasAnyRegistryLockPermission(), "User has no registry lock permission");
      checkArgument(
          !getInstance().hasRegistryLockPassword(), "User already has a password, remove it first");
      checkArgument(
          !isNullOrEmpty(registryLockPassword), "Registry lock password was null or empty");
      byte[] salt = SALT_SUPPLIER.get();
      getInstance().registryLockPasswordSalt = base64().encode(salt);
      getInstance().registryLockPasswordHash = hashPassword(registryLockPassword, salt);
      return thisCastToDerived();
    }
  }
}
