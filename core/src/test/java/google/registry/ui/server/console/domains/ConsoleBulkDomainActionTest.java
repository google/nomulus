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
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistDomainWithPendingTransfer;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.minusDays;
import static google.registry.util.DateTimeUtils.minusMonths;
import static google.registry.util.DateTimeUtils.plusDays;
import static google.registry.util.DateTimeUtils.plusMonths;
import static google.registry.util.DateTimeUtils.plusYears;
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
import google.registry.model.domain.DomainHistory;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.ui.server.console.ConsoleHistoryDataAction;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link ConsoleBulkDomainAction}. */
public class ConsoleBulkDomainActionTest extends ConsoleActionBaseTestCase {

  private static final String REGISTRY_ADMIN_CLIENT_ID = "AdminRegistrar";

  private static final String SUSPENDED_POLL_MESSAGE =
      "The registry administrator has added the status(es) [serverDeleteProhibited, serverHold,"
          + " serverRenewProhibited, serverTransferProhibited, serverUpdateProhibited].";

  private static final String UNSUSPENDED_POLL_MESSAGE =
      "The registry administrator has removed the status(es) [serverDeleteProhibited, serverHold,"
          + " serverRenewProhibited, serverTransferProhibited, serverUpdateProhibited].";

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
    persistNewRegistrar(
        REGISTRY_ADMIN_CLIENT_ID, "Admin Registrar", Registrar.Type.INTERNAL, 9999L);
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getDeletionTime()).isEqualTo(plusDays(clock.now(), 35));
    assertThat(updatedDomain.getLastEppUpdateRegistrarId()).isEqualTo("TheRegistrar");
    DomainHistory domainHistory =
        getOnlyHistoryEntryOfType(
            updatedDomain, HistoryEntry.Type.DOMAIN_DELETE, DomainHistory.class);
    assertThat(domainHistory.getRegistrarId()).isEqualTo("TheRegistrar");
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsAtLeastElementsIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, SUSPENDED_POLL_MESSAGE);
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, UNSUSPENDED_POLL_MESSAGE);
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
  void testFailure_adminDeleteDomainOwnedByDifferentRegistrar() {
    ConsoleBulkDomainAction action =
        createAction(
            "NewRegistrar",
            "DELETE",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Domain example.tld is not owned by registrar NewRegistrar",\
            "responseCode":2201}}\
            """);
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(domain.getDeletionTime());
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  @Test
  void testFailure_adminSuspendDomainOwnedByDifferentRegistrar() {
    ConsoleBulkDomainAction action =
        createAction(
            "NewRegistrar",
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Domain example.tld is not owned by registrar NewRegistrar",\
            "responseCode":2201}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  @Test
  void testHalfSuccess_halfWrongRegistrar() {
    Domain otherDomain =
        persistResource(
            persistDomainWithDependentResources(
                    "other",
                    "tld",
                    clock.now(),
                    minusMonths(clock.now(), 1),
                    plusMonths(clock.now(), 11))
                .asBuilder()
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .build());
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of(
                    "domainList", ImmutableList.of("example.tld", "other.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000},\
            "other.tld":{"message":"Domain other.tld is not owned by registrar TheRegistrar",\
            "responseCode":2201}}\
            """);
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsAtLeastElementsIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, SUSPENDED_POLL_MESSAGE);
    assertThat(loadByEntity(otherDomain).getStatusValues())
        .containsNoneIn(serverSuspensionStatuses);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testSuccess_duplicateDomainsDeduplicated() {
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of(
                    "domainList",
                    ImmutableList.of("example.tld", "example.tld"),
                    "reason",
                    "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}\
            """);
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsAtLeastElementsIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, SUSPENDED_POLL_MESSAGE);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.DOMAIN_SUSPEND);
    assertThat(history.getDescription()).hasValue("TheRegistrar|example.tld");
  }

  @Test
  void testFailure_nullDomainInList() {
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of(
                    "domainList", Arrays.asList("example.tld", null), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Domain list cannot contain null entries");
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  @Test
  void testFailure_suspendNonexistentDomain() {
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of(
                    "domainList", ImmutableList.of("nonexistent.tld"), "reason", "test")));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"nonexistent.tld":{"message":\
            "The domain with given ID (nonexistent.tld) doesn\\u0027t exist.",\
            "responseCode":2303}}\
            """);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  @Test
  void testFailure_deletedDomain() {
    persistResource(domain.asBuilder().setDeletionTime(minusDays(clock.now(), 1)).build());
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
            {"example.tld":{"message":\
            "The domain with given ID (example.tld) doesn\\u0027t exist.",\
            "responseCode":2303}}\
            """);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  @Test
  void testFailure_adminSuspendDomainWithExpiredPendingTransferToDifferentRegistrar() {
    persistDomainWithPendingTransfer(
        domain,
        minusDays(clock.now(), 6),
        minusDays(clock.now(), 1),
        plusYears(domain.getRegistrationExpirationTime(), 1));
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
            {"example.tld":{"message":"Domain example.tld is not owned by registrar TheRegistrar",\
            "responseCode":2201}}\
            """);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsAtLeastElementsIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, SUSPENDED_POLL_MESSAGE);
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, UNSUSPENDED_POLL_MESSAGE);
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsAtLeastElementsIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, SUSPENDED_POLL_MESSAGE);
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
    Domain updatedDomain = loadByEntity(domain);
    assertThat(updatedDomain.getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertSuperuserUpdateRecorded(updatedDomain, UNSUSPENDED_POLL_MESSAGE);
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
  void testFailure_suspendDomainOwnedByDifferentRegistrar_nonAdminFte() {
    User nonAdminFte =
        persistResource(
            new User.Builder()
                .setEmailAddress("nonadmin-fte@example.com")
                .setUserRoles(
                    new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(false).build())
                .build());
    assertCrossRegistrarSuspendRejected(nonAdminFte);
  }

  @Test
  void testFailure_unsuspendDomainOwnedByDifferentRegistrar_nonAdminFte() {
    User nonAdminFte =
        persistResource(
            new User.Builder()
                .setEmailAddress("nonadmin-fte@example.com")
                .setUserRoles(
                    new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(false).build())
                .build());
    assertCrossRegistrarUnsuspendRejected(nonAdminFte);
  }

  @Test
  void testFailure_suspendDomainOwnedByDifferentRegistrar_nonAdminSupportLead() {
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
    assertCrossRegistrarSuspendRejected(supportLead);
  }

  @Test
  void testFailure_unsuspendDomainOwnedByDifferentRegistrar_nonAdminSupportLead() {
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
    assertCrossRegistrarUnsuspendRejected(supportLead);
  }

  @Test
  void testFailure_delete_adminDoesNotRunAsSuperuser() {
    // Superuser deletes (which bypass server statuses and can delete immediately) are only
    // available through the nomulus CLI, not through the bulk domain action, even for admins.
    assertThat(fteUser.getUserRoles().isAdmin()).isTrue();
    assertDeleteDoesNotBypassServerDeleteProhibited(fteUser);
  }

  private void assertCrossRegistrarSuspendRejected(User user) {
    assertThat(user.getUserRoles().isAdmin()).isFalse();
    createAction(
            "NewRegistrar",
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "policy")),
            user)
        .run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Domain example.tld is not owned by registrar NewRegistrar",\
            "responseCode":2201}}\
            """);
    Domain reloadedDomain = loadByEntity(domain);
    assertThat(reloadedDomain.getStatusValues()).containsNoneIn(serverSuspensionStatuses);
    assertThat(reloadedDomain).isEqualTo(domain);
    assertThat(loadSingleton(PollMessage.OneTime.class)).isEmpty();
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
  }

  private void assertCrossRegistrarUnsuspendRejected(User user) {
    assertThat(user.getUserRoles().isAdmin()).isFalse();
    Domain suspendedDomain =
        persistResource(domain.asBuilder().addStatusValues(serverSuspensionStatuses).build());
    createAction(
            "NewRegistrar",
            "UNSUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "cured")),
            user)
        .run();

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Domain example.tld is not owned by registrar NewRegistrar",\
            "responseCode":2201}}\
            """);
    Domain reloadedDomain = loadByEntity(suspendedDomain);
    assertThat(reloadedDomain.getStatusValues())
        .containsAtLeastElementsIn(serverSuspensionStatuses);
    assertThat(reloadedDomain).isEqualTo(suspendedDomain);
    assertThat(loadSingleton(PollMessage.OneTime.class)).isEmpty();
    assertThat(loadSingleton(ConsoleUpdateHistory.class)).isEmpty();
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

  private void assertSuperuserUpdateRecorded(Domain updatedDomain, String expectedPollMessage) {
    assertThat(updatedDomain.getLastEppUpdateRegistrarId()).isEqualTo(REGISTRY_ADMIN_CLIENT_ID);
    DomainHistory domainHistory =
        getOnlyHistoryEntryOfType(
            updatedDomain, HistoryEntry.Type.DOMAIN_UPDATE, DomainHistory.class);
    assertThat(domainHistory.getRegistrarId()).isEqualTo(REGISTRY_ADMIN_CLIENT_ID);
    PollMessage.OneTime pollMessage = loadSingleton(PollMessage.OneTime.class).get();
    assertThat(pollMessage.getRegistrarId()).isEqualTo("TheRegistrar");
    assertThat(pollMessage.getMsg()).isEqualTo(expectedPollMessage);
  }

  private ConsoleBulkDomainAction createAction(String action, JsonElement payload) {
    return createAction("TheRegistrar", action, payload, fteUser, 500);
  }

  private ConsoleBulkDomainAction createAction(
      String registrarId, String action, JsonElement payload) {
    return createAction(registrarId, action, payload, fteUser, 500);
  }

  private ConsoleBulkDomainAction createAction(
      String registrarId, String action, JsonElement payload, User user) {
    return createAction(registrarId, action, payload, user, 500);
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
        params,
        eppController,
        limit,
        REGISTRY_ADMIN_CLIENT_ID,
        registrarId,
        action,
        Optional.ofNullable(payload));
  }
}
