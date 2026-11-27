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
import static google.registry.request.auth.AuthResult.NOT_AUTHENTICATED;
import static google.registry.request.auth.AuthSettings.AuthLevel.APP;
import static google.registry.request.auth.AuthSettings.AuthLevel.NONE;
import static google.registry.request.auth.AuthSettings.AuthLevel.USER;
import static google.registry.request.auth.AuthSettings.UserPolicy.ADMIN;
import static google.registry.request.auth.AuthSettings.UserPolicy.PUBLIC;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.TestLogHandler;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthSettings.UserPolicy;
import google.registry.util.JdkLoggerConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RequestAuthenticator}. */
class RequestAuthenticatorTest {

  private static final AuthResult APP_AUTH = AuthResult.createApp("app@registry.example");

  private static final AuthResult USER_PUBLIC_AUTH =
      AuthResult.createUser(
          new User.Builder()
              .setEmailAddress("user@registry.example")
              .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).build())
              .build());

  private static final AuthResult USER_ADMIN_AUTH =
      AuthResult.createUser(
          new User.Builder()
              .setEmailAddress("admin@registry.example")
              .setUserRoles(
                  new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
              .build());

  private final HttpServletRequest req = mock(HttpServletRequest.class);

  private final AuthenticationMechanism apiAuthenticationMechanism1 =
      mock(AuthenticationMechanism.class);
  private final AuthenticationMechanism apiAuthenticationMechanism2 =
      mock(AuthenticationMechanism.class);
  private final TestLogHandler logHandler = new TestLogHandler();

  private Optional<AuthResult> authorize(AuthLevel authLevel, UserPolicy userPolicy) {
    return new RequestAuthenticator(
            ImmutableList.of(apiAuthenticationMechanism1, apiAuthenticationMechanism2))
        .authorize(new AuthSettings(authLevel, userPolicy), req);
  }

  private AuthResult authenticate() {
    return new RequestAuthenticator(
            ImmutableList.of(apiAuthenticationMechanism1, apiAuthenticationMechanism2))
        .authenticate(new AuthSettings(NONE, PUBLIC), req);
  }

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(RequestAuthenticator.class).addHandler(logHandler);
    JdkLoggerConfig.getConfig(RequestAuthenticator.class).setLevel(Level.FINE);
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(NOT_AUTHENTICATED);
    when(apiAuthenticationMechanism2.authenticate(req)).thenReturn(NOT_AUTHENTICATED);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(RequestAuthenticator.class).removeHandler(logHandler);
    JdkLoggerConfig.getConfig(RequestAuthenticator.class).setLevel(null);
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
    assertThat(authenticate()).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
  }

  @Test
  void testAuthenticate_apiSecond() {
    when(apiAuthenticationMechanism2.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authenticate()).isEqualTo(APP_AUTH);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
  }

  @Test
  void testAuthenticate_notAuthenticated() {
    assertThat(authenticate()).isEqualTo(NOT_AUTHENTICATED);
    verify(apiAuthenticationMechanism1).authenticate(req);
    verify(apiAuthenticationMechanism2).authenticate(req);
    verifyNoMoreInteractions(apiAuthenticationMechanism1);
    verifyNoMoreInteractions(apiAuthenticationMechanism2);
  }

  @Test
  void testFailure_checkAuthConfig_noneAuthLevelRequiresAdmin() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> RequestAuthenticator.checkAuthConfig(new AuthSettings(NONE, ADMIN)));
    assertThat(thrown)
        .hasMessageThat()
        .contains("Actions with minimal auth level at NONE should not specify ADMIN user policy");
  }

  @Test
  void testAuthorize_userAuth_logsAuthenticatedViaAtInfo() {
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(USER_PUBLIC_AUTH);
    assertThat(authorize(USER, PUBLIC)).hasValue(USER_PUBLIC_AUTH);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Action requires auth:");
    assertAboutLogs().that(logHandler).hasLogAtLevelWithMessage(Level.INFO, "Authenticated via");
  }

  @Test
  void testAuthorize_appAuth_logsAuthenticatedViaAtFine() {
    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authorize(APP, PUBLIC)).hasValue(APP_AUTH);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Action requires auth:");
    assertAboutLogs().that(logHandler).hasLogAtLevelWithMessage(Level.FINE, "Authenticated via");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);
  }

  @Test
  void testAuthorize_unauthenticatedNoneRequired_logsFineOnly() {
    assertThat(authorize(NONE, PUBLIC)).hasValue(NOT_AUTHENTICATED);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Action requires auth:");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "No authentication found.");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);
  }

  @Test
  void testAuthorize_unauthorized_logsWarningAndFine() {
    assertThat(authorize(APP, PUBLIC)).isEmpty();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "Action requires auth:");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.FINE, "No authentication found.");
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.WARNING, "Not authorized; no authentication found.");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);

    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(APP_AUTH);
    assertThat(authorize(USER, PUBLIC)).isEmpty();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(Level.WARNING, "Not authorized; no authenticated user.");
    assertAboutLogs().that(logHandler).hasNoLogsAtLevel(Level.INFO);

    when(apiAuthenticationMechanism1.authenticate(req)).thenReturn(USER_PUBLIC_AUTH);
    assertThat(authorize(USER, ADMIN)).isEmpty();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.WARNING, "Not authorized; user policy is ADMIN, but the user was not an admin.");
  }
}
