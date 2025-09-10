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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.config.RegistryConfig.getUserAuthCachingDuration;
import static google.registry.config.RegistryConfig.getUserAuthMaxCachedEntries;
import static google.registry.request.auth.AuthModule.BEARER_PREFIX;
import static google.registry.request.auth.AuthModule.IAP_HEADER_NAME;
import static google.registry.testing.DatabaseHelper.createAdminUser;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebSignature.Header;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.TokenVerifier.VerificationException;
import com.google.common.collect.ImmutableSet;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.CacheUtils;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.IapOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.RegularOidcAuthenticationMechanism;
import google.registry.util.GoogleCredentialsBundle;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link OidcTokenAuthenticationMechanism}. */
public class OidcTokenAuthenticationMechanismTest {

  private static final String rawToken = "this-token";
  private static final String email = "user@email.test";
  private static final String gaiaId = "gaia-id";
  private static final ImmutableSet<String> serviceAccounts =
      ImmutableSet.of("service@email.test", "email@service.goog");

  private final Payload payload = new Payload();
  private final JsonWebSignature jwt =
      new JsonWebSignature(new Header(), payload, new byte[0], new byte[0]);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private User user;
  private AuthResult authResult;
  private OidcTokenAuthenticationMechanism authenticationMechanism =
      new OidcTokenAuthenticationMechanism(
          serviceAccounts, request -> rawToken, (service, token) -> jwt) {};

