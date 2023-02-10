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

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static google.registry.request.auth.AuthLevel.APP;

import com.google.auth.oauth2.TokenVerifier;
import google.registry.config.RegistryConfig.Config;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/**
 * A way to authenticate HTTP requests signed by Service Account
 *
 * <p>Currently used by cloud scheduler service account
 */
public class ServiceAccountAuthenticationMechanism extends IdTokenAuthenticationBase {

  private final String cloudSchedulerEmailPrefix;
  private static final String BEARER_PREFIX = "Bearer ";

  @Inject
  public ServiceAccountAuthenticationMechanism(
      @Named("serviceAccount") TokenVerifier tokenVerifier,
      @Config("cloudSchedulerServiceAccountEmail") String cloudSchedulerEmailPrefix) {

    super(tokenVerifier);
    this.cloudSchedulerEmailPrefix = cloudSchedulerEmailPrefix;
  }

  @Override
  String rawTokenFromRequest(HttpServletRequest request) {
    String rawToken = request.getHeader(AUTHORIZATION);
    if (rawToken != null && rawToken.startsWith(BEARER_PREFIX)) {
      return rawToken.substring(BEARER_PREFIX.length());
    }
    return null;
  }

  @Override
  AuthResult authResultFromEmail(String emailAddress) {
    if (emailAddress == cloudSchedulerEmailPrefix) {
      return AuthResult.create(APP);
    } else {
      return AuthResult.NOT_AUTHENTICATED;
    }
  }
}
