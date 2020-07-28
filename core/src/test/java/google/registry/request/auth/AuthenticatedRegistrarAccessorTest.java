// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.testing.AppEngineExtension.THE_REGISTRAR_GAE_USER_ID;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.flogger.LoggerConfig;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.TestLogHandler;
import dagger.Lazy;
import google.registry.groups.GroupsConnection;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.InjectRule;
import java.util.Optional;
import java.util.logging.Level;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link AuthenticatedRegistrarAccessor}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticatedRegistrarAccessorTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @RegisterExtension final InjectRule inject = new InjectRule();

  @Mock private HttpServletRequest req;
  @Mock private HttpServletResponse rsp;
  @Mock private GroupsConnection groupsConnection;
  @Mock private Lazy<GroupsConnection> lazyGroupsConnection;

  private final TestLogHandler testLogHandler = new TestLogHandler();

  private static final AuthResult USER = createAuthResult(false);
  private static final AuthResult GAE_ADMIN = createAuthResult(true);
  private static final AuthResult NO_USER = AuthResult.create(AuthLevel.NONE);
  private static final Optional<String> SUPPORT_GROUP = Optional.of("support@registry.example");
  /** Client ID of a REAL registrar with a RegistrarContact for USER and GAE_ADMIN. */
  private static final String CLIENT_ID_WITH_CONTACT = "TheRegistrar";
  /** Client ID of a REAL registrar without a RegistrarContact. */
  private static final String REAL_CLIENT_ID_WITHOUT_CONTACT = "NewRegistrar";
  /** Client ID of an OTE registrar without a RegistrarContact. */
  private static final String OTE_CLIENT_ID_WITHOUT_CONTACT = "OteRegistrar";
  /** Client ID of the Admin registrar without a RegistrarContact. */
  private static final String ADMIN_CLIENT_ID = "AdminRegistrar";

  /**
   * Creates an AuthResult for a fake user.
   *
   * <p>The user will be a RegistrarContact for "TheRegistrar", but not for "NewRegistrar".
   *
   * @param isAdmin if true, the user is an administrator for the app-engine project.
   */
  private static AuthResult createAuthResult(boolean isAdmin) {
    return AuthResult.create(
        AuthLevel.USER,
        UserAuthInfo.create(
            new User(
                String.format(
                    "%s@gmail.com",
                    isAdmin ? "admin" : "user"),
                "gmail.com",
                THE_REGISTRAR_GAE_USER_ID),
            isAdmin));
  }

  @BeforeEach
  void beforeEach() {
    when(lazyGroupsConnection.get()).thenReturn(groupsConnection);
    LoggerConfig.getConfig(AuthenticatedRegistrarAccessor.class).addHandler(testLogHandler);
    // persistResource(loadRegistrar(ADMIN_CLIENT_ID));
    persistResource(
        loadRegistrar(REAL_CLIENT_ID_WITHOUT_CONTACT)
            .asBuilder()
            .setClientId(OTE_CLIENT_ID_WITHOUT_CONTACT)
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .build());
    persistResource(
        loadRegistrar(REAL_CLIENT_ID_WITHOUT_CONTACT)
            .asBuilder()
            .setClientId(ADMIN_CLIENT_ID)
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .build());
    when(groupsConnection.isMemberOfGroup(any(), any())).thenReturn(false);
  }

  @AfterEach
  void afterEach() {
    LoggerConfig.getConfig(AuthenticatedRegistrarAccessor.class).removeHandler(testLogHandler);
  }

  /** Users are owners for registrars if and only if they are in the contacts for that registrar. */
  @Test
  void getAllClientIdWithAccess_user() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllClientIdWithRoles())
        .containsExactly(CLIENT_ID_WITH_CONTACT, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Logged out users don't have access to anything. */
  @Test
  void getAllClientIdWithAccess_loggedOutUser() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            NO_USER, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllClientIdWithRoles()).isEmpty();
    verifyNoInteractions(lazyGroupsConnection);
  }

  /**
   * GAE admins have admin access to everything.
   *
   * <p>They also have OWNER access if they are in the RegistrarContacts.
   *
   * <p>They also have OWNER access to the Admin Registrar.
   *
   * <p>They also have OWNER access to non-REAL Registrars.
   *
   * <p>(in other words - they don't have OWNER access only to REAL registrars owned by others)
   */
  @Test
  void getAllClientIdWithAccess_gaeAdmin() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            GAE_ADMIN, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllClientIdWithRoles())
        .containsExactly(
            CLIENT_ID_WITH_CONTACT, ADMIN,
            CLIENT_ID_WITH_CONTACT, OWNER,

            REAL_CLIENT_ID_WITHOUT_CONTACT, ADMIN,

            OTE_CLIENT_ID_WITHOUT_CONTACT, ADMIN,
            OTE_CLIENT_ID_WITHOUT_CONTACT, OWNER,

            ADMIN_CLIENT_ID, ADMIN,
            ADMIN_CLIENT_ID, OWNER);
    verifyNoInteractions(lazyGroupsConnection);
  }

  /**
   * Users in support group have admin access to everything.
   *
   * <p>They also have OWNER access if they are in the RegistrarContacts.
   *
   * <p>They also have OWNER access to the Admin Registrar.
   *
   * <p>They also have OWNER access to non-REAL Registrars.
   *
   * <p>(in other words - they don't have OWNER access only to REAL registrars owned by others)
   */
  @Test
  void getAllClientIdWithAccess_userInSupportGroup() {
    when(groupsConnection.isMemberOfGroup("user@gmail.com", SUPPORT_GROUP.get())).thenReturn(true);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllClientIdWithRoles())
        .containsExactly(
            CLIENT_ID_WITH_CONTACT, ADMIN,
            CLIENT_ID_WITH_CONTACT, OWNER,

            REAL_CLIENT_ID_WITHOUT_CONTACT, ADMIN,

            OTE_CLIENT_ID_WITHOUT_CONTACT, ADMIN,
            OTE_CLIENT_ID_WITHOUT_CONTACT, OWNER,

            ADMIN_CLIENT_ID, ADMIN,
            ADMIN_CLIENT_ID, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Empty Support group email - skips check and doesn't generate the lazy. */
  @Test
  void getAllClientIdWithAccess_emptySupportEmail_works() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_CLIENT_ID, Optional.empty(), lazyGroupsConnection);

    assertThat(registrarAccessor.getAllClientIdWithRoles())
        .containsExactly(CLIENT_ID_WITH_CONTACT, OWNER);
    // Make sure we didn't instantiate the lazyGroupsConnection
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Support group check throws - continue anyway. */
  @Test
  void getAllClientIdWithAccess_throwingGroupCheck_stillWorks() {
    when(groupsConnection.isMemberOfGroup(any(), any())).thenThrow(new RuntimeException("blah"));
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    verify(groupsConnection).isMemberOfGroup("user@gmail.com", SUPPORT_GROUP.get());
    assertThat(registrarAccessor.getAllClientIdWithRoles())
        .containsExactly(CLIENT_ID_WITH_CONTACT, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if user doesn't have access to it. */
  @Test
  void testGetRegistrarForUser_noAccess_isNotAdmin() {
    expectGetRegistrarFailure(
        REAL_CLIENT_ID_WITHOUT_CONTACT,
        USER,
        "user user@gmail.com doesn't have access to registrar NewRegistrar");
    verify(lazyGroupsConnection).get();
  }

  @Test
  void testGetRegistrarForUser_registrarIsDisabled_isNotAdmin() {
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.DISABLED)
            .build());
    expectGetRegistrarFailure(
        CLIENT_ID_WITH_CONTACT,
        USER,
        "user user@gmail.com doesn't have access to registrar TheRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if user doesn't have access to it, even if it's not REAL. */
  @Test
  void testGetRegistrarForUser_noAccess_isNotAdmin_notReal() {
    expectGetRegistrarFailure(
        OTE_CLIENT_ID_WITHOUT_CONTACT,
        USER,
        "user user@gmail.com doesn't have access to registrar OteRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if there's no user associated with the request. */
  @Test
  void testGetRegistrarForUser_noUser() {
    expectGetRegistrarFailure(
        CLIENT_ID_WITH_CONTACT,
        NO_USER,
        "<logged-out user> doesn't have access to registrar TheRegistrar");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading registrar if user has access to it. */
  @Test
  void testGetRegistrarForUser_inContacts_isNotAdmin() throws Exception {
    expectGetRegistrarSuccess(
        CLIENT_ID_WITH_CONTACT,
        USER,
        "user user@gmail.com has [OWNER] access to registrar TheRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Succeed loading registrar if admin with access. */
  @Test
  void testGetRegistrarForUser_inContacts_isAdmin() throws Exception {
    expectGetRegistrarSuccess(
        CLIENT_ID_WITH_CONTACT,
        GAE_ADMIN,
        "admin admin@gmail.com has [OWNER, ADMIN] access to registrar TheRegistrar");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading registrar for admin even if they aren't on the approved contacts list. */
  @Test
  void testGetRegistrarForUser_notInContacts_isAdmin() throws Exception {
    expectGetRegistrarSuccess(
        REAL_CLIENT_ID_WITHOUT_CONTACT,
        GAE_ADMIN,
        "admin admin@gmail.com has [ADMIN] access to registrar NewRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  @Test
  void testGetRegistrarForUser_registrarIsDisabled_isAdmin() throws Exception {
    persistResource(
        Registrar.loadByClientId("NewRegistrar")
            .get()
            .asBuilder()
            .setState(State.DISABLED)
            .build());
    expectGetRegistrarSuccess(
        REAL_CLIENT_ID_WITHOUT_CONTACT,
        GAE_ADMIN,
        "admin admin@gmail.com has [OWNER, ADMIN] access to registrar NewRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading non-REAL registrar for admin. */
  @Test
  void testGetRegistrarForUser_notInContacts_isAdmin_notReal() throws Exception {
    expectGetRegistrarSuccess(
        OTE_CLIENT_ID_WITHOUT_CONTACT,
        GAE_ADMIN,
        "admin admin@gmail.com has [OWNER, ADMIN] access to registrar OteRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Fail loading registrar even if admin, if registrar doesn't exist. */
  @Test
  void testGetRegistrarForUser_doesntExist_isAdmin() {
    expectGetRegistrarFailure(
        "BadClientId",
        GAE_ADMIN,
        "Registrar BadClientId does not exist");
    verifyNoInteractions(lazyGroupsConnection);
  }

  private void expectGetRegistrarSuccess(String clientId, AuthResult authResult, String message)
      throws Exception {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // make sure loading the registrar succeeds and returns a value
    assertThat(registrarAccessor.getRegistrar(clientId)).isNotNull();
    assertAboutLogs().that(testLogHandler).hasLogAtLevelWithMessage(Level.INFO, message);
  }

  private void expectGetRegistrarFailure(
      String clientId, AuthResult authResult, String message) {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_CLIENT_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // make sure getRegistrar fails
    RegistrarAccessDeniedException exception =
        assertThrows(
            RegistrarAccessDeniedException.class, () -> registrarAccessor.getRegistrar(clientId));

    assertThat(exception).hasMessageThat().contains(message);
  }

  /** guessClientIdForUser returns the first clientId in getAllClientIdWithRoles. */
  @Test
  void testGuessClientIdForUser_hasAccess_returnsFirst() throws Exception {
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "clientId-1", OWNER,
                "clientId-2", OWNER,
                "clientId-2", ADMIN));

    assertThat(registrarAccessor.guessClientId()).isEqualTo("clientId-1");
  }

  /** If a user doesn't have access to any registrars, guess fails. */
  @Test
  void testGuessClientIdForUser_noAccess_fails() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());

    assertThat(assertThrows(RegistrarAccessDeniedException.class, registrarAccessor::guessClientId))
        .hasMessageThat()
        .isEqualTo("TestUserId isn't associated with any registrar");
  }

  @Test
  void testNullness() {
    new NullPointerTester()
        .setDefault(HttpServletRequest.class, req)
        .setDefault(HttpServletResponse.class, rsp)
        .testAllPublicStaticMethods(AuthenticatedRegistrarAccessor.class);
  }
}
