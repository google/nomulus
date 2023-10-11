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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.request.auth.AuthResult.NOT_AUTHENTICATED;
import static google.registry.request.auth.AuthSettings.AuthLevel.APP;
import static google.registry.request.auth.AuthSettings.AuthLevel.NONE;
import static google.registry.request.auth.AuthSettings.AuthLevel.USER;
import static google.registry.request.auth.AuthSettings.AuthMethod.API;
import static google.registry.request.auth.AuthSettings.AuthMethod.LEGACY;
import static google.registry.request.auth.AuthSettings.UserPolicy.ADMIN;
import static google.registry.request.auth.AuthSettings.UserPolicy.PUBLIC;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthSettings.AuthMethod;
import google.registry.request.auth.AuthSettings.UserPolicy;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestAuthenticator}. */
class RequestAuthenticatorTest {

  private static final AuthResult APP_AUTH = AuthResult.createApp("app@registry.example");

  private static final AuthResult USER_PUBLIC_AUTH =
      AuthResult.createUser(
          UserAuthInfo.create(
              new User.Builder()
                  .setEmailAddress("user@registry.example")
                  .setUserRoles(
                      new UserRoles.Builder()
                          .setIsAdmin(false)
                          .setGlobalRole(GlobalRole.NONE)
                          .build())
                  .build()));

  private static final AuthResult USER_ADMIN_AUTH =
      AuthResult.createUser(
          UserAuthInfo.create(
              new User.Builder()
                  .setEmailAddress("admin@registry.example")
                  .setUserRoles(
                      new UserRoles.Builder()
                          .setIsAdmin(true)
                          .setGlobalRole(GlobalRole.FTE)
                          .build())
                  .build()));

  private final HttpServletRequest req = mock(HttpServletRequest.class);

  private final AuthenticationMechanism apiAuthenticationMechanism1 =
      mock(AuthenticationMechanism.class);
  private final AuthenticationMechanism apiAuthenticationMechanism2 =
      mock(AuthenticationMechanism.class);
  private final LegacyAuthenticationMechanism legacyAuthenticationMechanism =
      mock(LegacyAuthenticationMechanism.class);

  private Optional<AuthResult> authorize(AuthLevel authLevel, UserPolicy userPolicy) {
    return new RequestAuthenticator(
            ImmutableList.of(apiAuthenticationMechanism1, apiAuthenticationMechanism2),
            legacyAuthenticationMechanism)
        .authorize(AuthSettings.create(ImmutableList.of(API, LEGACY), authLevel, userPolicy), req);
  }

  private AuthResult authenticate(AuthMethod... methods) {
    return new RequestAuthenticator(
            ImmutableList.of(apiAuthenticationMechanism1, apiAuthenticationMechanism2),
            legacyAuthenticationMechanism)
        .authenticate(AuthSettings.create(ImmutableList.copyOf(methods), NONE, PUBLIC), req);
  }

  @BeforeEach
  void beforeEach() {
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(NOT_AUTHENTICATED);
    when(apiAuthenticationMechanism2.authenticate(req)).thenReturn(NOT_AUTHENTICATED);
    when(legacyAuthenticationMechanism.authenticate(req)).thenReturn(NOT_AUTHENTICATED);
  }

  @Test
  void testAuthorize_noneRequired() {
    for (AuthResult resultFound :
        ImmutableList.of(NOT_AUTHENTICATED, APP_AUTH, USER_ADMIN_AUTH, USER_PUBLIC_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(NONE, PUBLIC)).hasValue(resultFound);
    }
  }

  @Test
  void testAuthorize_appPublicRequired() {
    authorize(APP, PUBLIC);
    assertThat(authorize(APP, PUBLIC)).isEmpty();

    for (AuthResult resultFound : ImmutableList.of(APP_AUTH, USER_ADMIN_AUTH, USER_PUBLIC_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(APP, PUBLIC)).hasValue(resultFound);
    }
  }

