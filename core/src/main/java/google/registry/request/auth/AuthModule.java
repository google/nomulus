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

import static com.google.common.net.HttpHeaders.AUTHORIZATION;

import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.auth.oauth2.TokenVerifier;
import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.IapOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.RegularOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.TokenExtractor;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** Dagger module for authentication routines. */
@Module
public class AuthModule {

  // IAP-signed JWT will be in this header.
  // See https://cloud.google.com/iap/docs/signed-headers-howto#securing_iap_headers.
  public static final String IAP_HEADER_NAME = "X-Goog-IAP-JWT-Assertion";
  // GAE will put the content in header "proxy-authorization" in this header when it routes the
  // request to the app.
  public static final String PROXY_HEADER_NAME = "X-Google-Proxy-Authorization";
  public static final String BEARER_PREFIX = "Bearer ";
  // TODO: Change the IAP audience format once we are on GKE.
  // See: https://cloud.google.com/iap/docs/signed-headers-howto#verifying_the_jwt_payload
  private static final String IAP_AUDIENCE_FORMAT = "/projects/%d/apps/%s";
  private static final String IAP_ISSUER_URL = "https://cloud.google.com/iap";
  private static final String SA_ISSUER_URL = "https://accounts.google.com";

  /** Provides the custom authentication mechanisms (including OAuth and OIDC). */
  @Provides
  ImmutableList<AuthenticationMechanism> provideApiAuthenticationMechanisms(
      OAuthAuthenticationMechanism oauthAuthenticationMechanism,
      IapOidcAuthenticationMechanism iapOidcAuthenticationMechanism,
      RegularOidcAuthenticationMechanism regularOidcAuthenticationMechanism) {
    return ImmutableList.of(
        oauthAuthenticationMechanism,
        iapOidcAuthenticationMechanism,
        regularOidcAuthenticationMechanism);
  }

  @Qualifier
  @interface IapOidc {}

  @Qualifier
  @interface RegularOidc {}

  /** Provides the OAuthService instance. */
  @Provides
  OAuthService provideOauthService() {
    return OAuthServiceFactory.getOAuthService();
  }

  @Provides
  @IapOidc
  @Singleton
  TokenVerifier provideIapTokenVerifier(
      @Config("projectId") String projectId, @Config("projectIdNumber") long projectIdNumber) {
    String audience = String.format(IAP_AUDIENCE_FORMAT, projectIdNumber, projectId);
    return TokenVerifier.newBuilder().setAudience(audience).setIssuer(IAP_ISSUER_URL).build();
  }

  @Provides
  @RegularOidc
  @Singleton
  TokenVerifier provideRegularTokenVerifier(@Config("projectId") String projectId) {
    return TokenVerifier.newBuilder().setAudience(projectId).setIssuer(SA_ISSUER_URL).build();
  }

  @Provides
  @IapOidc
  @Singleton
  TokenExtractor provideIapTokenExtractor() {
    return request -> request.getHeader(IAP_HEADER_NAME);
  }

  @Provides
  @RegularOidc
  @Singleton
  TokenExtractor provideRegularTokenExtractor() {
    return request -> {
      // TODO: only check the Authorizaiton header after the migration to OIDC is complete.
      String rawToken = request.getHeader(PROXY_HEADER_NAME);
      if (rawToken == null) {
        rawToken = request.getHeader(AUTHORIZATION);
      }
      if (rawToken != null && rawToken.startsWith(BEARER_PREFIX)) {
        return rawToken.substring(BEARER_PREFIX.length());
      }
      return null;
    };
  }
}
