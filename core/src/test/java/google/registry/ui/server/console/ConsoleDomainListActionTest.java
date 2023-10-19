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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;

import com.google.api.client.http.HttpStatusCodes;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import google.registry.ui.server.console.ConsoleDomainListAction.DomainListRequest;
import google.registry.ui.server.console.ConsoleDomainListAction.DomainListResult;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link ConsoleDomainListAction}. */
public class ConsoleDomainListActionTest {

  private static final Gson GSON = GsonUtils.provideGson();

  private final FakeClock clock = new FakeClock(DateTime.parse("2023-10-20T00:00:00.000Z"));

  private FakeResponse response = new FakeResponse();

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    for (int i = 0; i < 10; i++) {
      DatabaseHelper.persistActiveDomain(i + "exists.tld");
      clock.advanceOneMilli();
    }
    DatabaseHelper.persistDeletedDomain("deleted.tld", clock.nowUtc().minusDays(1));
  }

  @Test
  void testSuccess_allDomains() {
    ConsoleDomainListAction action = createAction("TheRegistrar");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).hasSize(10);
    assertThat(result.totalResults).isEqualTo(10);
    assertThat(result.createdBeforeTime).isEqualTo(clock.nowUtc());
    assertThat(result.domains.stream().anyMatch(d -> d.getDomainName().equals("deleted.tld")))
        .isFalse();
  }

  @Test
  void testSuccess_noDomains() {
    ConsoleDomainListAction action = createAction("NewRegistrar");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).hasSize(0);
    assertThat(result.totalResults).isEqualTo(0);
    assertThat(result.createdBeforeTime).isEqualTo(clock.nowUtc());
  }

  @Test
  void testSuccess_pages() {
    // Two pages of results should go in reverse chronological order
    DomainListRequest request = new DomainListRequest(null, 0, 5, null);
    ConsoleDomainListAction action = createAction("TheRegistrar", request);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("9exists.tld", "8exists.tld", "7exists.tld", "6exists.tld", "5exists.tld");
    assertThat(result.totalResults).isEqualTo(10);

    // Now do the second page
    request = new DomainListRequest(result.createdBeforeTime, 1, 5, 10L);
    // reset the response to allow a second call
    response = new FakeResponse();
    action = createAction("TheRegistrar", request);
    action.run();
    result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("4exists.tld", "3exists.tld", "2exists.tld", "1exists.tld", "0exists.tld");
  }

  @Test
  void testSuccess_partialPage() {
    DomainListRequest request = new DomainListRequest(null, 1, 8, null);
    ConsoleDomainListAction action = createAction("TheRegistrar", request);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("1exists.tld", "0exists.tld");
  }

  @Test
  void testPartialSuccess_pastEnd() {
    DomainListRequest request = new DomainListRequest(null, 5, 5, null);
    ConsoleDomainListAction action = createAction("TheRegistrar", request);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).isEmpty();
  }

  @Test
  void testFailure_invalidResultsPerPage() {
    DomainListRequest request = new DomainListRequest(null, 0, 0, null);
    ConsoleDomainListAction action = createAction("TheRegistrar", request);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Results per page must be between 1 and 500 inclusive");

    response = new FakeResponse();
    request = new DomainListRequest(null, 0, 501, null);
    action = createAction("TheRegistrar", request);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Results per page must be between 1 and 500 inclusive");
  }

  @Test
  void testFailure_invalidPageNumber() {
    DomainListRequest request = new DomainListRequest(null, -1, 10, null);
    ConsoleDomainListAction action = createAction("TheRegistrar", request);
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Page number must be non-negative");
  }

  private static DomainListRequest defaultListRequest() {
    return new DomainListRequest(null, 0, 50, null);
  }

  private ConsoleDomainListAction createAction(String registrarId) {
    return createAction(registrarId, defaultListRequest());
  }

  private ConsoleDomainListAction createAction(String registrarId, DomainListRequest request) {
    AuthResult authResult =
        AuthResult.createUser(
            UserAuthInfo.create(
                new User.Builder()
                    .setEmailAddress("email@email.example")
                    .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
                    .build()));
    return new ConsoleDomainListAction(authResult, response, GSON, registrarId, request);
  }
}
