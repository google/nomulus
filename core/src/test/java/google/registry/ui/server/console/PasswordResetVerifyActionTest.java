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
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.PasswordResetRequest;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PasswordResetVerifyAction}. */
public class PasswordResetVerifyActionTest extends ConsoleActionBaseTestCase {

  private String verificationCode;

  @BeforeEach
  void beforeEach() {
    verificationCode = saveRequest(PasswordResetRequest.Type.EPP).getVerificationCode();
  }

  @Test
  void testSuccess_get_epp() throws Exception {
    createAction("GET", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(GSON.fromJson(response.getPayload(), Map.class))
        .isEqualTo(ImmutableMap.of("registrarId", "TheRegistrar", "type", "EPP"));
  }

  @Test
  void testSuccess_get_lock() throws Exception {
    verificationCode = saveRequest(PasswordResetRequest.Type.REGISTRY_LOCK).getVerificationCode();
    createAction("GET", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(GSON.fromJson(response.getPayload(), Map.class))
        .isEqualTo(ImmutableMap.of("registrarId", "TheRegistrar", "type", "REGISTRY_LOCK"));
  }

  @Test
  void testSuccess_post_epp() throws Exception {
    assertThat(Registrar.loadByRegistrarId("TheRegistrar").get().verifyPassword("password2"))
        .isTrue();
    createAction("POST", verificationCode, "newEppPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(Registrar.loadByRegistrarId("TheRegistrar").get().verifyPassword("password2"))
        .isFalse();
    assertThat(Registrar.loadByRegistrarId("TheRegistrar").get().verifyPassword("newEppPassword"))
        .isTrue();
  }

  @Test
  void testSuccess_post_lock() throws Exception {
    assertThat(loadByEntity(fteUser).verifyRegistryLockPassword("password")).isTrue();
    verificationCode = saveRequest(PasswordResetRequest.Type.REGISTRY_LOCK).getVerificationCode();
    createAction("POST", verificationCode, "newRegistryLockPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(loadByEntity(fteUser).verifyRegistryLockPassword("newRegistryLockPassword"))
        .isTrue();
  }

  @Test
  void testFailure_get_invalidVerificationCode() throws Exception {
    createAction("GET", "invalid", null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void testFailure_post_invalidVerificationCode() throws Exception {
    createAction("POST", "invalid", "newPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void testFailure_nullPassword() throws Exception {
    createAction("POST", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void testFailure_emptyPassword() throws Exception {
    createAction("POST", verificationCode, "").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void testFailure_get_epp_badPermission() throws Exception {
    createAction(createTechUser(), "GET", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testFailure_get_lock_badPermission() throws Exception {
    createAction(createAccountManager(), "GET", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testFailure_post_epp_badPermission() throws Exception {
    createAction(createTechUser(), "POST", verificationCode, "newPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testFailure_post_lock_badPermission() throws Exception {
    createAction(createAccountManager(), "POST", verificationCode, "newPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testFailure_get_expired() throws Exception {
    clock.advanceBy(Duration.standardDays(1));
    createAction("GET", verificationCode, null).run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  @Test
  void testFailure_post_expired() throws Exception {
    clock.advanceBy(Duration.standardDays(1));
    createAction("POST", verificationCode, "newPassword").run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
  }

  private User createTechUser() {
    return new User.Builder()
        .setEmailAddress("tech@example.tld")
        .setUserRoles(
            new UserRoles.Builder()
                .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.TECH_CONTACT))
                .build())
        .build();
  }

  private User createAccountManager() {
    return new User.Builder()
        .setEmailAddress("accountmanager@example.tld")
        .setUserRoles(
            new UserRoles.Builder()
                .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                .build())
        .build();
  }

  private PasswordResetRequest saveRequest(PasswordResetRequest.Type type) {
    return persistResource(
        new PasswordResetRequest.Builder()
            // use the built-in user registry lock email
            .setDestinationEmail("registrylockfte@email.tld")
            .setRequester("requester@email.tld")
            .setRegistrarId("TheRegistrar")
            .setType(type)
            .build());
  }

  private PasswordResetVerifyAction createAction(
      User user, String method, String verificationCode, @Nullable String newPassword) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    return createAction(method, verificationCode, newPassword);
  }

  private PasswordResetVerifyAction createAction(
      String method, String verificationCode, @Nullable String newPassword) {
    when(consoleApiParams.request().getMethod()).thenReturn(method);
    response = (FakeResponse) consoleApiParams.response();
    return new PasswordResetVerifyAction(
        consoleApiParams, verificationCode, Optional.ofNullable(newPassword));
  }
}
