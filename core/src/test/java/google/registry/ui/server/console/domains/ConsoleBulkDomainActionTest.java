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

package google.registry.ui.server.console.domains;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.minusMonths;
import static google.registry.util.DateTimeUtils.plusDays;
import static google.registry.util.DateTimeUtils.plusMonths;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.ui.server.console.ConsoleHistoryDataAction;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link ConsoleBulkDomainAction}. */
public class ConsoleBulkDomainActionTest extends ConsoleActionBaseTestCase {

  private static ImmutableSet<StatusValue> serverSuspensionStatuses =
      ImmutableSet.of(
          StatusValue.SERVER_RENEW_PROHIBITED,
          StatusValue.SERVER_TRANSFER_PROHIBITED,
          StatusValue.SERVER_UPDATE_PROHIBITED,
          StatusValue.SERVER_DELETE_PROHIBITED,
          StatusValue.SERVER_HOLD);

  private EppController eppController;
  private Domain domain;

  @BeforeEach
  void beforeEach() {
    eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(EppTestComponent.FakesAndMocksModule.create(clock))
            .build()
            .startRequest()
            .eppController();
    domain =
        persistDomainWithDependentResources(
            "example",
            "tld",
            clock.now(),
            minusMonths(clock.now(), 1),
            plusMonths(clock.now(), 11));
  }

  @Test
  void testSuccess_delete() {
    ConsoleBulkDomainAction action =
        createAction(
            "DELETE",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
"""
{"example.tld":{"message":"Command completed successfully; action pending",\
"responseCode":1001}}\
""");
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(plusDays(clock.now(), 35));
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_DELETE);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testSuccess_suspend() throws Exception {
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeastElementsIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testSuccess_unsuspend() throws Exception {
    persistResource(domain.asBuilder().addStatusValues(serverSuspensionStatuses).build());
    ConsoleBulkDomainAction action =
        createAction(
            "UNSUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeastElementsIn(serverSuspensionStatuses);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_UNSUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testHalfSuccess_halfNonexistent() throws Exception {
    ConsoleBulkDomainAction action =
        createAction(
            "DELETE",
            GSON.toJsonTree(
                ImmutableMap.of(
                    "domainList",
                    ImmutableList.of("example.tld", "nonexistent.tld"),
                    "reason",
                    "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
"""
{"example.tld":{"message":"Command completed successfully; action pending","responseCode":1001},\
"nonexistent.tld":{"message":"The domain with given ID (nonexistent.tld) doesn\\u0027t exist.",\
"responseCode":2303}}\
""");
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(plusDays(clock.now(), 35));
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_DELETE);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testFailure_badActionString() {
    ConsoleBulkDomainAction action =
        createAction(
            "bad", GSON.toJsonTree(ImmutableMap.of("domainList", ImmutableList.of("domain.tld"))));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo(
            "No enum constant"
                + " google.registry.ui.server.console.domains.ConsoleDomainActionType.BulkAction.bad");
  }

  @Test
  void testFailure_emptyBody() {
    ConsoleBulkDomainAction action = createAction("DELETE", null);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Bulk action payload must be present");
  }

  @Test
  void testFailure_listTooLarge() {
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of(
                "domainList", Collections.nCopies(6, "domain.tld"), "reason", "reason"));
    ConsoleBulkDomainAction action = createAction("DELETE", payload, fteUser, 5);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload())
        .isEqualTo("Cannot process more than 5 domains in a single bulk action");
  }

  @Test
  void testFailure_emptyList() {
    JsonElement payload =
        GSON.toJsonTree(ImmutableMap.of("domainList", ImmutableList.of(), "reason", "reason"));
    ConsoleBulkDomainAction action = createAction("DELETE", payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Domain list cannot be empty");
  }

  @Test
  void testFailure_noPermission() {
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of("domainList", ImmutableList.of("domain.tld"), "reason", "reason"));
    ConsoleBulkDomainAction action =
        createAction(
            "DELETE",
            payload,
            new User.Builder()
                .setEmailAddress("foobar@theregistrar.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setRegistrarRoles(
                            ImmutableMap.of("TheRegistrar", RegistrarRole.ACCOUNT_MANAGER))
                        .build())
                .build());
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_disabledRegistrar() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setState(Registrar.State.DISABLED).build());
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test"));
    ConsoleBulkDomainAction action = createAction("DELETE", payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_nonexistentRegistrar() {
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test"));
    ConsoleBulkDomainAction action = createAction("nonexistentRegistrar", "DELETE", payload);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testSuccess_suspend_nonAdminFte() {
    User nonAdminFte =
        persistResource(
            new User.Builder()
                .setEmailAddress("nonadmin-fte@example.com")
                .setUserRoles(
                    new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(false).build())
                .build());
    assertThat(nonAdminFte.getUserRoles().isAdmin()).isFalse();
    persistResource(
        domain.asBuilder().addStatusValue(StatusValue.CLIENT_UPDATE_PROHIBITED).build());

    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy")),
            nonAdminFte);
    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeastElementsIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
    assertThat(history.getActingUser().getEmailAddress()).isEqualTo("nonadmin-fte@example.com");
  }

  @Test
  void testSuccess_unsuspend_nonAdminFte() {
    User nonAdminFte =
        persistResource(
            new User.Builder()
                .setEmailAddress("nonadmin-fte@example.com")
                .setUserRoles(
                    new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(false).build())
                .build());
    persistResource(domain.asBuilder().addStatusValues(serverSuspensionStatuses).build());

    ConsoleBulkDomainAction action =
        createAction(
            "UNSUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "cured")),
            nonAdminFte);
    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_UNSUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
    assertThat(history.getActingUser().getEmailAddress()).isEqualTo("nonadmin-fte@example.com");
  }

  @Test
  void testSuccess_suspend_nonAdminSupportLead() {
    User supportLead =
        persistResource(
            new User.Builder()
                .setEmailAddress("support-lead@example.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setGlobalRole(GlobalRole.SUPPORT_LEAD)
                        .setIsAdmin(false)
                        .build())
                .build());
    assertThat(supportLead.getUserRoles().isAdmin()).isFalse();

    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy")),
            supportLead);
    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeastElementsIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
    assertThat(history.getActingUser().getEmailAddress()).isEqualTo("support-lead@example.com");
  }

  @Test
  void testSuccess_unsuspend_nonAdminSupportLead() {
    User supportLead =
        persistResource(
            new User.Builder()
                .setEmailAddress("support-lead@example.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setGlobalRole(GlobalRole.SUPPORT_LEAD)
                        .setIsAdmin(false)
                        .build())
                .build());
    persistResource(domain.asBuilder().addStatusValues(serverSuspensionStatuses).build());

    ConsoleBulkDomainAction action =
        createAction(
            "UNSUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "cured")),
            supportLead);
    action.run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_UNSUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
    assertThat(history.getActingUser().getEmailAddress()).isEqualTo("support-lead@example.com");
  }

  @Test
  void testSuccess_historyQueryableByRegistrarId() {
    User supportLead =
        persistResource(
            new User.Builder()
                .setEmailAddress("support-lead@example.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setGlobalRole(GlobalRole.SUPPORT_LEAD)
                        .setIsAdmin(false)
                        .build())
                .build());
    createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy")),
            supportLead)
        .run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);

