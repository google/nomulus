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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Suppliers.memoizeWithExpiration;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static google.registry.util.RegistryEnvironment.UNITTEST;

import com.google.cloud.compute.v1.BackendService;
import com.google.cloud.compute.v1.BackendServicesClient;
import com.google.cloud.compute.v1.BackendServicesSettings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.IapOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.RegularOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.TokenExtractor;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.TokenVerifier;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.RegistryEnvironment;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Dagger module for authentication routines. */
@Module
public class AuthModule {

  // IAP-signed JWT will be in this header.
  // See https://cloud.google.com/iap/docs/signed-headers-howto#securing_iap_headers.
  public static final String IAP_HEADER_NAME = "X-Goog-IAP-JWT-Assertion";
  public static final String BEARER_PREFIX = "Bearer ";
  private static final String IAP_AUDIENCE_FORMAT = "/projects/%d/global/backendServices/%d";
  private static final String IAP_ISSUER_URL = "https://cloud.google.com/iap";
  private static final String REGULAR_ISSUER_URL = "https://accounts.google.com";
  // The backend service IDs created when setting up GKE routes. They will be included in the
  // audience field in the JWT that IAP creates.
  // See: https://cloud.google.com/iap/docs/signed-headers-howto#verifying_the_jwt_payload
  // The automatically generated backend service ID has the following format:
  // gkemcg1-default-console[-canary]-80-(some random string)
  private static final Pattern BACKEND_END_PATTERN =
      Pattern.compile(".*-default-((frontend|backend|console|pubapi)(-canary)?)-80-.*");

  /** Provides the custom authentication mechanisms. */
  @Provides
  ImmutableList<AuthenticationMechanism> provideApiAuthenticationMechanisms(
      IapOidcAuthenticationMechanism iapOidcAuthenticationMechanism,
      RegularOidcAuthenticationMechanism regularOidcAuthenticationMechanism) {
    return ImmutableList.of(iapOidcAuthenticationMechanism, regularOidcAuthenticationMechanism);
  }

  @Qualifier
  @interface IapOidc {}

  @Qualifier
  @interface RegularOidc {}

  @Qualifier
  @interface RegularOidcFallback {}

  @Provides
  @IapOidc
  @Singleton
  TokenVerifier provideIapTokenVerifier(
      @Config("projectIdNumber") long projectIdNumber,
      @Named("backendServiceIdMap") Supplier<ImmutableMap<String, Long>> backendServiceIdMap) {
    com.google.auth.oauth2.TokenVerifier.Builder tokenVerifierBuilder =
        com.google.auth.oauth2.TokenVerifier.newBuilder().setIssuer(IAP_ISSUER_URL);
    return (String service, String token) -> {
      Long backendServiceId = backendServiceIdMap.get().get(service);
      checkNotNull(
          backendServiceId,
          "Backend service ID not found for service: %s, available IDs are %s",
          service,
          backendServiceIdMap);
      String audience = String.format(IAP_AUDIENCE_FORMAT, projectIdNumber, backendServiceId);
      return tokenVerifierBuilder.setAudience(audience).build().verify(token);
    };
  }

  @Provides
  @RegularOidc
  @Singleton
  TokenVerifier provideRegularTokenVerifier(@Config("oauthClientId") String clientId) {
    com.google.auth.oauth2.TokenVerifier tokenVerifier =
        com.google.auth.oauth2.TokenVerifier.newBuilder()
            .setAudience(clientId)
            .setIssuer(REGULAR_ISSUER_URL)
            .build();
    return (@Nullable String service, String token) -> {
      return tokenVerifier.verify(token);
    };
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
      String rawToken = request.getHeader(AUTHORIZATION);
      if (rawToken != null && rawToken.startsWith(BEARER_PREFIX)) {
        return rawToken.substring(BEARER_PREFIX.length());
      }
      return null;
    };
  }

  @Provides
  @Singleton
  static BackendServicesClient provideBackendServicesClients(
      @ApplicationDefaultCredential GoogleCredentialsBundle credentialsBundle) {
    try {
      return BackendServicesClient.create(
          BackendServicesSettings.newBuilder()
              .setCredentialsProvider(credentialsBundle::getGoogleCredentials)
              .build());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Provides
  @Named("backendServiceIdMap")
  static ImmutableMap<String, Long> provideBackendServiceList(
      Lazy<BackendServicesClient> client, @Config("projectId") String projectId) {
    if (RegistryEnvironment.isInTestServer() || RegistryEnvironment.get() == UNITTEST) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, Long> builder = ImmutableMap.builder();
    for (BackendService service : client.get().list(projectId).iterateAll()) {
      String name = service.getName();
      Matcher matcher = BACKEND_END_PATTERN.matcher(name);
      if (!matcher.matches()) {
        continue;
      }
      builder.put(matcher.group(1), service.getId());
    }
    return builder.build();
  }

  // Use an expiring cache so that the backend service ID map can be refreshed without restarting
  // the server. The map is very unlikely to change, except for when services are just deployed
  // for the first time, because some pods might receive traffic before all services are deployed.
  @Provides
  @Singleton
  @Named("backendServiceIdMap")
  static Supplier<ImmutableMap<String, Long>> provideBackendServiceIdMapSupplier(
      @Named("backendServiceIdMap") Provider<ImmutableMap<String, Long>> backendServiceIdMap) {
    return memoizeWithExpiration(backendServiceIdMap::get, Duration.ofMinutes(15));
  }
}
