// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.ui.server.registrar;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.getMostRecentRegistryLockByRepoId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static google.registry.ui.server.registrar.RegistryLockGetActionTest.userFromRegistrarContact;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.model.domain.DomainBase;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonResponse;
import google.registry.request.ResponseImpl;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.request.auth.UserAuthInfo;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.tools.DomainLockUtils;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletResponse;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public final class RegistryLockPostActionTest {

  private static final String EMAIL_MESSAGE_TEMPLATE =
      "Please click the link below to perform the lock \\/ unlock action on domain example.tld. "
          + "Note: this code will expire in one hour.\n\n"
          + "https:\\/\\/localhost\\/registry-lock-verify\\?lockVerificationCode="
          + "[0-9a-zA-Z_\\-]+&isLock=(true|false)";

  private final FakeClock clock = new FakeClock();

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder().withDatastoreAndCloudSql().withClock(clock).build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private User userWithoutPermission;
  private User userWithLockPermission;

  private InternetAddress outgoingAddress;
  private DomainBase domain;
  private RegistryLockPostAction action;

  @Mock SendEmailService emailService;
  @Mock HttpServletResponse mockResponse;

  @Before
  public void setup() throws Exception {
    userWithLockPermission = userFromRegistrarContact(AppEngineRule.makeRegistrarContact3());
    userWithoutPermission = userFromRegistrarContact(AppEngineRule.makeRegistrarContact2());
    createTld("tld");
    domain = persistResource(newDomainBase("example.tld"));
    outgoingAddress = new InternetAddress("domain-registry@example.com");

    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithLockPermission, false)));
  }

  @Test
  public void testSuccess_lock() throws Exception {
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  public void testSuccess_unlock() throws Exception {
    saveRegistryLock(createLock().asBuilder().setLockCompletionTimestamp(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertSuccess(response, "unlock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  public void testSuccess_unlock_relockDurationSet() throws Exception {
    saveRegistryLock(createLock().asBuilder().setLockCompletionTimestamp(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    ImmutableMap<String, Object> request =
        new ImmutableMap.Builder<String, Object>()
            .putAll(unlockRequest())
            .put("relockDurationMillis", Duration.standardDays(1).getMillis())
            .build();
    Map<String, ?> response = action.handleJsonRequest(request);
    assertSuccess(response, "unlock", "Marla.Singer.RegistryLock@crr.com");
    RegistryLock savedUnlockRequest = getMostRecentRegistryLockByRepoId(domain.getRepoId()).get();
    assertThat(savedUnlockRequest.getRelockDuration())
        .isEqualTo(Optional.of(Duration.standardDays(1)));
  }

  @Test
  public void testSuccess_unlock_adminUnlockingAdmin() throws Exception {
    saveRegistryLock(
        createLock()
            .asBuilder()
            .isSuperuser(true)
            .setLockCompletionTimestamp(clock.nowUtc())
            .build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    // we should still email the admin user's email address
    assertSuccess(response, "unlock", "johndoe@theregistrar.com");
  }

  @Test
  public void testSuccess_linkedToContactEmail() throws Exception {
    // Even though the user is some.email@gmail.com the contact is still Marla Singer
    userWithLockPermission =
        new User("some.email@gmail.com", "gmail.com", userWithLockPermission.getUserId());
    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithLockPermission, false)));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  public void testFailure_unlock_noLock() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "No lock object for domain example.tld");
  }

  @Test
  public void testFailure_unlock_alreadyUnlocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTimestamp(clock.nowUtc())
            .setUnlockRequestTimestamp(clock.nowUtc())
            .build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "A pending unlock action already exists for example.tld");
  }

  @Test
  public void testFailure_unlock_nonAdminUnlockingAdmin() {
    saveRegistryLock(
        createLock()
            .asBuilder()
            .isSuperuser(true)
            .setLockCompletionTimestamp(clock.nowUtc())
            .build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(
        response, "Non-admin user cannot unlock admin-locked domain example.tld");
  }

  @Test
  public void testSuccess_adminUser() throws Exception {
    // Admin user should be able to lock/unlock regardless -- and we use the admin user's email
    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  public void testSuccess_adminUser_doesNotRequirePassword() throws Exception {
    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, true)));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true));
    assertSuccess(response, "lock", "johndoe@theregistrar.com");
  }

  @Test
  public void testFailure_noInput() {
    Map<String, ?> response = action.handleJsonRequest(null);
    assertFailureWithMessage(response, "Null JSON");
  }

  @Test
  public void testFailure_noClientId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of());
    assertFailureWithMessage(response, "Missing key for client: clientId");
  }

  @Test
  public void testFailure_emptyClientId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of("clientId", ""));
    assertFailureWithMessage(response, "Missing key for client: clientId");
  }

  @Test
  public void testFailure_noDomainName() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of("clientId", "TheRegistrar", "password", "hi", "isLock", true));
    assertFailureWithMessage(response, "Missing key for fullyQualifiedDomainName");
  }

  @Test
  public void testFailure_noLockParam() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "password", "hi"));
    assertFailureWithMessage(response, "Missing key for isLock");
  }

  @Test
  public void testFailure_notAllowedOnRegistrar() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setRegistryLockAllowed(false).build());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(response, "Registry lock not allowed for registrar TheRegistrar");
  }

  @Test
  public void testFailure_noPassword() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  public void testFailure_notEnabledForRegistrarContact() {
    action =
        createAction(
            AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, false)));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "hi"));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  public void testFailure_badPassword() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "badPassword"));
    assertFailureWithMessage(response, "Incorrect registry lock password for contact");
  }

  @Test
  public void testFailure_invalidDomain() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "bad.tld",
                "isLock", true,
                "password", "hi"));
    assertFailureWithMessage(response, "Unknown domain bad.tld");
  }

  @Test
  public void testSuccess_previousLockUnlocked() throws Exception {
    saveRegistryLock(
        createLock()
            .asBuilder()
            .setLockCompletionTimestamp(clock.nowUtc().minusMinutes(1))
            .setUnlockRequestTimestamp(clock.nowUtc().minusMinutes(1))
            .setUnlockCompletionTimestamp(clock.nowUtc().minusMinutes(1))
            .build());

    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  public void testSuccess_previousLockExpired() throws Exception {
    RegistryLock previousLock = saveRegistryLock(createLock());
    String verificationCode = previousLock.getVerificationCode();
    previousLock = getRegistryLockByVerificationCode(verificationCode).get();
    clock.setTo(previousLock.getLockRequestTimestamp().plusHours(2));
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertSuccess(response, "lock", "Marla.Singer.RegistryLock@crr.com");
  }

  @Test
  public void testFailure_alreadyPendingLock() {
    saveRegistryLock(createLock());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(
        response, "A pending or completed lock action already exists for example.tld");
  }

  @Test
  public void testFailure_alreadyLocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    Map<String, ?> response = action.handleJsonRequest(lockRequest());
    assertFailureWithMessage(response, "Domain example.tld is already locked");
  }

  @Test
  public void testFailure_alreadyUnlocked() {
    Map<String, ?> response = action.handleJsonRequest(unlockRequest());
    assertFailureWithMessage(response, "Domain example.tld is already unlocked");
  }

  private ImmutableMap<String, Object> lockRequest() {
    return fullRequest(true);
  }

  private ImmutableMap<String, Object> unlockRequest() {
    return fullRequest(false);
  }

  private ImmutableMap<String, Object> fullRequest(boolean lock) {
    return ImmutableMap.of(
        "isLock", lock,
        "clientId", "TheRegistrar",
        "fullyQualifiedDomainName", "example.tld",
        "password", "hi");
  }

  private RegistryLock createLock() {
    DomainBase domain = loadByForeignKey(DomainBase.class, "example.tld", clock.nowUtc()).get();
    return new RegistryLock.Builder()
        .setDomainName("example.tld")
        .isSuperuser(false)
        .setVerificationCode(UUID.randomUUID().toString())
        .setRegistrarId("TheRegistrar")
        .setRepoId(domain.getRepoId())
        .setRegistrarPocId("Marla.Singer@crr.com")
        .build();
  }

  private void assertSuccess(Map<String, ?> response, String lockAction, String recipient)
      throws Exception {
    assertThat(response)
        .containsExactly(
            "status", "SUCCESS",
            "results", ImmutableList.of(),
            "message", String.format("Successful %s", lockAction));
    verifyEmail(recipient);
  }

  private void assertFailureWithMessage(Map<String, ?> response, String message) {
    assertThat(response)
        .containsExactly("status", "ERROR", "results", ImmutableList.of(), "message", message);
    verifyNoMoreInteractions(emailService);
  }

  private void verifyEmail(String recipient) throws Exception {
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(emailCaptor.capture());
    EmailMessage sentMessage = emailCaptor.getValue();
    assertThat(sentMessage.subject()).matches("Registry (un)?lock verification");
    assertThat(sentMessage.body()).matches(EMAIL_MESSAGE_TEMPLATE);
    assertThat(sentMessage.from()).isEqualTo(new InternetAddress("domain-registry@example.com"));
    assertThat(sentMessage.recipients()).containsExactly(new InternetAddress(recipient));
  }

  private RegistryLockPostAction createAction(AuthResult authResult) {
    Role role = authResult.userAuthInfo().get().isUserAdmin() ? Role.ADMIN : Role.OWNER;
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("TheRegistrar", role, "NewRegistrar", role));
    JsonActionRunner jsonActionRunner =
        new JsonActionRunner(ImmutableMap.of(), new JsonResponse(new ResponseImpl(mockResponse)));
    DomainLockUtils domainLockUtils =
        new DomainLockUtils(new DeterministicStringGenerator(Alphabets.BASE_58));
    return new RegistryLockPostAction(
        jsonActionRunner,
        authResult,
        registrarAccessor,
        emailService,
        domainLockUtils,
        outgoingAddress);
  }
}
