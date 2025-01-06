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
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.io.BaseEncoding.base64;
import static google.registry.model.registrar.Registrar.checkValidEmail;
import static google.registry.util.PasswordUtils.SALT_SUPPLIER;
import static google.registry.util.PasswordUtils.hashPassword;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.gson.annotations.Expose;
import google.registry.model.Buildable;
import google.registry.model.UpdateAutoTimestampEntity;
import google.registry.util.PasswordUtils;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * A console user, either a registry employee or a registrar partner.
 *
 * <p>This class deliberately does not include an {@link Id} so that any foreign-keyed fields can
 * refer to the proper parent entity's ID, whether we're storing this in the DB itself or as part of
 * another entity.
 */
@Access(AccessType.FIELD)
@Embeddable
@MappedSuperclass
public class UserBase extends UpdateAutoTimestampEntity implements Buildable {

  private static final long serialVersionUID = 6936728603828566721L;

  /** Email address of the user in question. */
  @Transient @Expose String emailAddress;

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
  public Builder<? extends UserBase, ?> asBuilder() {
    return new Builder<>(clone(this));
  }

  /** Builder for constructing immutable {@link UserBase} objects. */
  public static class Builder<T extends UserBase, B extends Builder<T, B>>
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
