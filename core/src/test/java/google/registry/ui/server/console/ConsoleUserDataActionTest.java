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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.model.console.User;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.ConsoleUserDataAction}. */
class ConsoleUserDataActionTest {

  private static final Gson GSON = RequestModule.provideGson();

  private ConsoleApiParams consoleApiParams;

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess_hasXSRFCookie() throws IOException {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUserDataAction action =
        createAction(Optional.of(ConsoleApiParamsUtils.createFake(authResult)));
    action.run();
    List<Cookie> cookies = ((FakeResponse) consoleApiParams.response()).getCookies();
    assertThat(cookies.stream().map(cookie -> cookie.getName()).collect(toImmutableList()))
        .containsExactly("X-CSRF-Token");
  }

  @Test
  void testSuccess_getContactInfo() throws IOException {
    User user = DatabaseHelper.createAdminUser("email@email.com");
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleUserDataAction action =
        createAction(Optional.of(ConsoleApiParamsUtils.createFake(authResult)));
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    Map jsonObject =
        GSON.fromJson(((FakeResponse) consoleApiParams.response()).getPayload(), Map.class);
    assertThat(jsonObject)
        .containsExactly(
            "userRoles",
            ImmutableMap.of(),
            "isAdmin",
            true,
            "technicalDocsUrl",
            "test",
            "globalRole",
            "FTE",
            "productName",
            "Nomulus",
            "supportPhoneNumber",
            "+1 (212) 867 5309",
            "supportEmail",
            "support@example.com");
  }

  @Test
  void testFailure_notAuthenticated() throws IOException {
    ConsoleUserDataAction action = createAction(Optional.empty());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_UNAUTHORIZED);
  }

  private ConsoleUserDataAction createAction(Optional<ConsoleApiParams> maybeConsoleApiParams)
      throws IOException {
    consoleApiParams =
        maybeConsoleApiParams.orElseGet(
            () -> ConsoleApiParamsUtils.createFake(AuthResult.NOT_AUTHENTICATED));
    when(consoleApiParams.request().getMethod()).thenReturn("GET");
    return new ConsoleUserDataAction(
        consoleApiParams, "Nomulus", "support@example.com", "+1 (212) 867 5309", "test");
  }
}
