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
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.request.auth.RequestAuthenticator.AuthMethod;
import google.registry.request.auth.RequestAuthenticator.AuthSettings;
import google.registry.request.auth.RequestAuthenticator.UserPolicy;
import google.registry.security.XsrfTokenManager;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeOAuthService;
import google.registry.testing.FakeUserService;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RequestAuthenticator}. */
class RequestAuthenticatorTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private static final AuthSettings AUTH_NONE =
      AuthSettings.create(
          ImmutableList.of(AuthMethod.INTERNAL), AuthLevel.NONE, UserPolicy.IGNORED);

  private static final AuthSettings AUTH_INTERNAL_OR_ADMIN = AuthSettings.create(
      ImmutableList.of(AuthMethod.INTERNAL),
      AuthLevel.APP,
      UserPolicy.IGNORED);

  private static final AuthSettings AUTH_ANY_USER_ANY_METHOD = AuthSettings.create(
      ImmutableList.of(AuthMethod.API, AuthMethod.LEGACY),
      AuthLevel.USER,
      UserPolicy.PUBLIC);

  private static final AuthSettings AUTH_ANY_USER_NO_LEGACY = AuthSettings.create(
      ImmutableList.of(AuthMethod.API),
      AuthLevel.USER,
      UserPolicy.PUBLIC);

  private static final AuthSettings AUTH_ADMIN_USER_ANY_METHOD = AuthSettings.create(
      ImmutableList.of(AuthMethod.API, AuthMethod.LEGACY),
      AuthLevel.USER,
      UserPolicy.ADMIN);

  private static final AuthSettings AUTH_NO_METHODS = AuthSettings.create(
      ImmutableList.of(),
      AuthLevel.APP,
      UserPolicy.IGNORED);

  private static final AuthSettings AUTH_WRONG_METHOD_ORDERING = AuthSettings.create(
      ImmutableList.of(AuthMethod.API, AuthMethod.INTERNAL),
      AuthLevel.APP,
      UserPolicy.IGNORED);

  private static final AuthSettings AUTH_DUPLICATE_METHODS = AuthSettings.create(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API, AuthMethod.API),
      AuthLevel.APP,
      UserPolicy.IGNORED);

  private static final AuthSettings AUTH_INTERNAL_WITH_USER = AuthSettings.create(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API),
      AuthLevel.USER,
      UserPolicy.IGNORED);

  private static final AuthSettings AUTH_WRONGLY_IGNORING_USER = AuthSettings.create(
      ImmutableList.of(AuthMethod.INTERNAL, AuthMethod.API),
      AuthLevel.APP,
      UserPolicy.IGNORED);

  private final UserService mockUserService = mock(UserService.class);
  private final HttpServletRequest req = mock(HttpServletRequest.class);

  private final User testUser = new User("test@google.com", "test@google.com");
  private final FakeUserService fakeUserService = new FakeUserService();
  private final XsrfTokenManager xsrfTokenManager =
      new XsrfTokenManager(new FakeClock(), fakeUserService);
  private final FakeOAuthService fakeOAuthService = new FakeOAuthService(
      false /* isOAuthEnabled */,
      testUser,
      false /* isUserAdmin */,
      "test-client-id",
      ImmutableList.of("test-scope1", "test-scope2", "nontest-scope"));

  @BeforeEach
  void beforeEach() {
    when(req.getMethod()).thenReturn("POST");
  }

  private RequestAuthenticator createRequestAuthenticator(UserService userService) {
    return new RequestAuthenticator(
        new AppEngineInternalAuthenticationMechanism(fakeUserService),
        ImmutableList.of(
            new OAuthAuthenticationMechanism(
                fakeOAuthService,
                ImmutableSet.of("test-scope1", "test-scope2", "test-scope3"),
                ImmutableSet.of("test-scope1", "test-scope2"),
                ImmutableSet.of("test-client-id", "other-test-client-id"))),
        new LegacyAuthenticationMechanism(userService, xsrfTokenManager));
  }

  private Optional<AuthResult> runTest(UserService userService, AuthSettings auth) {
    return createRequestAuthenticator(userService)
        .authorize(auth, req);
  }

  @Test
  void testNoAuthNeeded_noneFound() {
    Optional<AuthResult> authResult = runTest(mockUserService, AUTH_NONE);

    verifyNoInteractions(mockUserService);
    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.NONE);
  }

  @Test
  void testNoAuthNeeded_internalFound() {
    when(req.getHeader("X-AppEngine-QueueName")).thenReturn("__cron");

    Optional<AuthResult> authResult = runTest(mockUserService, AUTH_NONE);

    verifyNoInteractions(mockUserService);
    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.APP);
    assertThat(authResult.get().userAuthInfo()).isEmpty();
  }

  @Test
  void testInternalAuth_notInvokedInternally() {
    Optional<AuthResult> authResult = runTest(mockUserService, AUTH_INTERNAL_OR_ADMIN);

    verifyNoInteractions(mockUserService);
    assertThat(authResult).isEmpty();
  }

  @Test
  void testInternalAuth_success() {
    when(req.getHeader("X-AppEngine-QueueName")).thenReturn("__cron");

    Optional<AuthResult> authResult = runTest(mockUserService, AUTH_INTERNAL_OR_ADMIN);

    verifyNoInteractions(mockUserService);
    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.APP);
    assertThat(authResult.get().userAuthInfo()).isEmpty();
  }

  @Test
  void testInternalAuth_failForAdminUser() {
    when(req.getHeader("X-AppEngine-QueueName")).thenReturn("__cron");
    fakeUserService.setUser(testUser, true /* isAdmin */);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_INTERNAL_OR_ADMIN);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAnyUserAnyMethod_notLoggedIn() {
    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_ANY_METHOD);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAnyUserAnyMethod_xsrfFailure() {
    fakeUserService.setUser(testUser, false);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_ANY_METHOD);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAnyUserAnyMethod_success() {
    fakeUserService.setUser(testUser, false /* isAdmin */);
    when(req.getHeader(XsrfTokenManager.X_CSRF_TOKEN))
        .thenReturn(xsrfTokenManager.generateToken(testUser.getEmail()));

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_ANY_METHOD);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().isUserAdmin()).isFalse();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isEmpty();
  }

  @Test
  void testAnyUserAnyMethod_xsrfNotRequiredForGet() {
    fakeUserService.setUser(testUser, false);
    when(req.getMethod()).thenReturn("GET");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_ANY_METHOD);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isEmpty();
  }

  @Test
  void testAdminUserAnyMethod_notLoggedIn() {
    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ADMIN_USER_ANY_METHOD);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAdminUserAnyMethod_notAdminUser() {
    fakeUserService.setUser(testUser, false /* isAdmin */);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ADMIN_USER_ANY_METHOD);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAdminUserAnyMethod_xsrfFailure() {
    fakeUserService.setUser(testUser, true);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ADMIN_USER_ANY_METHOD);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testAdminUserAnyMethod_success() {
    fakeUserService.setUser(testUser, true /* isAdmin */);
    when(req.getHeader(XsrfTokenManager.X_CSRF_TOKEN))
        .thenReturn(xsrfTokenManager.generateToken(testUser.getEmail()));

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ADMIN_USER_ANY_METHOD);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().isUserAdmin()).isTrue();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isEmpty();
  }

  @Test
  void testOAuth_success() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().isUserAdmin()).isFalse();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().authorizedScopes())
        .containsAtLeast("test-scope1", "test-scope2");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().oauthClientId())
      .isEqualTo("test-client-id");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().rawAccessToken())
      .isEqualTo("TOKEN");
  }

  @Test
  void testOAuthAdmin_success() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setUserAdmin(true);
    fakeOAuthService.setOAuthEnabled(true);
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().isUserAdmin()).isTrue();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().authorizedScopes())
        .containsAtLeast("test-scope1", "test-scope2");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().oauthClientId())
      .isEqualTo("test-client-id");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().rawAccessToken())
      .isEqualTo("TOKEN");
  }

  @Test
  void testOAuthMissingAuthenticationToken_failure() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testOAuthClientIdMismatch_failure() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);
    fakeOAuthService.setClientId("wrong-client-id");
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testOAuthNoScopes_failure() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);
    fakeOAuthService.setAuthorizedScopes();
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testOAuthMissingScope_failure() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);
    fakeOAuthService.setAuthorizedScopes("test-scope1", "test-scope3");
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testOAuthExtraScope_success() {
    fakeOAuthService.setUser(testUser);
    fakeOAuthService.setOAuthEnabled(true);
    fakeOAuthService.setAuthorizedScopes("test-scope1", "test-scope2", "test-scope3");
    when(req.getHeader(AUTHORIZATION)).thenReturn("Bearer TOKEN");

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isPresent();
    assertThat(authResult.get().authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.get().userAuthInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().user()).isEqualTo(testUser);
    assertThat(authResult.get().userAuthInfo().get().isUserAdmin()).isFalse();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo()).isPresent();
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().authorizedScopes())
        .containsAtLeast("test-scope1", "test-scope2", "test-scope3");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().oauthClientId())
      .isEqualTo("test-client-id");
    assertThat(authResult.get().userAuthInfo().get().oauthTokenInfo().get().rawAccessToken())
      .isEqualTo("TOKEN");
  }

  @Test
  void testAnyUserNoLegacy_failureWithLegacyUser() {
    fakeUserService.setUser(testUser, false /* isAdmin */);

    Optional<AuthResult> authResult = runTest(fakeUserService, AUTH_ANY_USER_NO_LEGACY);

    assertThat(authResult).isEmpty();
  }

  @Test
  void testCheckAuthConfig_NoMethods_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(AUTH_NO_METHODS));
    assertThat(thrown).hasMessageThat().contains("Must specify at least one auth method");
  }

  @Test
  void testCheckAuthConfig_WrongMethodOrdering_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(AUTH_WRONG_METHOD_ORDERING));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Auth methods must be unique and strictly in order - INTERNAL, API, LEGACY");
  }

  @Test
  void testCheckAuthConfig_DuplicateMethods_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(AUTH_DUPLICATE_METHODS));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Auth methods must be unique and strictly in order - INTERNAL, API, LEGACY");
  }

  @Test
  void testCheckAuthConfig_InternalWithUser_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(AUTH_INTERNAL_WITH_USER));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Actions with INTERNAL auth method may not require USER auth level");
  }

  @Test
  void testCheckAuthConfig_WronglyIgnoringUser_failure() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(AUTH_WRONGLY_IGNORING_USER));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Actions with auth methods beyond INTERNAL must not specify the IGNORED user policy");
  }
}
