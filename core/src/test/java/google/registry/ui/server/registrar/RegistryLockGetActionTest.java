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
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import google.registry.request.Action.Method;
import google.registry.request.auth.AuthLevel;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.RegistrarAccessDeniedException;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link RegistryLockGetAction}. */
@RunWith(JUnit4.class)
public final class RegistryLockGetActionTest {

  private static final Gson GSON = new Gson();

  @Rule public final AppEngineRule appEngineRule = AppEngineRule.builder().withDatastore().build();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeResponse response = new FakeResponse();
  private final User user = new User("marla.singer@example.com", "gmail.com", "12345");

  private AuthResult authResult;
  private RegistryLockGetAction action;

  @Mock ExistingRegistryLocksRetriever retriever;

  @Before
  public void setup() {
    authResult = AuthResult.create(AuthLevel.USER, UserAuthInfo.create(user, false));
    action =
        new RegistryLockGetAction(
            Method.GET, response, authResult, retriever, Optional.of("clientId"));
  }

  @Test
  public void testSuccess_retrievesLocks() throws Exception {
    ImmutableMap<String, Object> resultMap =
        ImmutableMap.of(
            "lockEnabledForContact",
            true,
            "email",
            "marla.singer@example.com",
            "locks",
            ImmutableList.of(),
            "clientId",
            "clientId");
    when(retriever.getLockedDomainsMap("clientId")).thenReturn(resultMap);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            GSON.toJson(
                ImmutableMap.of(
                    "status", "SUCCESS",
                    "message", "Successful locks retrieval",
                    "results", ImmutableList.of(resultMap))));
  }

  @Test
  public void testFailure_invalidMethod() {
    action.method = Method.POST;
    assertThat(assertThrows(IllegalArgumentException.class, action::run))
        .hasMessageThat()
        .isEqualTo("Only GET requests allowed");
  }

  @Test
  public void testFailure_noAuthInfo() {
    action.authResult = AuthResult.NOT_AUTHENTICATED;
    assertThat(assertThrows(IllegalArgumentException.class, action::run))
        .hasMessageThat()
        .isEqualTo("User auth info must be present");
  }

  @Test
  public void testFailure_noClientId() {
    action.paramClientId = Optional.empty();
    assertThat(assertThrows(IllegalArgumentException.class, action::run))
        .hasMessageThat()
        .isEqualTo("clientId must be present");
  }

  @Test
  public void testFailure_noRegistrarAccess() throws Exception {
    String errorMessage =
        String.format(
            "%s doesn't have access to registrar clientId", authResult.userIdForLogging());
    when(retriever.getLockedDomainsMap("clientId"))
        .thenThrow(new RegistrarAccessDeniedException(errorMessage));
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
  }
}
