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
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.gson.annotations.Expose;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.persistence.converter.RegistrarToRoleMapUserType;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Map;
import org.hibernate.annotations.Type;

/**
 * Contains the global and per-registrar roles for a given user.
 *
 * <p>See <a href="https://go/nomulus-console-authz">go/nomulus-console-authz</a> for more
 * information.
 */
@Access(AccessType.FIELD)
@Embeddable
public class UserRoles extends ImmutableObject implements Buildable {

  /** Whether the user is a global admin, who has access to everything. */
  @Column(nullable = false)
  private boolean isAdmin = false;

  /**
   * The global role (e.g. {@link GlobalRole#SUPPORT_AGENT}) that the user has across all
   * registrars.
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private GlobalRole globalRole = GlobalRole.NONE;

  /** Any per-registrar roles that this user may have. */
  @Expose
  @Type(RegistrarToRoleMapUserType.class)
  private Map<String, RegistrarRole> registrarRoles = ImmutableMap.of();

  /** Whether the user is a global admin, who has access to everything. */
  public boolean isAdmin() {
    return isAdmin;
  }

  /**
   * The global role (e.g. {@link GlobalRole#SUPPORT_AGENT}) that the user has across all
   * registrars.
   */
  public GlobalRole getGlobalRole() {
    return globalRole;
  }

  /** Any per-registrar roles that this user may have. */
  public Map<String, RegistrarRole> getRegistrarRoles() {
    return ImmutableMap.copyOf(registrarRoles);
  }

  /** If the user has the given permission either globally or on the given registrar. */
  public boolean hasPermission(String registrarId, ConsolePermission permission) {
    if (hasGlobalPermission(permission)) {
      return true;
    }
    if (!registrarRoles.containsKey(registrarId)) {
      return false;
    }
    return registrarRoles.get(registrarId).hasPermission(permission);
  }

  /** If the user has the given permission globally across all registrars. */
  public boolean hasGlobalPermission(ConsolePermission permission) {
    return isAdmin || globalRole.hasPermission(permission);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** Builder for constructing immutable {@link UserRoles} objects. */
  public static class Builder extends Buildable.Builder<UserRoles> {

    public Builder() {}

    private Builder(UserRoles userRoles) {
      super(userRoles);
    }

    @Override
    public UserRoles build() {
      // Users should only have a global role or per-registrar roles, not both
      checkArgument(
          getInstance().globalRole.equals(GlobalRole.NONE)
              || getInstance().registrarRoles.isEmpty(),
          "Users cannot have both global and per-registrar roles");
      return super.build();
    }

    public Builder setIsAdmin(boolean isAdmin) {
      getInstance().isAdmin = isAdmin;
      return this;
    }

    public Builder setGlobalRole(GlobalRole globalRole) {
      checkArgumentNotNull(globalRole, "Global role cannot be null");
      getInstance().globalRole = globalRole;
      return this;
    }

    public Builder setRegistrarRoles(Map<String, RegistrarRole> registrarRoles) {
      checkArgumentNotNull(registrarRoles, "Registrar roles map cannot be null");
      getInstance().registrarRoles = ImmutableMap.copyOf(registrarRoles);
      return this;
    }
  }
}
