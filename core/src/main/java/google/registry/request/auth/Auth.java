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
import google.registry.request.auth.RequestAuthenticator.AuthMethod;
import google.registry.request.auth.RequestAuthenticator.AuthSettings;
import google.registry.request.auth.RequestAuthenticator.UserPolicy;

/** Enum used to configure authentication settings for Actions. */
public enum Auth {

  /**
   * Allows anyone access, doesn't attempt to authenticate user.
   *
   * Will never return absent(), but only authenticates access from App Engine task-queues. For
   * everyone else - returns NOT_AUTHENTICATED.
   */
  AUTH_PUBLIC_ANONYMOUS(
      ImmutableList.of(AuthMethod.INTERNAL),
      AuthLevel.NONE,
      UserPolicy.PUBLIC),

  /**
   * Allows anyone access, does attempt to authenticate user.
   *
   * If a user is logged in, will authenticate (and return) them. Otherwise, access is still
   * granted, but NOT_AUTHENTICATED is returned.
   *
   * Will never return absent().
   */
  AUTH_PUBLIC(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API, AuthMethod.LEGACY),
      AuthLevel.NONE,
      UserPolicy.PUBLIC),

  /**
   * Allows anyone access, as long as they are logged in.
   *
   * Does not allow access from App Engine task-queues.
   */
  AUTH_PUBLIC_LOGGED_IN(
      ImmutableList.of(AuthMethod.API, AuthMethod.LEGACY),
      AuthLevel.USER,
      UserPolicy.PUBLIC),

  /**
   * Allows anyone access, as long as they use OAuth to authenticate.
   *
   * <p>Also allows access from App Engine task-queue. Note that OAuth client ID still needs to be
   * allow-listed in the config file for OAuth-based authentication to succeed.
   */
  AUTH_PUBLIC_OR_INTERNAL(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API), AuthLevel.APP, UserPolicy.PUBLIC),

  /**
   * Allows only admins or App Engine task-queue access.
   */
  AUTH_INTERNAL_OR_ADMIN(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API),
      AuthLevel.APP,
      UserPolicy.ADMIN);

  private final AuthSettings authSettings;

  Auth(
      ImmutableList<AuthMethod> methods,
      AuthLevel minimumLevel,
      UserPolicy userPolicy) {
    authSettings = AuthSettings.create(methods, minimumLevel, userPolicy);
  }

  public AuthSettings authSettings() {
    return authSettings;
  }
}
