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
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthSettings.AuthMethod;
import google.registry.request.auth.AuthSettings.UserPolicy;
import google.registry.ui.server.registrar.HtmlAction;
import google.registry.ui.server.registrar.JsonGetAction;

/** Enum used to configure authentication settings for Actions. */
public enum Auth {

  /**
   * Allows anyone to access.
   *
   * <p>If a user is logged in, will authenticate (and return) them. Otherwise, access is still
   * granted, but NOT_AUTHENTICATED is returned.
   *
   * <p>This is used for public HTML endpoints like RDAP, the check API, and web WHOIS.
   *
   * <p>User-facing legacy console endpoints (those that extend {@link HtmlAction}) also use it.
   * They need to allow requests from signed-out users so that they can redirect users to the login
   * page. After a user is logged in, they check if the user actually has access to the specific
   * console using {@link AuthenticatedRegistrarAccessor}.
   *
   * @see HtmlAction
   */
  AUTH_PUBLIC(
      ImmutableList.of(AuthMethod.API, AuthMethod.LEGACY), AuthLevel.NONE, UserPolicy.PUBLIC),

  /**
   * Allows anyone to access, as long as they are logged in.
   *
   * <p>This is used by legacy registrar console programmatic endpoints (those that extend {@link
   * JsonGetAction}), which are accessed via XHR requests sent from a logged-in user when performing
   * actions on the console.
   */
  AUTH_PUBLIC_LOGGED_IN(
      ImmutableList.of(AuthMethod.API, AuthMethod.LEGACY), AuthLevel.USER, UserPolicy.PUBLIC),

  /**
   * Allows only the app or admins to access.
   *
   * <p>This applies to the majority of the endpoints. For APP level authentication to work, the
   * associated service account needs to be allowlisted in the {@code
   * auth.allowedServiceAccountEmails} field in the config YAML file.
   */
  AUTH_API_ADMIN(ImmutableList.of(AuthMethod.API), AuthLevel.APP, UserPolicy.ADMIN);

  private final AuthSettings authSettings;

  Auth(ImmutableList<AuthMethod> methods, AuthLevel minimumLevel, UserPolicy userPolicy) {
    authSettings = AuthSettings.create(methods, minimumLevel, userPolicy);
  }

  public AuthSettings authSettings() {
    return authSettings;
  }
}
