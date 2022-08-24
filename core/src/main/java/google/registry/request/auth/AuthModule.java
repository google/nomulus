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

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.oauth.OAuthServiceFactory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import javax.inject.Singleton;

/**
 * Dagger module for authentication routines.
 */
@Module
public class AuthModule {

  /** Provides the custom authentication mechanisms (including OAuth). */
  @Provides
  ImmutableList<AuthenticationMechanism> provideApiAuthenticationMechanisms(
      OAuthAuthenticationMechanism oauthAuthenticationMechanism,
      CookieOAuth2AuthenticationMechanism cookieOAuth2AuthenticationMechanism) {
    return ImmutableList.of(oauthAuthenticationMechanism, cookieOAuth2AuthenticationMechanism);
  }

  /** Provides the OAuthService instance. */
  @Provides
  OAuthService provideOauthService() {
    return OAuthServiceFactory.getOAuthService();
  }

  @Provides
  @Singleton
  GoogleIdTokenVerifier provideGoogleIdTokenVerifier(
      @Config("allowedOauthClientIds") ImmutableSet<String> allowedOauthClientIds,
      NetHttpTransport httpTransport,
      JsonFactory jsonFactory) {
    return new GoogleIdTokenVerifier.Builder(httpTransport, jsonFactory)
        .setAudience(allowedOauthClientIds)
        .build();
  }
}
