// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import google.registry.request.JsonActionRunner;
import google.registry.request.JsonResponse;
import google.registry.request.ResponseImpl;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.util.EmailMessage;
import google.registry.util.SendEmailService;
import java.util.Map;
import javax.mail.internet.InternetAddress;
import javax.servlet.http.HttpServletResponse;
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

  @Rule public final AppEngineRule appEngineRule = AppEngineRule.builder().withDatastore().build();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final User userWithoutPermission =
      new User("johndoe@theregistrar.com", "gmail.com", "31337");
  // Marla Singer has registry lock auth permissions
  private final User userWithLockPermission =
      new User("Marla.Singer@crr.com", "gmail.com", "31337");

  private ImmutableMap<String, Object> existingLocksMap =
      ImmutableMap.of(
          "lockEnabledForContact",
          true,
          "email",
          "marla.singer@example.com",
          "locks",
          ImmutableList.of(),
          "clientId",
          "TheRegistrar");

  private AuthResult authResult;
  private InternetAddress outgoingAddress;
  private RegistryLockPostAction action;

  @Mock ExistingRegistryLocksRetriever retriever;
  @Mock SendEmailService emailService;
  @Mock HttpServletResponse mockResponse;

  @Before
  public void setup() throws Exception {
    when(retriever.getLockedDomainsMap(anyString())).thenReturn(existingLocksMap);
    authResult =
        AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, false));
    outgoingAddress = new InternetAddress("domain-registry@example.com");
    JsonActionRunner jsonActionRunner =
        new JsonActionRunner(ImmutableMap.of(), new JsonResponse(new ResponseImpl(mockResponse)));
    AuthenticatedRegistrarAccessor registrarAccessor =
        AuthenticatedRegistrarAccessor.createForTesting(
            ImmutableSetMultimap.of("TheRegistrar", Role.OWNER, "NewRegistrar", Role.OWNER));
    action =
        new RegistryLockPostAction(
            retriever,
            jsonActionRunner,
            authResult,
            registrarAccessor,
            emailService,
            outgoingAddress);
  }

  @Test
  public void testSuccess_lock() {
    action.authResult =
        AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithLockPermission, false));
    // TODO when we can persist locks
  }

  @Test
  public void testSuccess_unlock() {
    action.authResult =
        AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithLockPermission, false));
    // TODO when we can persist locks
  }

  @Test
  public void testSuccess_adminUser() throws Exception {
    // Admin user should be able to lock/unlock regardless
    action.authResult =
        AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithoutPermission, true));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "test"));
    // TODO when we can persist locks
    assertThat(response)
        .containsExactly(
            "status", "SUCCESS",
            "results", ImmutableList.of(existingLocksMap),
            "message", "Successful lock / unlock");
    verifyEmail("lock");
  }

  @Test
  public void testFailure_noInput() {
    Map<String, ?> response = action.handleJsonRequest(null);
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Malformed JSON");
  }

  @Test
  public void testFailure_noClientId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of());
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Missing key for client: clientId");
  }

  @Test
  public void testFailure_emptyClientId() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of("clientId", ""));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Missing key for client: clientId");
  }

  @Test
  public void testFailure_noDomainName() {
    Map<String, ?> response = action.handleJsonRequest(ImmutableMap.of("clientId", "TheRegistrar"));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Missing key for fqdn: fullyQualifiedDomainName");
  }

  @Test
  public void testFailure_noLockParam() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of("clientId", "TheRegistrar", "fullyQualifiedDomainName", "example.tld"));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Missing key for isLock: isLock");
  }

  @Test
  public void testFailure_notAllowedOnRegistrar() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "NewRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "password"));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Registry lock not allowed for this registrar");
  }

  @Test
  public void testFailure_noPassword() {
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Missing key for password: password");
  }

  @Test
  public void testFailure_notEnabledForRegistrarContact() {
    // A wrong password should give the same error as not-enabled-for-this-contact
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "test"));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Incorrect registry lock password for contact");
  }

  @Test
  public void testFailure_badPassword() {
    action.authResult =
        AuthResult.create(AuthLevel.USER, UserAuthInfo.create(userWithLockPermission, false));
    Map<String, ?> response =
        action.handleJsonRequest(
            ImmutableMap.of(
                "clientId", "TheRegistrar",
                "fullyQualifiedDomainName", "example.tld",
                "isLock", true,
                "password", "badPassword"));
    assertThat(response)
        .containsExactly(
            "status", "ERROR",
            "results", ImmutableList.of(),
            "message", "Incorrect registry lock password for contact");
  }

  @Test
  public void testFailure_invalidTld() {
    // TODO when we can persist locks
  }

  private void verifyEmail(String lockAction) throws Exception {
    ArgumentCaptor<EmailMessage> emailCaptor = ArgumentCaptor.forClass(EmailMessage.class);
    verify(emailService).sendEmail(emailCaptor.capture());
    EmailMessage sentMessage = emailCaptor.getValue();
    assertThat(sentMessage.subject())
        .isEqualTo(String.format("Registry %s verification", lockAction));
    assertThat(sentMessage.body()).startsWith("Please click the link below");
    assertThat(sentMessage.from()).isEqualTo(new InternetAddress("domain-registry@example.com"));
    assertThat(sentMessage.recipients())
        .containsExactly(new InternetAddress("johndoe@theregistrar.com"));
  }
}
