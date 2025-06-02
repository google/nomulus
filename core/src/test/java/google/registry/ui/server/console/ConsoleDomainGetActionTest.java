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
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link google.registry.ui.server.console.ConsoleDomainGetAction}. */
public class ConsoleDomainGetActionTest extends ConsoleActionBaseTestCase {

  @BeforeEach
  void beforeEach() {
    DatabaseHelper.persistActiveDomain("exists.tld");
  }

  @Test
  void testSuccess_fullJsonRepresentation() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.createUser(
                createUser(
                    new UserRoles.Builder()
                        .setRegistrarRoles(
                            ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                        .build())),
            "exists.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            "{\"domainName\":\"exists.tld\",\"adminContact\":{\"key\":\"3-ROID\",\"kind\":"
                + "\"google.registry.model.contact.Contact\"},\"techContact\":{\"key\":\"3-ROID\","
                + "\"kind\":\"google.registry.model.contact.Contact\"},\"registrantContact\":"
                + "{\"key\":\"3-ROID\",\"kind\":\"google.registry.model.contact.Contact\"},"
                + "\"registrationExpirationTime\":\"294247-01-10T04:00:54.775Z\","
                + "\"lastTransferTime\":\"null\",\"repoId\":\"2-TLD\","
                + "\"currentSponsorRegistrarId\":\"TheRegistrar\",\"creationRegistrarId\":"
                + "\"TheRegistrar\",\"creationTime\":{\"creationTime\":"
                + "\"1970-01-01T00:00:00.000Z\"},\"lastEppUpdateTime\":\"null\",\"statuses\":"
                + "[\"INACTIVE\"]}");
  }

  @Test
  void testFailure_emptyAuth() {
    ConsoleDomainGetAction action = createAction(AuthResult.NOT_AUTHENTICATED, "exists.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_UNAUTHORIZED);
  }

  @Test
  void testFailure_appAuth() {
    ConsoleDomainGetAction action =
        createAction(AuthResult.createApp("service@registry.example"), "exists.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_UNAUTHORIZED);
  }

  @Test
  void testFailure_noAccessToRegistrar() {
    ConsoleDomainGetAction action =
        createAction(
            AuthResult.createUser(createUser(new UserRoles.Builder().build())), "exists.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_FOUND);
  }

  @Test
  void testFailure_nonexistentDomain() {
    ConsoleDomainGetAction action = createAction(AuthResult.createUser(fteUser), "nonexistent.tld");
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_FOUND);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setUserRoles(userRoles)
        .build();
  }

  private ConsoleDomainGetAction createAction(AuthResult authResult, String domain) {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    response = (FakeResponse) consoleApiParams.response();
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.GET.toString());
    return new ConsoleDomainGetAction(consoleApiParams, domain);
  }
}
