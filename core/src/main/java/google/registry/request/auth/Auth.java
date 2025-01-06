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

import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthSettings.UserPolicy;

/** Enum used to configure authentication settings for Actions. */
public enum Auth {

  /**
   * Allows anyone to access.
   *
   * <p>This is used for public HTML endpoints like RDAP, the check API, and web WHOIS.
   */
  AUTH_PUBLIC(AuthLevel.NONE, UserPolicy.PUBLIC),

  /**
   * Allows anyone to access, as long as they are logged in.
   *
   * <p>Note that the action might use {@link AuthenticatedRegistrarAccessor} to impose a more
   * fine-grained access control pattern than merely whether the user is logged in/out.
   */
  AUTH_PUBLIC_LOGGED_IN(AuthLevel.USER, UserPolicy.PUBLIC),

  /**
   * Allows only the app itself (via service accounts) or admins to access.
   *
   * <p>This applies to the majority of the endpoints. For APP level authentication to work, the
   * associated service account needs to be allowlisted in the {@code
   * auth.allowedServiceAccountEmails} field in the config YAML file.
   */
  AUTH_ADMIN(AuthLevel.APP, UserPolicy.ADMIN);

  private final AuthSettings authSettings;

  Auth(AuthLevel minimumLevel, UserPolicy userPolicy) {
    authSettings = new AuthSettings(minimumLevel, userPolicy);
  }

  public AuthSettings authSettings() {
    return authSettings;
  }
}
