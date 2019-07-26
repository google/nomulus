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
import static javax.servlet.http.HttpServletResponse.SC_MOVED_TEMPORARILY;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.User;
import com.google.appengine.api.users.UserService;
import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.collect.ImmutableMap;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class RegistryLockVerifyActionTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create("marla.singer@example.com", "12345"))
          .build();

  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private final FakeResponse response = new FakeResponse();
  private final UserService userService = UserServiceFactory.getUserService();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");
  private final UUID lockId = UUID.fromString("f1be78a2-2d61-458c-80f0-9dd8f2f8625f");

  private AuthResult authResult;
  private RegistryLockVerifyAction action;

  @Before
  public void setup() {
    when(request.getRequestURI()).thenReturn("https://registry.example/registry-lock-verification");
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action =
        new RegistryLockVerifyAction(
            request,
            response,
            userService,
            authResult,
            ImmutableMap.of(),
            "logoFilename",
            "productName",
            lockId);
  }

  @Test
  public void testFailure_notLoggedIn() {
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_MOVED_TEMPORARILY);
    assertThat(response.getHeaders()).containsKey("Location");
  }
}
