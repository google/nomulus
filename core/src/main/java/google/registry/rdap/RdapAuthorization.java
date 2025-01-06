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

package google.registry.rdap;

import com.google.common.collect.ImmutableSet;

/**
 * Authorization information for RDAP data access.
 *
 * @param role The role to be used for access.
 * @param registrarIds The registrar client IDs for which access is granted (used only if the role
 *     is REGISTRAR.
 */
public record RdapAuthorization(Role role, ImmutableSet<String> registrarIds) {

  enum Role {
    ADMINISTRATOR,
    REGISTRAR,
    PUBLIC
  }

  static RdapAuthorization create(Role role, String registrarId) {
    return create(role, ImmutableSet.of(registrarId));
  }

  static RdapAuthorization create(Role role, ImmutableSet<String> clientIds) {
    return new RdapAuthorization(role, clientIds);
  }

  boolean isAuthorizedForRegistrar(String registrarId) {
    return switch (role()) {
      case ADMINISTRATOR -> true;
      case REGISTRAR -> registrarIds().contains(registrarId);
      default -> false;
    };
  }

  public static final RdapAuthorization PUBLIC_AUTHORIZATION =
      create(Role.PUBLIC, ImmutableSet.of());

  public static final RdapAuthorization ADMINISTRATOR_AUTHORIZATION =
      create(Role.ADMINISTRATOR, ImmutableSet.of());
}