    ConsoleApiParams historyParams =
        ConsoleApiParamsUtils.createFake(AuthResult.createUser(supportLead));
    when(historyParams.request().getMethod()).thenReturn("GET");
    FakeResponse historyResponse = (FakeResponse) historyParams.response();
    new ConsoleHistoryDataAction(
            historyParams, "support@example.com", 500, "TheRegistrar", Optional.empty())
        .run();

    assertThat(historyResponse.getStatus()).isEqualTo(SC_OK);
    assertThat(historyResponse.getPayload()).contains("TheRegistrar|example.tld");
    assertThat(historyResponse.getPayload()).contains("DOMAIN_SUSPEND");
  }

  @Test
  void testFailure_suspend_supportAgentForbidden() {
    User supportAgent =
        persistResource(
            new User.Builder()
                .setEmailAddress("support-agent@example.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setGlobalRole(GlobalRole.SUPPORT_AGENT)
                        .setIsAdmin(false)
                        .build())
                .build());
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy"));

    createAction("SUSPEND", payload, supportAgent).run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);

    createAction("UNSUSPEND", payload, supportAgent).run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_suspend_registrarTechContactForbidden() {
    User techContact =
        persistResource(
            new User.Builder()
                .setEmailAddress("tech@theregistrar.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setRegistrarRoles(
                            ImmutableMap.of("TheRegistrar", RegistrarRole.TECH_CONTACT))
                        .build())
                .build());
    JsonElement payload =
        GSON.toJsonTree(
            ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy"));

    createAction("SUSPEND", payload, techContact).run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_delete_nonAdminDoesNotRunAsSuperuser() {
    User supportLead =
        persistResource(
            new User.Builder()
                .setEmailAddress("support-lead@example.com")
                .setUserRoles(
                    new UserRoles.Builder()
                        .setGlobalRole(GlobalRole.SUPPORT_LEAD)
                        .setIsAdmin(false)
                        .build())
                .build());
    assertDeleteDoesNotBypassServerDeleteProhibited(supportLead);
  }

  @Test
  void testFailure_delete_adminDoesNotRunAsSuperuser() {
    // Superuser deletes (which bypass server statuses and can delete immediately) are only
    // available through the nomulus CLI, not through the bulk domain action, even for admins.
    assertThat(fteUser.getUserRoles().isAdmin()).isTrue();
    assertDeleteDoesNotBypassServerDeleteProhibited(fteUser);
  }

  private void assertDeleteDoesNotBypassServerDeleteProhibited(User user) {
    Domain lockedDomain =
        persistResource(
            domain.asBuilder().addStatusValue(StatusValue.SERVER_DELETE_PROHIBITED).build());

    createAction(
            "DELETE",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")),
            user)
        .run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).contains("\"responseCode\":2304");
    assertThat(loadByEntity(lockedDomain)).isEqualTo(lockedDomain);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  private ConsoleBulkDomainAction createAction(String action, JsonElement payload) {
    return createAction("TheRegistrar", action, payload, fteUser, 500);
  }

  private ConsoleBulkDomainAction createAction(
      String registrarId, String action, JsonElement payload) {
    return createAction(registrarId, action, payload, fteUser, 500);
  }

  private ConsoleBulkDomainAction createAction(String action, JsonElement payload, User user) {
    return createAction("TheRegistrar", action, payload, user, 500);
  }

  private ConsoleBulkDomainAction createAction(
      String action, JsonElement payload, User user, int limit) {
    return createAction("TheRegistrar", action, payload, user, limit);
  }

  private ConsoleBulkDomainAction createAction(
      String registrarId, String action, JsonElement payload, User user, int limit) {
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(authResult);
    when(params.request().getMethod()).thenReturn("POST");
    response = (FakeResponse) params.response();
    return new ConsoleBulkDomainAction(
        params, eppController, limit, registrarId, action, Optional.ofNullable(payload));
  }
}