  @RegisterExtension
  public final JpaTestExtensions.JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(User.class).buildUnitTestExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    // 1. Create a brand new cache.
    LoadingCache<String, Optional<User>> testCache =
        CacheUtils.newCacheBuilder(getUserAuthCachingDuration())
            .maximumSize(getUserAuthMaxCachedEntries())
            .recordStats()
            .build(OidcTokenAuthenticationMechanism::loadUser);
    OidcTokenAuthenticationMechanism.setCacheForTesting(testCache);
    payload.setEmail(email);
    payload.setSubject(gaiaId);
    user = createAdminUser(email);
  }

  @AfterEach
  void afterEach() {
    OidcTokenAuthenticationMechanism.unsetAuthResultForTesting();
  }

  @Test
  void testAuthResultBypass() {
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.NOT_AUTHENTICATED);
    assertThat(authenticationMechanism.authenticate(null)).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testAuthenticate_noTokenFromRequest() {
    authenticationMechanism =
        new OidcTokenAuthenticationMechanism(
            serviceAccounts, e -> null, (service, token) -> jwt) {};
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testAuthenticate_invalidToken() throws Exception {
    authenticationMechanism =
        new OidcTokenAuthenticationMechanism(
            serviceAccounts,
            e -> null,
            (service, token) -> {
              throw new VerificationException("Bad token");
            }) {};
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testAuthenticate_noEmailAddress() throws Exception {
    payload.setEmail(null);
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testAuthenticate_user() throws Exception {
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult.isAuthenticated()).isTrue();
    assertThat(authResult.authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.user().get()).isEqualTo(user);
  }

  @Test
  void testAuthenticate_serviceAccount() throws Exception {
    payload.setEmail("service@email.test");
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult.isAuthenticated()).isTrue();
    assertThat(authResult.authLevel()).isEqualTo(AuthLevel.APP);
  }

  @Test
  void testAuthenticate_bothUserAndServiceAccount() throws Exception {
    User serviceUser =
        persistResource(
            new User.Builder()
                .setEmailAddress("service@email.test")
                .setUserRoles(
                    new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
                .build());
    payload.setEmail("service@email.test");
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult.isAuthenticated()).isTrue();
    assertThat(authResult.authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.user().get()).isEqualTo(serviceUser);
  }

  @Test
  void testAuthenticate_unknownEmailAddress() throws Exception {
    payload.setEmail("bad-guy@evil.real");
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testIap_tokenExtractor() throws Exception {
    useIapOidcMechanism();
    when(request.getHeader(IAP_HEADER_NAME)).thenReturn(rawToken);
    assertThat(authenticationMechanism.tokenExtractor.extract(request)).isEqualTo(rawToken);
  }

  @Test
  void testRegular_tokenExtractor() throws Exception {
    useRegularOidcMechanism();
    // The token does not have the "Bearer " prefix.
    when(request.getHeader(AUTHORIZATION)).thenReturn(rawToken);
    assertThat(authenticationMechanism.tokenExtractor.extract(request)).isNull();

    // The token is in the correct format.
    when(request.getHeader(AUTHORIZATION))
        .thenReturn(String.format("%s%s", BEARER_PREFIX, rawToken));
    assertThat(authenticationMechanism.tokenExtractor.extract(request)).isEqualTo(rawToken);
  }

  private void useIapOidcMechanism() {
    TestComponent component = DaggerOidcTokenAuthenticationMechanismTest_TestComponent.create();
    authenticationMechanism = component.iapOidcAuthenticationMechanism();
  }

  private void useRegularOidcMechanism() {
    TestComponent component = DaggerOidcTokenAuthenticationMechanismTest_TestComponent.create();
    authenticationMechanism = component.regularOidcAuthenticationMechanism();
  }

  @Test
  void testAuthenticate_ExistentUser_isCached() {
    // Before the test, clear the cache to ensure a clean state.
    OidcTokenAuthenticationMechanism.userCache.invalidateAll();
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(0);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(0);

    // First call: This should be a cache miss, triggering the loader.
    AuthResult authResult1 = authenticationMechanism.authenticate(request);
    assertThat(authResult1.isAuthenticated()).isTrue();
    assertThat(authResult1.user().get()).isEqualTo(user);

    // Verify a cache miss occurred and the cache now has one entry.
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(1);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(0);
    assertThat(OidcTokenAuthenticationMechanism.userCache.estimatedSize()).isEqualTo(1);

    // Second call for the same user: This should be a cache hit.
    AuthResult authResult2 = authenticationMechanism.authenticate(request);
    assertThat(authResult2.isAuthenticated()).isTrue();
    assertThat(authResult2.user().get()).isEqualTo(user);

    // Verify a cache hit occurred. The miss count should be unchanged.
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(1);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(1);
  }

  @Test
  void testAuthenticate_nonExistentUser_isCached() throws Exception {
    // Before the test, clear the cache to ensure a clean state.
    OidcTokenAuthenticationMechanism.userCache.invalidateAll();
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(0);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(0);

    // Use an email that is not in the test database.
    payload.setEmail("bad-guy@evil.real");

    // First call: This should be a cache miss for a user that does not exist.
    // The result should be NOT_AUTHENTICATED because there's no service account fallback.
    AuthResult authResult1 = authenticationMechanism.authenticate(request);
    assertThat(authResult1).isEqualTo(AuthResult.NOT_AUTHENTICATED);

    // Verify a cache miss occurred and the cache now stores the "not found" result.
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(1);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(0);
    assertThat(OidcTokenAuthenticationMechanism.userCache.estimatedSize()).isEqualTo(1);

    // Second call for the same non-existent user: This should be a cache hit.
    AuthResult authResult2 = authenticationMechanism.authenticate(request);
    assertThat(authResult2).isEqualTo(AuthResult.NOT_AUTHENTICATED);

    // Verify a cache hit occurred. The miss count should be unchanged.
    // This proves that we did not go back to the database.
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount()).isEqualTo(1);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount()).isEqualTo(1);
  }

  @Test
  void testAuthenticate_whenCacheIsDisabled_cacheIsNotUsed() {
    // Arrange: Explicitly disable the cache for this test.
    RegistryConfig.overrideIsUserAuthCachingEnabledForTesting(false);
    // Get the initial cache statistics *after* the test setup has run.
    long initialMissCount = OidcTokenAuthenticationMechanism.userCache.stats().missCount();
    long initialHitCount = OidcTokenAuthenticationMechanism.userCache.stats().hitCount();

    // Act: Authenticate the same user twice.
    AuthResult authResult1 = authenticationMechanism.authenticate(request);
    AuthResult authResult2 = authenticationMechanism.authenticate(request);

    // Assert: Both authentications should succeed by hitting the database.
    assertThat(authResult1.isAuthenticated()).isTrue();
    assertThat(authResult2.isAuthenticated()).isTrue();

    // Assert: The cache statistics should NOT have changed, proving the cache was bypassed.
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().missCount())
        .isEqualTo(initialMissCount);
    assertThat(OidcTokenAuthenticationMechanism.userCache.stats().hitCount())
        .isEqualTo(initialHitCount);

    // Teardown: Restore the default setting for other tests.
    RegistryConfig.overrideIsUserAuthCachingEnabledForTesting(true);
  }

  @Singleton
  @Component(modules = {AuthModule.class, TestModule.class})
  interface TestComponent {
    IapOidcAuthenticationMechanism iapOidcAuthenticationMechanism();

    RegularOidcAuthenticationMechanism regularOidcAuthenticationMechanism();
  }

  @Module
  static class TestModule {
    @Provides
    @Singleton
    @Config("projectIdNumber")
    long provideProjectIdNumber() {
      return 12345;
    }

    @Provides
    @Singleton
    @Config("projectId")
    String provideProjectId() {
      return "my-project";
    }

    @Provides
    @Singleton
    @Config("allowedServiceAccountEmails")
    ImmutableSet<String> provideAllowedServiceAccountEmails() {
      return serviceAccounts;
    }

    @Provides
    @Singleton
    @Config("oauthClientId")
    String provideOauthClientId() {
      return "client-id";
    }

    @Provides
    @Singleton
    @ApplicationDefaultCredential
    GoogleCredentialsBundle provideGoogleCredentialBundle() {
      return GoogleCredentialsBundle.create(GoogleCredentials.newBuilder().build());
    }
  }

  private void reinitializeCache() {
    OidcTokenAuthenticationMechanism.userCache =
        CacheUtils.newCacheBuilder(getUserAuthCachingDuration())
            .maximumSize(getUserAuthMaxCachedEntries())
            .recordStats()
            .build(OidcTokenAuthenticationMechanism::loadUser);
  }
}
