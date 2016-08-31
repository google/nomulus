// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;

import com.google.appengine.api.users.UserServiceFactory;
import com.google.common.net.MediaType;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import google.registry.testing.UserInfo;
import javax.servlet.http.HttpServletRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link ConsoleUiAction}. */
@RunWith(MockitoJUnitRunner.class)
public class ConsoleUiActionTest {

  @Rule
  public final AppEngineRule appEngineRule = AppEngineRule.builder()
      .withDatastore()
      .withUserService(UserInfo.create("marla.singer@example.com", "12345"))
      .build();

  @Mock
  private SessionUtils sessionUtils;

  private final FakeResponse response = new FakeResponse();
  private final ConsoleUiAction action = new ConsoleUiAction();

  @Before
  public void setUp() throws Exception {
    action.enabled = true;
    action.response = response;
    action.sessionUtils = sessionUtils;
    action.userService = UserServiceFactory.getUserService();
    when(sessionUtils.checkRegistrarConsoleLogin(any(HttpServletRequest.class))).thenReturn(true);
    when(sessionUtils.getRegistrarClientId(any(HttpServletRequest.class)))
        .thenReturn("TheRegistrar");
  }

  @Test
  public void testWebPage_disallowsIframe() throws Exception {
    action.run();
    assertThat(response.getHeaders()).containsEntry("X-Frame-Options", "SAMEORIGIN");
  }

  @Test
  public void testWebPage_setsHtmlUtf8ContentType() throws Exception {
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.HTML_UTF_8);
  }

  @Test
  public void testWebPage_containsUserNickname() throws Exception {
    action.run();
    assertThat(response.getPayload()).contains("marla.singer");
  }

  @Test
  public void testUserHasAccessAsTheRegistrar_showsRegistrarConsole() throws Exception {
    action.run();
    assertThat(response.getPayload()).contains("Registrar Console");
    assertThat(response.getPayload()).contains("reg-content-and-footer");
  }

  @Test
  public void testConsoleDisabled_showsDisabledPage() throws Exception {
    action.enabled = false;
    action.run();
    assertThat(response.getPayload()).contains("<h1>Console is disabled</h1>");
  }

  @Test
  public void testUserDoesntHaveAccessToAnyRegistrar_showsWhoAreYouPage() throws Exception {
    when(sessionUtils.checkRegistrarConsoleLogin(any(HttpServletRequest.class))).thenReturn(false);
    action.run();
    assertThat(response.getPayload()).contains("<h1>You need permission</h1>");
  }
}
