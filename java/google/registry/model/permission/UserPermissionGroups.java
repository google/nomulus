// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.model.permission;

import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.registrar.Registrar;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

import java.util.Set;

/**
 * Permission groups assigned to a user.
 *
 * Note that user permissions are only intended for internal users. Each user referenced by
 * UserPermissionGroups entities should have a corresponding RegistrarContact entity for the 9999
 * internal registrar.
 */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class UserPermissionGroups extends ImmutableObject implements Buildable {

  @Parent
  Key<Registrar> parent;

  @Id
  String emailAddress;

  @Index
  Set<String> permissionGroups;

  /**
   * Email Address of the internal user.
   */
  public String getEmailAddress() {
    return emailAddress;
  }

  /**
   * Set of permission groups that have been assigned to the internal user.
   */
  public Set<String> getPermissionGroups() {
    return permissionGroups;
  }

  public Key<Registrar> getParent() {
    return parent;
  }

  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PermissionGroup}, since it is immutable. */
  public static class Builder extends Buildable.Builder<UserPermissionGroups> {

    public Builder() {}

    private Builder(UserPermissionGroups instance) {
      super(instance);
    }

    public Builder setEmailAddress(String emailAddress) {
      getInstance().emailAddress = emailAddress;
      return this;
    }

    public Builder setPermissionGroups(Iterable<String> permissionGroups) {
      getInstance().permissionGroups = ImmutableSet.copyOf(permissionGroups);
      return this;
    }

    public Builder setParent(Registrar parent) {
      return setParent(Key.create(parent));
    }

    public Builder setParent(Key<Registrar> parent) {
      getInstance().parent = parent;
      return this;
    }
  }
}
