// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeResponse;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.ConsoleUserDataAction}. */
class ConsoleUserDataActionTest {

  private static final Gson GSON = RequestModule.provideGson();
  private FakeResponse response = new FakeResponse();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @Test
  void testSuccess_getContactInfo() throws IOException {
    User user =
        new User.Builder()
            .setEmailAddress("email@email.com")
            .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
            .build();

    ConsoleUserDataAction action =
        createAction(AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user)));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    Map jsonObject = GSON.fromJson(response.getPayload(), Map.class);
    assertThat(jsonObject)
        .containsExactly("isAdmin", false, "technicalDocsUrl", "test", "globalRole", "FTE");
  }

  @Test
  void testFailure_notAConsoleUser() throws IOException {
    ConsoleUserDataAction action =
        createAction(
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    new com.google.appengine.api.users.User(
                        "JohnDoe@theregistrar.com", "theregistrar.com"),
                    false)));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_UNAUTHORIZED);
  }

  private ConsoleUserDataAction createAction(AuthResult authResult) throws IOException {
    return new ConsoleUserDataAction(authResult, response, "test");
  }
}
