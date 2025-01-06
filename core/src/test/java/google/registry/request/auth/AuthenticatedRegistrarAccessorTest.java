// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.console.RegistrarRole.ACCOUNT_MANAGER;
import static google.registry.request.auth.AuthResult.NOT_AUTHENTICATED;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.ADMIN;
import static google.registry.request.auth.AuthenticatedRegistrarAccessor.Role.OWNER;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.TestLogHandler;
import dagger.Lazy;
import google.registry.groups.GroupsConnection;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase.State;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.util.JdkLoggerConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.logging.Level;
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
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Mock private HttpServletRequest req;
  @Mock private HttpServletResponse rsp;
  @Mock private GroupsConnection groupsConnection;
  @Mock private Lazy<GroupsConnection> lazyGroupsConnection;

  private final TestLogHandler testLogHandler = new TestLogHandler();

  private static final AuthResult USER = createAuthResult(false);
  private static final AuthResult ADMIN_USER = createAuthResult(true);
  private static final AuthResult NO_USER = NOT_AUTHENTICATED;
  private static final Optional<String> SUPPORT_GROUP = Optional.of("support@registry.example");

  /** Registrar ID of a REAL registrar which the {@link USER} has access to. */
  private static final String REGISTRAR_ID_WITH_ACCESS = "TheRegistrar";

  /** Registrar ID of a REAL registrar which the {@link USER} has no access to. */
  private static final String REAL_REGISTRAR_ID_WITHOUT_ACCESS = "NewRegistrar";

  /** Registrar ID of an OTE registrar without a RegistrarContact. */
  private static final String OTE_REGISTRAR_ID_WITHOUT_CONTACT = "OteRegistrar";

  /** Registrar ID of the Admin registrar without a RegistrarContact. */
  private static final String ADMIN_REGISTRAR_ID = "AdminRegistrar";

  /**
   * Creates an AuthResult for a fake user.
   *
   * <p>The user will have access to TheRegistrar", but not "NewRegistrar".
   */
  private static AuthResult createAuthResult(boolean isAdmin) {
    return AuthResult.createUser(
        new User.Builder()
            .setEmailAddress("johndoe@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setIsAdmin(isAdmin)
                    .setRegistrarRoles(ImmutableMap.of(REGISTRAR_ID_WITH_ACCESS, ACCOUNT_MANAGER))
                    .build())
            .build());
  }

  @BeforeEach
  void beforeEach() {
    when(lazyGroupsConnection.get()).thenReturn(groupsConnection);
    JdkLoggerConfig.getConfig(AuthenticatedRegistrarAccessor.class).addHandler(testLogHandler);
    persistResource(
        loadRegistrar(REAL_REGISTRAR_ID_WITHOUT_ACCESS)
            .asBuilder()
            .setRegistrarId(OTE_REGISTRAR_ID_WITHOUT_CONTACT)
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .build());
    persistResource(
        loadRegistrar(REAL_REGISTRAR_ID_WITHOUT_ACCESS)
            .asBuilder()
            .setRegistrarId(ADMIN_REGISTRAR_ID)
            .setType(Registrar.Type.OTE)
            .setIanaIdentifier(null)
            .build());
    when(groupsConnection.isMemberOfGroup(any(), any())).thenReturn(false);
  }

  @AfterEach
  void afterEach() {
    JdkLoggerConfig.getConfig(AuthenticatedRegistrarAccessor.class).removeHandler(testLogHandler);
  }

  /** Users are owners for registrars if and only if they are in the contacts for that registrar. */
  @Test
  void getAllRegistrarIdWithAccess_user() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(REGISTRAR_ID_WITH_ACCESS, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Logged-out users don't have access to anything. */
  @Test
  void getAllRegistrarIdWithAccess_loggedOutUser() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            NO_USER, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles()).isEmpty();
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
  void getAllRegistrarIdWithAccess_userInSupportGroup() {
    when(groupsConnection.isMemberOfGroup("johndoe@theregistrar.com", SUPPORT_GROUP.get()))
        .thenReturn(true);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(
            REGISTRAR_ID_WITH_ACCESS, ADMIN,
            REGISTRAR_ID_WITH_ACCESS, OWNER,
            REAL_REGISTRAR_ID_WITHOUT_ACCESS, ADMIN,
            OTE_REGISTRAR_ID_WITHOUT_CONTACT, ADMIN,
            OTE_REGISTRAR_ID_WITHOUT_CONTACT, OWNER,
            ADMIN_REGISTRAR_ID, ADMIN,
            ADMIN_REGISTRAR_ID, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Empty Support group email - skips check and doesn't generate the lazy. */
  @Test
  void getAllRegistrarIdWithAccess_emptySupportEmail_works() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_REGISTRAR_ID, Optional.empty(), lazyGroupsConnection);

    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(REGISTRAR_ID_WITH_ACCESS, OWNER);
    // Make sure we didn't instantiate the lazyGroupsConnection
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Support group check throws - continue anyway. */
  @Test
  void getAllRegistrarIdWithAccess_throwingGroupCheck_stillWorks() {
    when(groupsConnection.isMemberOfGroup(any(), any())).thenThrow(new RuntimeException("blah"));
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            USER, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    verify(groupsConnection).isMemberOfGroup("johndoe@theregistrar.com", SUPPORT_GROUP.get());
    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(REGISTRAR_ID_WITH_ACCESS, OWNER);
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if user doesn't have access to it. */
  @Test
  void testGetRegistrarForUser_noAccess_isNotAdmin() {
    expectGetRegistrarFailure(
        REAL_REGISTRAR_ID_WITHOUT_ACCESS,
        USER,
        "user johndoe@theregistrar.com doesn't have access to registrar NewRegistrar");
    verify(lazyGroupsConnection).get();
  }

  @Test
  void testGetRegistrarForUser_registrarIsDisabled_isNotAdmin() {
    persistResource(
        Registrar.loadByRegistrarId(REGISTRAR_ID_WITH_ACCESS)
            .get()
            .asBuilder()
            .setState(State.DISABLED)
            .build());
    expectGetRegistrarFailure(
        REGISTRAR_ID_WITH_ACCESS,
        USER,
        "user johndoe@theregistrar.com doesn't have access to registrar TheRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if user doesn't have access to it, even if it's not REAL. */
  @Test
  void testGetRegistrarForUser_noAccess_isNotAdmin_notReal() {
    expectGetRegistrarFailure(
        OTE_REGISTRAR_ID_WITHOUT_CONTACT,
        USER,
        "user johndoe@theregistrar.com doesn't have access to registrar OteRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Fail loading registrar if there's no user associated with the request. */
  @Test
  void testGetRegistrarForUser_noUser() {
    expectGetRegistrarFailure(
        REGISTRAR_ID_WITH_ACCESS,
        NO_USER,
        "<logged-out user> doesn't have access to registrar TheRegistrar");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading registrar if user has access to it. */
  @Test
  void testGetRegistrarForUser_inContacts_isNotAdmin() throws Exception {
    expectGetRegistrarSuccess(
        REGISTRAR_ID_WITH_ACCESS,
        USER,
        "user johndoe@theregistrar.com has [OWNER] access to registrar TheRegistrar");
    verify(lazyGroupsConnection).get();
  }

  /** Succeed loading registrar if admin with access. */
  @Test
  void testGetRegistrarForUser_inContacts_isAdmin() throws Exception {
    expectGetRegistrarSuccess(
        REGISTRAR_ID_WITH_ACCESS,
        ADMIN_USER,
        "admin johndoe@theregistrar.com has [OWNER, ADMIN] access to registrar TheRegistrar");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading registrar for admin even if they aren't on the approved contacts list. */
  @Test
  void testGetRegistrarForUser_notInContacts_isAdmin() throws Exception {
    expectGetRegistrarSuccess(
        REAL_REGISTRAR_ID_WITHOUT_ACCESS,
        ADMIN_USER,
        "admin johndoe@theregistrar.com has [ADMIN] access to registrar NewRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  @Test
  void testGetRegistrarForUser_registrarIsDisabled_isAdmin() throws Exception {
    persistResource(
        Registrar.loadByRegistrarId("NewRegistrar")
            .get()
            .asBuilder()
            .setState(State.DISABLED)
            .build());
    expectGetRegistrarSuccess(
        REAL_REGISTRAR_ID_WITHOUT_ACCESS,
        ADMIN_USER,
        "admin johndoe@theregistrar.com has [OWNER, ADMIN] access to registrar NewRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Succeed loading non-REAL registrar for admin. */
  @Test
  void testGetRegistrarForUser_notInContacts_isAdmin_notReal() throws Exception {
    expectGetRegistrarSuccess(
        OTE_REGISTRAR_ID_WITHOUT_CONTACT,
        ADMIN_USER,
        "admin johndoe@theregistrar.com has [OWNER, ADMIN] access to registrar OteRegistrar.");
    verifyNoInteractions(lazyGroupsConnection);
  }

  /** Fail loading registrar even if admin, if registrar doesn't exist. */
  @Test
  void testGetRegistrarForUser_doesntExist_isAdmin() {
    expectGetRegistrarFailure(
        "BadRegistrarId", ADMIN_USER, "Registrar BadRegistrarId does not exist");
    verifyNoInteractions(lazyGroupsConnection);
  }

  private void expectGetRegistrarSuccess(String registrarId, AuthResult authResult, String message)
      throws Exception {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // make sure loading the registrar succeeds and returns a value
    assertThat(registrarAccessor.getRegistrar(registrarId)).isNotNull();
    assertAboutLogs().that(testLogHandler).hasLogAtLevelWithMessage(Level.INFO, message);
  }

  private void expectGetRegistrarFailure(
      String registrarId, AuthResult authResult, String message) {
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // make sure getRegistrar fails
    RegistrarAccessDeniedException exception =
        assertThrows(
            RegistrarAccessDeniedException.class,
            () -> registrarAccessor.getRegistrar(registrarId));

    assertThat(exception).hasMessageThat().contains(message);
  }

  /** guessRegistrarIdForUser returns the first registrarId in getAllRegistrarIdWithRoles. */
  @Test
  void testGuessRegistrarIdForUser_hasAccess_returnsFirst() throws Exception {
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of(
                "registrarId-1", OWNER,
                "registrarId-2", OWNER,
                "registrarId-2", ADMIN));

    assertThat(registrarAccessor.guessRegistrarId()).isEqualTo("registrarId-1");
  }

  /** If a user doesn't have access to any registrars, guess fails. */
  @Test
  void testGuessRegistrarIdForUser_noAccess_fails() {
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(ImmutableSetMultimap.of());

    RegistrarAccessDeniedException thrown =
        assertThrows(RegistrarAccessDeniedException.class, registrarAccessor::guessRegistrarId);
    assertThat(thrown).hasMessageThat().isEqualTo("TestUserId isn't associated with any registrar");
  }

  @Test
  void testNullness() {
    new NullPointerTester()
        .setDefault(HttpServletRequest.class, req)
        .setDefault(HttpServletResponse.class, rsp)
        .testAllPublicStaticMethods(AuthenticatedRegistrarAccessor.class);
  }

  @Test
  void testConsoleUser_admin() {
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(
                new UserRoles.Builder().setIsAdmin(true).setGlobalRole(GlobalRole.FTE).build())
            .build();
    AuthResult authResult = AuthResult.createUser(consoleUser);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // Admin access to all, and owner access to the non-real registrar and the admin registrar
    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(
            REGISTRAR_ID_WITH_ACCESS, ADMIN,
            REAL_REGISTRAR_ID_WITHOUT_ACCESS, ADMIN,
            OTE_REGISTRAR_ID_WITHOUT_CONTACT, ADMIN,
            OTE_REGISTRAR_ID_WITHOUT_CONTACT, OWNER,
            ADMIN_REGISTRAR_ID, ADMIN,
            ADMIN_REGISTRAR_ID, OWNER);
  }

  @Test
  void testConsoleUser_globalRole() {
    // Users with global roles shouldn't necessarily have access to specific registrars if they're
    // not admins
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.SUPPORT_AGENT).build())
            .build();
    AuthResult authResult = AuthResult.createUser(consoleUser);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);

    // Explicit access to registrars is required for non-admins
    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles()).isEmpty();
  }

  @Test
  void testConsoleUser_registrarRoles() {
    // Registrar employees should have OWNER access to their registrars
    google.registry.model.console.User consoleUser =
        new google.registry.model.console.User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of(
                            REGISTRAR_ID_WITH_ACCESS,
                            ACCOUNT_MANAGER,
                            REAL_REGISTRAR_ID_WITHOUT_ACCESS,
                            ACCOUNT_MANAGER))
                    .build())
            .build();
    AuthResult authResult = AuthResult.createUser(consoleUser);
    AuthenticatedRegistrarAccessor registrarAccessor =
        new AuthenticatedRegistrarAccessor(
            authResult, ADMIN_REGISTRAR_ID, SUPPORT_GROUP, lazyGroupsConnection);
    assertThat(registrarAccessor.getAllRegistrarIdsWithRoles())
        .containsExactly(
            REGISTRAR_ID_WITH_ACCESS, OWNER,
            REAL_REGISTRAR_ID_WITHOUT_ACCESS, OWNER);
  }
}
