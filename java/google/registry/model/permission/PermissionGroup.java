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

import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;

import com.google.common.collect.ImmutableSet;
import google.registry.model.Buildable;
import google.registry.model.ImmutableObject;
import google.registry.model.common.EntityGroupRoot;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Parent;

import java.util.Set;

/** Named group of user permissions. */
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
@Entity
public class PermissionGroup extends ImmutableObject implements Buildable {

  @Parent
  Key<EntityGroupRoot> parent = getCrossTldKey();


  @Id
  String name;

  Set<Permission> permissions;

  /**
   * Permission Group Name.
   *
   * Functions as both a human-readable identifier and a unique identifier for the permission group.
   */
  public String getName() {
    return name;
  }

  /**
   * Set of permissions granted by membership in the permission group.
   */
  public Set<Permission> getPermissions() {
    return permissions;
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PermissionGroup}, since it is immutable. */
  public static class Builder extends Buildable.Builder<PermissionGroup> {

    public Builder() {}

    private Builder(PermissionGroup instance) {
      super(instance);
    }

    public Builder setName(String name) {
      getInstance().name = name;
      return this;
    }

    public Builder setPermissions(Iterable<Permission> permissions) {
      getInstance().permissions = ImmutableSet.copyOf(permissions);
      return this;
    }

  }

  /** Loads all PermissionGroup entities. */
  public static Iterable<PermissionGroup> loadAll() {
    return ofy().load().type(PermissionGroup.class).ancestor(getCrossTldKey());
  }
}
