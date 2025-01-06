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

package google.registry.request.auth;

import com.google.errorprone.annotations.Immutable;
import google.registry.model.console.UserRoles;

/**
 * Parameters used to configure the authenticator.
 *
 * <p>AuthSettings shouldn't be used directly, instead - use one of the predefined {@link Auth} enum
 * values.
 */
@Immutable
public record AuthSettings(AuthLevel minimumLevel, UserPolicy userPolicy) {

  /**
   * Authentication level.
   *
   * <p>Used by {@link Auth} to specify what authentication is required, and by {@link AuthResult}
   * to specify what authentication was found. These are a series of levels, from least to most
   * authentication required. The lowest level of requirement, NONE, can be satisfied by any level
   * of authentication, while the highest level, USER, can only be satisfied by the authentication
   * of a specific user. The level returned may be higher than what was required, if more
   * authentication turns out to be possible. For instance, if an authenticated user is found, USER
   * will be returned even if no authentication was required.
   */
  public enum AuthLevel {

    /** No authentication was required/found. */
    NONE,

    /**
     * Authentication required, but user not required.
     *
     * <p>In Auth: authentication is required, but App-internal authentication (which isn't
     * associated with a specific user, but a service account) is permitted. Examples include
     * requests from Cloud Tasks, Cloud Scheduler, and the proxy.
     *
     * <p>In AuthResult: App-internal authentication (via service accounts) was successful.
     */
    APP,

    /**
     * Authentication required, user required.
     *
     * <p>In Auth: Authentication is required, and app-internal authentication is forbidden, meaning
     * that a valid authentication result will contain specific user information.
     *
     * <p>In AuthResult: A valid user was authenticated.
     */
    USER
  }

  /** User authorization policy options. */
  public enum UserPolicy {

    /** No user policy is enforced; anyone can access this action. */
    PUBLIC,

    /** If there is a user, it must be an admin, as determined by {@link UserRoles#isAdmin()}. */
    ADMIN
  }
}