  @Test
  void testAuthorize_appAdminRequired() {
    for (AuthResult resultFound : ImmutableList.of(NOT_AUTHENTICATED, USER_PUBLIC_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(APP, ADMIN)).isEmpty();
    }

    for (AuthResult resultFound : ImmutableList.of(APP_AUTH, USER_ADMIN_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(APP, ADMIN)).hasValue(resultFound);
    }
  }

  @Test
  void testAuthorize_userPublicRequired() {
    for (AuthResult resultFound : ImmutableList.of(NOT_AUTHENTICATED, APP_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(USER, PUBLIC)).isEmpty();
    }

    for (AuthResult resultFound : ImmutableList.of(USER_PUBLIC_AUTH, USER_ADMIN_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(USER, PUBLIC)).hasValue(resultFound);
    }
  }

  @Test
  void testAuthorize_userAdminRequired() {
    for (AuthResult resultFound : ImmutableList.of(NOT_AUTHENTICATED, APP_AUTH, USER_PUBLIC_AUTH)) {
      when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(resultFound);
      assertThat(authorize(USER, ADMIN)).isEmpty();
    }

    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(USER_ADMIN_AUTH);
    assertThat(authorize(USER, ADMIN)).hasValue(USER_ADMIN_AUTH);
  }

  @Test
  void testAuthenticate_apiFirst() {
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authenticate(API, LEGACY)).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_apiSecond() {
    when(apiAuthenticationMechanism2.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authenticate(API, LEGACY)).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_legacy() {
    when(legacyAuthenticationMechanism.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authenticate(API, LEGACY)).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verify(legacyAuthenticationMechanism).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_returnFirstResult() {
    // API auth 2 returns an authenticted auth result, so we don't bother trying the next auth
    // (legacy auth).
    when(apiAuthenticationMechanism2.authenticate(req)).thenReturn(APP_AUTH);
    when(legacyAuthenticationMechanism.authenticate(req)).thenReturn(USER_PUBLIC_AUTH);
    assertThat(authenticate(API, LEGACY)).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_notAuthenticated() {
    assertThat(authenticate(API, LEGACY)).isEqualTo(NOT_AUTHENTICATED);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verify(legacyAuthenticationMechanism).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_apiOnly() {
    when(legacyAuthenticationMechanism.authenticate(req)).thenReturn(USER_PUBLIC_AUTH);
    assertThat(authenticate(API)).isEqualTo(NOT_AUTHENTICATED);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testAuthenticate_legacyOnly() {
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(USER_PUBLIC_AUTH);
    assertThat(authenticate(LEGACY)).isEqualTo(NOT_AUTHENTICATED);
    verify(legacyAuthenticationMechanism).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
    verifyNoMoreInteractions(legacyAuthenticationMechanism);
  }

  @Test
  void testFailure_checkAuthConfig_noMethods() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RequestAuthenticator.checkAuthConfig(
                    AuthSettings.create(ImmutableList.of(), NONE, PUBLIC)));
    assertThat(thrown).hasMessageThat().contains("Must specify at least one auth method");
  }

  @Test
  void testFailure_checkAuthConfig_wrongMethodOrder() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RequestAuthenticator.checkAuthConfig(
                    AuthSettings.create(ImmutableList.of(LEGACY, API), NONE, PUBLIC)));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Auth methods must be unique and strictly in order - API, LEGACY");
  }

  @Test
  void testFailure_CheckAuthConfig_duplicateMethods() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RequestAuthenticator.checkAuthConfig(
                    AuthSettings.create(ImmutableList.of(API, API), NONE, PUBLIC)));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Auth methods must be unique and strictly in order - API, LEGACY");
  }

  @Test
  void testFailure_checkAuthConfig_noneAuthLevelRequiresAdmin() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                RequestAuthenticator.checkAuthConfig(
                    AuthSettings.create(ImmutableList.of(API, LEGACY), NONE, ADMIN)));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Actions with minimal auth level at NONE should not specify ADMIN user policy");
  }
}
