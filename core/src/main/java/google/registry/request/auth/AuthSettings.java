// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import google.registry.model.console.UserRoles;

/**
 * Parameters used to configure the authenticator.
 *
 * <p>AuthSettings shouldn't be used directly, instead - use one of the predefined {@link Auth} enum
 * values.
 */
@Immutable
@AutoValue
public abstract class AuthSettings {

  public abstract ImmutableList<AuthMethod> methods();

  public abstract AuthLevel minimumLevel();

  public abstract UserPolicy userPolicy();

  static AuthSettings create(
      ImmutableList<AuthMethod> methods, AuthLevel minimumLevel, UserPolicy userPolicy) {
    return new AutoValue_AuthSettings(methods, minimumLevel, userPolicy);
  }

  /** Available methods for authentication. */
  public enum AuthMethod {

    /**
     * Authentication methods suitable for API-style access, such as {@link
     * OidcTokenAuthenticationMechanism}.
     */
    API,

    /** Legacy authentication using cookie-based App Engine Users API. Must come last if present. */
    LEGACY
  }

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

    /**
     * If there is a user, it must be an admin, as determined by {@link UserAuthInfo#isUserAdmin()}.
     *
     * <p>Note that, if the user returned is an App Engine {@link
     * com.google.appengine.api.users.User} , anybody with access to the app in the GCP Console,
     * including editors and viewers, is an admin.
     *
     * <p>On the other hand, if the user is a {@link google.registry.model.console.User}, the admin
     * role is explicitly defined in that object via the {@link UserRoles#isAdmin()} method.
     */
    ADMIN
  }
}
