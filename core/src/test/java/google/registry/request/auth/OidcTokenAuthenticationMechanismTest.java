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
import static google.registry.request.auth.AuthModule.BEARER_PREFIX;
import static google.registry.request.auth.AuthModule.IAP_HEADER_NAME;
import static google.registry.request.auth.AuthModule.PROXY_HEADER_NAME;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebSignature.Header;
import com.google.auth.oauth2.TokenVerifier;
import com.google.auth.oauth2.TokenVerifier.VerificationException;
import com.google.common.collect.ImmutableSet;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.IapOidcAuthenticationMechanism;
import google.registry.request.auth.OidcTokenAuthenticationMechanism.RegularOidcAuthenticationMechanism;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
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
  private final User user =
      new User.Builder()
          .setEmailAddress(email)
          .setGaiaId(gaiaId)
          .setUserRoles(
              new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
          .build();
  private final JsonWebSignature jwt =
      new JsonWebSignature(new Header(), payload, new byte[0], new byte[0]);
  private final TokenVerifier tokenVerifier = mock(TokenVerifier.class);
  private final HttpServletRequest request = mock(HttpServletRequest.class);

  private AuthResult authResult;
  private OidcTokenAuthenticationMechanism authenticationMechanism =
      new OidcTokenAuthenticationMechanism(serviceAccounts, tokenVerifier, e -> rawToken) {};

  @RegisterExtension
  public final JpaTestExtensions.JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(User.class).buildUnitTestExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    when(tokenVerifier.verify(eq(rawToken))).thenReturn(jwt);
    payload.setEmail(email);
    payload.setSubject(gaiaId);
    insertInDb(user);
  }

  @AfterEach
  void afterEach() {
    OidcTokenAuthenticationMechanism.unsetAuthResultForTesting();
  }

  @Test
  void testAuthResultBypass() {
    OidcTokenAuthenticationMechanism.setAuthResultForTesting(AuthResult.create(AuthLevel.APP));
    assertThat(authenticationMechanism.authenticate(null))
        .isEqualTo(AuthResult.create(AuthLevel.APP));
  }

  @Test
  void testAuthenticate_noTokenFromRequest() {
    authenticationMechanism =
        new OidcTokenAuthenticationMechanism(serviceAccounts, tokenVerifier, e -> null) {};
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult).isEqualTo(AuthResult.NOT_AUTHENTICATED);
  }

  @Test
  void testAuthenticate_invalidToken() throws Exception {
    when(tokenVerifier.verify(eq(rawToken))).thenThrow(new VerificationException("Bad token"));
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
    assertThat(authResult.userAuthInfo().get().consoleUser().get()).isEqualTo(user);
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
        new User.Builder()
            .setEmailAddress("service@email.test")
            .setGaiaId("service-gaia-id")
            .setUserRoles(
                new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
            .build();
    insertInDb(serviceUser);
    payload.setEmail("service@email.test");
    authResult = authenticationMechanism.authenticate(request);
    assertThat(authResult.isAuthenticated()).isTrue();
    assertThat(authResult.authLevel()).isEqualTo(AuthLevel.USER);
    assertThat(authResult.userAuthInfo().get().consoleUser().get()).isEqualTo(serviceUser);
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
    when(request.getHeader(PROXY_HEADER_NAME)).thenReturn(rawToken);
    assertThat(authenticationMechanism.tokenExtractor.extract(request)).isNull();

    // The token is in the correct format.
    when(request.getHeader(PROXY_HEADER_NAME))
        .thenReturn(String.format("%s%s", BEARER_PREFIX, rawToken));
    assertThat(authenticationMechanism.tokenExtractor.extract(request)).isEqualTo(rawToken);

    // The token is in the correct format, and under the alternative header.
    when(request.getHeader(PROXY_HEADER_NAME)).thenReturn(null);
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
  }
}
