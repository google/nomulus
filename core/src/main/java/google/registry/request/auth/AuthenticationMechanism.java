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

import jakarta.servlet.http.HttpServletRequest;

/**
 * A particular way to authenticate an HTTP request, returning an {@link AuthResult}.
 *
 * <p>For instance, a request could be authenticated using OIDC, via special request headers, etc.
 */
public interface AuthenticationMechanism {

  /**
   * Attempt to authenticate an incoming request.
   *
   * @param request the request to be authenticated
   * @return the results of the authentication check; if the request could not be authenticated,
   *     the mechanism should return AuthResult.NOT_AUTHENTICATED
   */
  AuthResult authenticate(HttpServletRequest request);
}
