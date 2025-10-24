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
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.domain.Domain;
import google.registry.request.Action;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleDomainListAction.DomainListResult;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link ConsoleDomainListAction}. */
public class ConsoleDomainListActionTest extends ConsoleActionBaseTestCase {

  @BeforeEach
  void beforeEach() {
    for (int i = 0; i < 10; i++) {
      DatabaseHelper.persistActiveDomain(i + "exists.tld", clock.nowUtc());
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
    assertThat(result.checkpointTime).isEqualTo(clock.nowUtc());
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
    assertThat(result.checkpointTime).isEqualTo(clock.nowUtc());
  }

  @Test
  void testSuccess_pages() {
    // Two pages of results should go in reverse chronological order
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, null);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("9exists.tld", "8exists.tld", "7exists.tld", "6exists.tld", "5exists.tld");
    assertThat(result.totalResults).isEqualTo(10);

    // Now do the second page
    action = createAction("TheRegistrar", result.checkpointTime, 1, 5, 10L, null);
    action.run();
    result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("4exists.tld", "3exists.tld", "2exists.tld", "1exists.tld", "0exists.tld");
  }

  @Test
  void testSuccess_partialPage() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 1, 8, null, null);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("1exists.tld", "0exists.tld");
  }

  @Test
  void testSuccess_checkpointTime_createdBefore() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 10, null, null);
    action.run();

    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).hasSize(10);
    assertThat(result.totalResults).isEqualTo(10);

    clock.advanceOneMilli();
    persistActiveDomain("newdomain.tld", clock.nowUtc());

    // Even though we persisted a new domain, the old checkpoint should return no more results
    action = createAction("TheRegistrar", result.checkpointTime, 1, 10, null, null);
    action.run();
    result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).isEmpty();
    assertThat(result.totalResults).isEqualTo(10);
  }

  @Test
  void testSuccess_checkpointTime_deletion() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, null);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);

    clock.advanceOneMilli();
    Domain toDelete =
        ForeignKeyUtils.loadResource(Domain.class, "0exists.tld", clock.nowUtc()).get();
    persistDomainAsDeleted(toDelete, clock.nowUtc());

    // Second page should include the domain that is now deleted due to the checkpoint time
    action = createAction("TheRegistrar", result.checkpointTime, 1, 5, null, null);
    action.run();
    result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains.stream().map(Domain::getDomainName).collect(toImmutableList()))
        .containsExactly("4exists.tld", "3exists.tld", "2exists.tld", "1exists.tld", "0exists.tld");
  }

  @Test
  void testSuccess_searchTerm_oneMatch() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, "0");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(Iterables.getOnlyElement(result.domains).getDomainName()).isEqualTo("0exists.tld");
  }

  @Test
  void testSuccess_searchTerm_returnsNone() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, "deleted");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).isEmpty();
  }

  @Test
  void testSuccess_searchTerm_caseInsensitive() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, "eXiStS");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).hasSize(5);
    assertThat(result.totalResults).isEqualTo(10);
  }

  @Test
  void testSuccess_searchTerm_tld() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 5, null, "tld");
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).hasSize(5);
    assertThat(result.totalResults).isEqualTo(10);
  }

  @Test
  void testPartialSuccess_pastEnd() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 5, 5, null, null);
    action.run();
    DomainListResult result = GSON.fromJson(response.getPayload(), DomainListResult.class);
    assertThat(result.domains).isEmpty();
  }

  @Test
  void testFailure_invalidResultsPerPage() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, 0, 0, null, null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Results per page must be between 1 and 500 inclusive");

    action = createAction("TheRegistrar", null, 0, 501, null, null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Results per page must be between 1 and 500 inclusive");
  }

  @Test
  void testFailure_invalidPageNumber() {
    ConsoleDomainListAction action = createAction("TheRegistrar", null, -1, 10, null, null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Page number must be non-negative");
  }

  private ConsoleDomainListAction createAction(String registrarId) {
    return createAction(registrarId, null, null, null, null, null);
  }

  private ConsoleDomainListAction createAction(
      String registrarId,
      @Nullable DateTime checkpointTime,
      @Nullable Integer pageNumber,
      @Nullable Integer resultsPerPage,
      @Nullable Long totalResults,
      @Nullable String searchTerm) {
    AuthResult authResult = AuthResult.createUser(fteUser);
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.GET.toString());
    response = (FakeResponse) consoleApiParams.response();
    return new ConsoleDomainListAction(
        consoleApiParams,
        registrarId,
        Optional.ofNullable(checkpointTime),
        Optional.ofNullable(pageNumber),
        Optional.ofNullable(resultsPerPage),
        Optional.ofNullable(totalResults),
        Optional.ofNullable(searchTerm));
  }
}
