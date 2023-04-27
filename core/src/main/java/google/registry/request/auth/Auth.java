// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import google.registry.request.auth.AuthSettings.AuthMethod;
import google.registry.request.auth.AuthSettings.UserPolicy;

/** Enum used to configure authentication settings for Actions. */
public enum Auth {

  /**
   * Allows anyone to access, doesn't attempt to authenticate user.
   *
   * <p>Will never return absent(), but only authenticates access from App Engine task-queues. For
   * everyone else - returns NOT_AUTHENTICATED.
   */
  AUTH_PUBLIC_ANONYMOUS(
      ImmutableList.of(AuthSettings.AuthMethod.INTERNAL),
      AuthSettings.AuthLevel.NONE,
      AuthSettings.UserPolicy.PUBLIC),

  /**
   * Allows anyone to access, does attempt to authenticate user.
   *
   * <p>If a user is logged in, will authenticate (and return) them. Otherwise, access is still
   * granted, but NOT_AUTHENTICATED is returned.
   *
   * <p>Will never return absent().
   */
  AUTH_PUBLIC(
      ImmutableList.of(
          AuthSettings.AuthMethod.INTERNAL,
          AuthSettings.AuthMethod.API,
          AuthSettings.AuthMethod.LEGACY),
      AuthSettings.AuthLevel.NONE,
      AuthSettings.UserPolicy.PUBLIC),

  /**
   * Allows anyone to access, as long as they are logged in.
   *
   * <p>Does not allow access from App Engine task-queues.
   */
  AUTH_PUBLIC_LOGGED_IN(
      ImmutableList.of(AuthSettings.AuthMethod.API, AuthSettings.AuthMethod.LEGACY),
      AuthSettings.AuthLevel.USER,
      AuthSettings.UserPolicy.PUBLIC),

  /**
   * Allows anyone to access, as long as they use OAuth to authenticate.
   *
   * <p>Also allows access from App Engine task-queue. Note that OAuth client ID still needs to be
   * allow-listed in the config file for OAuth-based authentication to succeed.
   */
  AUTH_PUBLIC_OR_INTERNAL(
      ImmutableList.of(AuthSettings.AuthMethod.INTERNAL, AuthSettings.AuthMethod.API),
      AuthSettings.AuthLevel.APP,
      AuthSettings.UserPolicy.PUBLIC),

  /** Allows only admins or App Engine task-queue access. */
  AUTH_INTERNAL_OR_ADMIN(
      ImmutableList.of(AuthSettings.AuthMethod.INTERNAL, AuthSettings.AuthMethod.API),
      AuthSettings.AuthLevel.APP,
      AuthSettings.UserPolicy.ADMIN),

  /**
   * Allows only App Engine task-queue access.
   *
   * <p>In general, prefer AUTH_INTERNAL_OR_ADMIN. This level of access should be reserved for
   * endpoints that have some sensitivity (it was introduced to mitigate a remote-shell
   * vulnerability).
   */
  AUTH_INTERNAL_ONLY(
      ImmutableList.of(AuthSettings.AuthMethod.INTERNAL),
      AuthSettings.AuthLevel.APP,
      AuthSettings.UserPolicy.IGNORED);

  private final AuthSettings authSettings;

  Auth(
      ImmutableList<AuthMethod> methods,
      AuthSettings.AuthLevel minimumLevel,
      UserPolicy userPolicy) {
    authSettings = AuthSettings.create(methods, minimumLevel, userPolicy);
  }

  public AuthSettings authSettings() {
    return authSettings;
  }
}
