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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;

/** A console user, either a registry employee or a registrar partner. */
public class User extends ImmutableObject implements Buildable {

  /** Autogenerated unique ID of this user. */
  @SuppressWarnings("unused")
  private long id;

  /** GAIA ID associated with the user in question. */
  private String gaiaId;

  /** Email address of the user in question. */
  private String emailAddress;

  /** Roles (which grant permissions) associated with this user. */
  private UserRoles userRoles;

  /**
   * A hashed password that exists iff this contact is registry-lock-enabled. The hash is a base64
   * encoded SHA256 string.
   */
  String registryLockPasswordHash;

  /** Randomly generated hash salt. */
  String registryLockPasswordSalt;

  public long getId() {
    return id;
  }

  public String getGaiaId() {
    return gaiaId;
  }

  public String getEmailAddress() {
    return emailAddress;
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
    return hashPassword(registryLockPassword, registryLockPasswordSalt)
        .equals(registryLockPasswordHash);
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
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for constructing immutable {@link User} objects. */
  public static class Builder extends Buildable.Builder<User> {

    public Builder() {}

    public Builder(User user) {
      super(user);
    }

    @Override
    public User build() {
      checkArgumentNotNull(getInstance().gaiaId, "Gaia ID cannot be null");
      checkArgumentNotNull(getInstance().emailAddress, "Email address cannot be null");
      checkArgumentNotNull(getInstance().userRoles, "User roles cannot be null");
      return super.build();
    }

    public Builder setGaiaId(String gaiaId) {
      checkArgument(!isNullOrEmpty(gaiaId), "Gaia ID cannot be null or empty");
      getInstance().gaiaId = gaiaId;
      return this;
    }

    public Builder setEmailAddress(String emailAddress) {
      getInstance().emailAddress = checkValidEmail(emailAddress);
      return this;
    }

    public Builder setUserRoles(UserRoles userRoles) {
      checkArgumentNotNull(userRoles, "User roles cannot be null");
      getInstance().userRoles = userRoles;
      return this;
    }

    public Builder removeRegistryLockPassword() {
      getInstance().registryLockPasswordHash = null;
      getInstance().registryLockPasswordSalt = null;
      return this;
    }

    public Builder setRegistryLockPassword(String registryLockPassword) {
      checkArgument(
          getInstance().hasAnyRegistryLockPermission(), "User has no registry lock permission");
      checkArgument(
          !getInstance().hasRegistryLockPassword(), "User already has a password, remove it first");
      checkArgument(
          !isNullOrEmpty(registryLockPassword), "Registry lock password was null or empty");
      getInstance().registryLockPasswordSalt = base64().encode(SALT_SUPPLIER.get());
      getInstance().registryLockPasswordHash =
          hashPassword(registryLockPassword, getInstance().registryLockPasswordSalt);
      return this;
    }
  }
}
