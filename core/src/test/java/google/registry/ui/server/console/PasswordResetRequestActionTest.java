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
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.PasswordResetRequest;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.PasswordResetRequestAction.PasswordResetRequestData;
import jakarta.servlet.http.HttpServletResponse;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PasswordResetRequestAction}. */
public class PasswordResetRequestActionTest extends ConsoleActionBaseTestCase {

  @BeforeEach
  void beforeEach() {
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
  }

  @Test
  void testSuccess_epp() throws Exception {
    PasswordResetRequestAction action =
        createAction(PasswordResetRequest.Type.EPP, "TheRegistrar", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertAboutImmutableObjects()
        .that(DatabaseHelper.loadSingleton(PasswordResetRequest.class).get())
        .isEqualExceptFields(
            new PasswordResetRequest.Builder()
                .setDestinationEmail("johndoe@theregistrar.com")
                .setRequester("fte@email.tld")
                .setType(PasswordResetRequest.Type.EPP)
                .setRegistrarId("TheRegistrar")
                .build(),
            "requestTime",
            "verificationCode");
  }

  @Test
  void testSuccess_registryLock() throws Exception {
    User targetUser =
        DatabaseHelper.persistResource(
            new User.Builder()
                .setEmailAddress("email@registry.tld")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setRegistrarRoles(
                            ImmutableMap.of(
                                "TheRegistrar", RegistrarRole.ACCOUNT_MANAGER_WITH_REGISTRY_LOCK))
                        .build())
                .setRegistryLockEmailAddress("registrylock@theregistrar.com")
                .setRegistryLockPassword("password")
                .build());
    PasswordResetRequestAction action =
        createAction(
            PasswordResetRequest.Type.REGISTRY_LOCK,
            "TheRegistrar",
            "registrylock@theregistrar.com");
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertAboutImmutableObjects()
        .that(DatabaseHelper.loadSingleton(PasswordResetRequest.class).get())
        .isEqualExceptFields(
            new PasswordResetRequest.Builder()
                .setDestinationEmail("registrylock@theregistrar.com")
                .setRequester("fte@email.tld")
                .setType(PasswordResetRequest.Type.REGISTRY_LOCK)
                .setRegistrarId("TheRegistrar")
                .build(),
            "requestTime",
            "verificationCode");
  }

  @Test
  void testFailure_nullType() throws Exception {
    PasswordResetRequestAction action = createAction(null, "TheRegistrar", "email@email.test");
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Type cannot be null");
  }

  @Test
  void testFailure_nullRegistrarId() throws Exception {
    PasswordResetRequestAction action =
        createAction(PasswordResetRequest.Type.EPP, null, "email@email.test");
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Registrar ID cannot be null");
  }

  @Test
  void testFailure_registryLock_nullEmail() throws Exception {
    PasswordResetRequestAction action =
        createAction(PasswordResetRequest.Type.REGISTRY_LOCK, "TheRegistrar", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Must provide registry lock email to reset");
  }

  @Test
  void testFailure_registryLock_invalidEmail() throws Exception {
    PasswordResetRequestAction action =
        createAction(
            PasswordResetRequest.Type.REGISTRY_LOCK, "TheRegistrar", "nonexistent@email.com");
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Unknown user with lock email nonexistent@email.com");
  }

  @Test
  void testFailure_epp_noPermission() throws Exception {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.test")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                    .build())
            .build();
    PasswordResetRequestAction action =
        createAction(user, PasswordResetRequest.Type.EPP, "TheRegistrar", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  @Test
  void testFailure_lock_noPermission() throws Exception {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.test")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(ImmutableMap.of("TheRegistrar", RegistrarRole.TECH_CONTACT))
                    .build())
            .build();
    PasswordResetRequestAction action =
        createAction(
            user,
            PasswordResetRequest.Type.REGISTRY_LOCK,
            "TheRegistrar",
            "registrylockfte@email.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
  }

  private PasswordResetRequestAction createAction(
      User user,
      PasswordResetRequest.Type type,
      String registrarId,
      @Nullable String registryLockEmail) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    response = (FakeResponse) consoleApiParams.response();
    return createAction(type, registrarId, registryLockEmail);
  }

  private PasswordResetRequestAction createAction(
      PasswordResetRequest.Type type, String registrarId, @Nullable String registryLockEmail) {
    PasswordResetRequestData data =
        new PasswordResetRequestData(type, registrarId, registryLockEmail);
    return new PasswordResetRequestAction(consoleApiParams, data);
  }
}
