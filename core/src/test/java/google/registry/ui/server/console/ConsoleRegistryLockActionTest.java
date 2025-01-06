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

package google.registry.ui.server.console;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.newDomain;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.ui.server.console.ConsoleRegistryLockAction.ConsoleRegistryLockPostInput;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.groups.GmailClient;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.domain.RegistryLock;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.DomainLockUtils;
import google.registry.util.EmailMessage;
import google.registry.util.StringGenerator;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Tests for {@link ConsoleRegistryLockAction}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class ConsoleRegistryLockActionTest {

  private static final String EXPECTED_EMAIL_MESSAGE =
      """
      Please click the link below to perform the lock / unlock action on domain example.test. \
      Note: this code will expire in one hour.

      https://registrarconsole.tld/console/#/registry-lock-verify?lockVerificationCode=\
      123456789ABCDEFGHJKLMNPQRSTUVWXY""";

  private final FakeClock fakeClock = new FakeClock(DateTime.parse("2024-04-18T12:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(fakeClock).buildIntegrationTestExtension();

  @Mock GmailClient gmailClient;
  private ConsoleRegistryLockAction action;
  private Domain defaultDomain;
  private FakeResponse response;
  private User user;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("test");
    defaultDomain = persistActiveDomain("example.test");
    user =
        new User.Builder()
            .setEmailAddress("user@theregistrar.com")
            .setRegistryLockEmailAddress("registrylock@theregistrar.com")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .setRegistryLockPassword("registryLockPassword")
            .build();
    action = createGetAction();
  }

  @AfterEach
  void afterEach() {
    verifyNoMoreInteractions(gmailClient);
  }

  @Test
  void testGet_simpleLock() {
    saveRegistryLock(createDefaultLockBuilder().setLockCompletionTime(fakeClock.nowUtc()).build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
[{"domainName":"example.test","registrarPocId":"johndoe@theregistrar.com","lockRequestTime":\
{"creationTime":"2024-04-18T12:00:00.000Z"},"unlockRequestTime":"null","lockCompletionTime":\
"2024-04-18T12:00:00.000Z","unlockCompletionTime":"null","isSuperuser":false}]\
""");
  }

  @Test
  void testGet_allCurrentlyValidLocks() {
    RegistryLock expiredLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("expired.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .build();
    saveRegistryLock(expiredLock);
    RegistryLock expiredUnlock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("expiredunlock.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build();
    saveRegistryLock(expiredUnlock);
    fakeClock.advanceBy(Duration.standardDays(1));

    RegistryLock regularLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("example.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    fakeClock.advanceOneMilli();
    RegistryLock adminLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("adminexample.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("122222222ABCDEFGHJKLMNPQRSTUVWXY")
            .isSuperuser(true)
            .setLockCompletionTime(fakeClock.nowUtc())
            .build();
    RegistryLock incompleteLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("pending.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("111111111ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .build();

    RegistryLock incompleteUnlock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("incompleteunlock.test")
            .setRegistrarId("TheRegistrar")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUVWXY")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .build();

    RegistryLock unlockedLock =
        new RegistryLock.Builder()
            .setRepoId("repoId")
            .setDomainName("unlocked.test")
            .setRegistrarId("TheRegistrar")
            .setRegistrarPocId("johndoe@theregistrar.com")
            .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUUUUU")
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .setUnlockCompletionTime(fakeClock.nowUtc())
            .build();

    saveRegistryLock(regularLock);
    saveRegistryLock(adminLock);
    saveRegistryLock(incompleteLock);
    saveRegistryLock(incompleteUnlock);
    saveRegistryLock(unlockedLock);

    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    // We should include all the locks that are currently locked, which does not include pending
    // locks or completed unlocks
    assertThat(response.getPayload())
        .isEqualTo(
            """
[{"domainName":"adminexample.test","lockRequestTime":{"creationTime":"2024-04-19T12:00:00.001Z"},\
"unlockRequestTime":"null","lockCompletionTime":"2024-04-19T12:00:00.001Z","unlockCompletionTime":\
"null","isSuperuser":true},\
\
{"domainName":"example.test","registrarPocId":"johndoe@theregistrar.com","lockRequestTime":\
{"creationTime":"2024-04-19T12:00:00.001Z"},"unlockRequestTime":"null","lockCompletionTime":\
"2024-04-19T12:00:00.000Z","unlockCompletionTime":"null","isSuperuser":false},\
\
{"domainName":"expiredunlock.test","registrarPocId":"johndoe@theregistrar.com","lockRequestTime":\
{"creationTime":"2024-04-18T12:00:00.000Z"},"unlockRequestTime":"2024-04-18T12:00:00.000Z",\
"lockCompletionTime":"2024-04-18T12:00:00.000Z","unlockCompletionTime":"null","isSuperuser":false},\
\
{"domainName":"incompleteunlock.test","registrarPocId":"johndoe@theregistrar.com","lockRequestTime":\
{"creationTime":"2024-04-19T12:00:00.001Z"},"unlockRequestTime":"2024-04-19T12:00:00.001Z",\
"lockCompletionTime":"2024-04-19T12:00:00.001Z","unlockCompletionTime":"null","isSuperuser":false},\
\
{"domainName":"pending.test","registrarPocId":"johndoe@theregistrar.com","lockRequestTime":\
{"creationTime":"2024-04-19T12:00:00.001Z"},"unlockRequestTime":"null","lockCompletionTime":"null",\
"unlockCompletionTime":"null","isSuperuser":false}]""");
  }

  @Test
  void testGet_noLocks() {
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("[]");
  }

  @Test
  void testGet_failure_noRegistrarAccess() throws Exception {
    user =
        user.asBuilder()
            .setUserRoles(
                user.getUserRoles().asBuilder().setRegistrarRoles(ImmutableMap.of()).build())
            .build();
    action = createGetAction();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testGet_failure_noRegistryLockAccess() throws Exception {
    // User has access to the registrar, but not to do locks
    user =
        user.asBuilder()
            .setUserRoles(
                user.getUserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                    .build())
            .build();
    action = createGetAction();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testPost_lock() throws Exception {
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(getMostRecentRegistryLockByRepoId(defaultDomain.getRepoId())).isPresent();
    verifyEmail();
    // Doesn't actually change the status values (hasn't been verified)
    assertThat(loadByEntity(defaultDomain).getStatusValues()).containsExactly(StatusValue.INACTIVE);
  }

  @Test
  void testPost_unlock() throws Exception {
    saveRegistryLock(createDefaultLockBuilder().setLockCompletionTime(fakeClock.nowUtc()).build());
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action = createDefaultPostAction(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyEmail();
    // Doesn't actually change the status values (hasn't been verified)
    assertThat(loadByEntity(defaultDomain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testPost_unlock_relockDuration() throws Exception {
    saveRegistryLock(createDefaultLockBuilder().setLockCompletionTime(fakeClock.nowUtc()).build());
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action =
        createPostAction(
            "example.test", false, "registryLockPassword", Duration.standardDays(1).getMillis());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyEmail();
    RegistryLock savedUnlockRequest =
        getMostRecentRegistryLockByRepoId(defaultDomain.getRepoId()).get();
    assertThat(savedUnlockRequest.getRelockDuration())
        .isEqualTo(Optional.of(Duration.standardDays(1)));
  }

  @Test
  void testPost_adminUnlockingAdmin() throws Exception {
    saveRegistryLock(
        createDefaultLockBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .isSuperuser(true)
            .build());
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    user =
        user.asBuilder()
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(true).build())
            .build();
    action = createDefaultPostAction(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyEmail();
  }

  @Test
  void testPost_success_noPasswordForAdmin() throws Exception {
    user =
        user.asBuilder()
            .setUserRoles(
                new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(true).build())
            .build();
    action = createPostAction("example.test", true, "", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verifyEmail();
  }

  @Test
  void testPost_failure_noRegistrarAccess() throws Exception {
    user =
        user.asBuilder()
            .setUserRoles(
                user.getUserRoles().asBuilder().setRegistrarRoles(ImmutableMap.of()).build())
            .build();
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testPost_failure_noRegistryLockAccess() throws Exception {
    // User has access to the registrar, but not to do locks
    user =
        user.asBuilder()
            .setUserRoles(
                user.getUserRoles()
                    .asBuilder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                    .build())
            .build();
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testPost_failure_unlock_noLock() throws Exception {
    action = createDefaultPostAction(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Domain example.test is already unlocked");
  }

  @Test
  void testPost_failure_nonAdminUnlockingAdmin() throws Exception {
    saveRegistryLock(
        createDefaultLockBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .isSuperuser(true)
            .build());
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action = createDefaultPostAction(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Non-admin user cannot unlock admin-locked domain example.test");
  }

  @Test
  void testPost_failure_wrongRegistrarForDomain() throws Exception {
    persistResource(
        newDomain("otherregistrar.test")
            .asBuilder()
            .setCreationRegistrarId("NewRegistrar")
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    action = createPostAction("otherregistrar.test", true, "registryLockPassword", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Domain otherregistrar.test is not owned by registrar TheRegistrar");
  }

  @Test
  void testPost_failure_notAllowedForRegistrar() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setRegistryLockAllowed(false).build());
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Registry lock not allowed for registrar TheRegistrar");
  }

  @Test
  void testPost_failure_noRegistryLockEmail() {
    user = user.asBuilder().setRegistryLockEmailAddress(null).build();
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("User has no registry lock email address");
  }

  @Test
  void testPost_failure_badPassword() throws Exception {
    action = createPostAction("example.test", true, "badPassword", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_UNAUTHORIZED);
  }

  @Test
  void testPost_failure_lock_alreadyPendingLock() throws Exception {
    saveRegistryLock(createDefaultLockBuilder().build());
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("A pending or completed lock action already exists for example.test");
  }

  @Test
  void testPost_failure_alreadyLocked() throws Exception {
    persistResource(defaultDomain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action = createDefaultPostAction(true);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Domain example.test is already locked");
  }

  @Test
  void testPost_failure_alreadyUnlocked() throws Exception {
    saveRegistryLock(
        createDefaultLockBuilder()
            .setLockCompletionTime(fakeClock.nowUtc())
            .setUnlockRequestTime(fakeClock.nowUtc())
            .setUnlockCompletionTime(fakeClock.nowUtc())
            .build());
    action = createDefaultPostAction(false);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Domain example.test is already unlocked");
  }

  private ConsoleRegistryLockAction createDefaultPostAction(boolean isLock) {
    return createPostAction("example.test", isLock, "registryLockPassword", null);
  }

  private ConsoleRegistryLockAction createPostAction(
      String domainName, boolean isLock, String password, Long relockDurationMillis) {
    ConsoleApiParams params = createParams();
    ConsoleRegistryLockPostInput postInput =
        new ConsoleRegistryLockPostInput(domainName, isLock, password, relockDurationMillis);
    return createGenericAction(params, "POST", Optional.of(postInput));
  }

  private ConsoleRegistryLockAction createGetAction() throws IOException {
    return createGenericAction(createParams(), "GET", Optional.empty());
  }

  private ConsoleRegistryLockAction createGenericAction(
      ConsoleApiParams params,
      String method,
      Optional<ConsoleRegistryLockPostInput> optionalPostInput) {
    when(params.request().getMethod()).thenReturn(method);
    when(params.request().getServerName()).thenReturn("registrarconsole.tld");
    when(params.request().getParameter("registrarId")).thenReturn("TheRegistrar");
    DomainLockUtils domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(StringGenerator.Alphabets.BASE_58),
            "adminreg",
            new CloudTasksHelper(fakeClock).getTestCloudTasksUtils());
    response = (FakeResponse) params.response();
    return new ConsoleRegistryLockAction(
        params, domainLockUtils, gmailClient, optionalPostInput, "TheRegistrar");
  }

  private ConsoleApiParams createParams() {
    AuthResult authResult = AuthResult.createUser(user);
    return ConsoleApiParamsUtils.createFake(authResult);
  }

  private RegistryLock.Builder createDefaultLockBuilder() {
    return new RegistryLock.Builder()
        .setRepoId(defaultDomain.getRepoId())
        .setDomainName(defaultDomain.getDomainName())
        .setRegistrarId(defaultDomain.getCurrentSponsorRegistrarId())
        .setRegistrarPocId("johndoe@theregistrar.com")
        .setVerificationCode("123456789ABCDEFGHJKLMNPQRSTUUUUU");
  }

  private void verifyEmail() throws Exception {
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(gmailClient).sendEmail(emailCaptor.capture());
    EmailMessage sentMessage = emailCaptor.getValue();
    assertThat(sentMessage.subject()).matches("Registry (un)?lock verification");
    assertThat(sentMessage.body()).isEqualTo(EXPECTED_EMAIL_MESSAGE);
    assertThat(sentMessage.recipients())
        .containsExactly(new InternetAddress("registrylock@theregistrar.com"));
  }
}
