// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsoleDumDownloadActionTest extends ConsoleActionBaseTestCase {

  @BeforeEach
  void beforeEach() {
    for (int i = 0; i < 3; i++) {
      DatabaseHelper.persistActiveDomain(
          i + "exists.tld", clock.nowUtc(), clock.nowUtc().plusDays(300));
      clock.advanceOneMilli();
    }
    DatabaseHelper.persistDeletedDomain("deleted.tld", clock.nowUtc().minusDays(1));
  }

  @Test
  void testSuccess_returnsCorrectDomains() throws IOException {
    AuthResult authResult = AuthResult.createUser(fteUser);
    ConsoleDumDownloadAction action = createAction(authResult);
    action.run();
    ImmutableList<String> expected =
        ImmutableList.of(
            "Domain Name,Creation Time,Expiration Time,Domain Statuses",
            "2exists.tld,2024-04-15 00:00:00.002+00,2025-02-09 00:00:00.002+00,{INACTIVE}",
            "1exists.tld,2024-04-15 00:00:00.001+00,2025-02-09 00:00:00.001+00,{INACTIVE}",
            "0exists.tld,2024-04-15 00:00:00+00,2025-02-09 00:00:00+00,{INACTIVE}");
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    ImmutableList<String> actual =
        ImmutableList.copyOf(response.getStringWriter().toString().split("\r\n"));
    assertThat(actual).containsExactlyElementsIn(expected);
  }

  @Test
  void testFailure_forbidden() {
    UserRoles userRoles =
        new UserRoles.Builder().setGlobalRole(GlobalRole.NONE).setIsAdmin(false).build();

    User user =
        new User.Builder().setEmailAddress("email@email.com").setUserRoles(userRoles).build();

    AuthResult authResult = AuthResult.createUser(user);
    ConsoleDumDownloadAction action = createAction(authResult);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  private ConsoleDumDownloadAction createAction(AuthResult authResult) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) consoleApiParams.response();
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.GET.toString());
    return new ConsoleDumDownloadAction(clock, consoleApiParams, "TheRegistrar", "test_name");
  }
}
