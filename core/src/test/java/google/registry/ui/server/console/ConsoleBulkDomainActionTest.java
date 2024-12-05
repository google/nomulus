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
import static google.registry.model.common.FeatureFlag.FeatureName.MINIMUM_DATASET_CONTACTS_OPTIONAL;
import static google.registry.model.common.FeatureFlag.FeatureStatus.INACTIVE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import google.registry.flows.DaggerEppTestComponent;
import google.registry.flows.EppController;
import google.registry.flows.EppTestComponent;
import google.registry.model.common.FeatureFlag;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.tools.GsonUtils;
import java.util.Map;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link ConsoleBulkDomainAction}. */
public class ConsoleBulkDomainActionTest {

  private static final Gson GSON = GsonUtils.provideGson();

  private final FakeClock clock = new FakeClock(DateTime.parse("2024-05-13T00:00:00.000Z"));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private EppController eppController;
  private FakeResponse fakeResponse;
  private Domain domain;

  @BeforeEach
  void beforeEach() {
    persistResource(
        new FeatureFlag()
            .asBuilder()
            .setFeatureName(MINIMUM_DATASET_CONTACTS_OPTIONAL)
            .setStatusMap(ImmutableSortedMap.of(START_OF_TIME, INACTIVE))
            .build());
    eppController =
        DaggerEppTestComponent.builder()
            .fakesAndMocksModule(EppTestComponent.FakesAndMocksModule.create(clock))
            .build()
            .startRequest()
            .eppController();
    createTld("tld");
    domain =
        persistDomainWithDependentResources(
            "example",
            "tld",
            persistActiveContact("contact1234"),
            clock.nowUtc(),
            clock.nowUtc().minusMonths(1),
            clock.nowUtc().plusMonths(11));
  }

  @Test
  void testSuccess_delete() {
    ConsoleBulkDomainAction action =
        createAction(
            "DELETE",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_OK);
    assertThat(fakeResponse.getPayload())
        .isEqualTo(
            """
{"example.tld":{"message":"Command completed successfully; action pending",\
"responseCode":1001}}""");
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(clock.nowUtc().plusDays(35));
  }

  @Test
  void testSuccess_suspend() throws Exception {
    User adminUser =
        persistResource(
            new User.Builder()
                .setEmailAddress("email@email.com")
                .setUserRoles(
                    new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).setIsAdmin(true).build())
                .build());
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")),
            adminUser);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_OK);
    assertThat(fakeResponse.getPayload())
        .isEqualTo(
            """
            {"example.tld":{"message":"Command completed successfully","responseCode":1000}}""");
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeast(
            StatusValue.SERVER_RENEW_PROHIBITED,
            StatusValue.SERVER_TRANSFER_PROHIBITED,
            StatusValue.SERVER_UPDATE_PROHIBITED,
            StatusValue.SERVER_DELETE_PROHIBITED,
            StatusValue.SERVER_HOLD);
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
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_OK);
    assertThat(fakeResponse.getPayload())
        .isEqualTo(
            """
{"example.tld":{"message":"Command completed successfully; action pending","responseCode":1001},\
"nonexistent.tld":{"message":"The domain with given ID (nonexistent.tld) doesn\\u0027t exist.",\
"responseCode":2303}}""");
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(clock.nowUtc().plusDays(35));
  }

  @Test
  void testFailure_badActionString() {
    ConsoleBulkDomainAction action = createAction("bad", null);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(fakeResponse.getPayload())
        .isEqualTo(
            "No enum constant"
                + " google.registry.ui.server.console.ConsoleBulkDomainAction.BulkAction.bad");
  }

  @Test
  void testFailure_emptyBody() {
    ConsoleBulkDomainAction action = createAction("DELETE", null);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(fakeResponse.getPayload()).isEqualTo("Bulk action payload must be present");
  }

  @Test
  void testFailure_noPermission() {
    JsonElement payload =
        GSON.toJsonTree(ImmutableMap.of("domainList", ImmutableList.of("domain.tld")));
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
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_FORBIDDEN);
  }

  @Test
  void testFailure_suspend_nonAdmin() {
    ConsoleBulkDomainAction action =
        createAction(
            "SUSPEND",
            GSON.toJsonTree(
                ImmutableMap.of("domainList", ImmutableList.of("example.tld"), "reason", "test")));
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(SC_OK);
    Map<String, ConsoleBulkDomainAction.ConsoleEppOutput> payload =
        GSON.fromJson(fakeResponse.getPayload(), new TypeToken<>() {});
    assertThat(payload).containsKey("example.tld");
    assertThat(payload.get("example.tld").responseCode()).isEqualTo(2004);
    assertThat(payload.get("example.tld").message()).contains("cannot be set by clients");
    assertThat(loadByEntity(domain)).isEqualTo(domain);
  }

  private ConsoleBulkDomainAction createAction(String action, JsonElement payload) {
    User user =
        persistResource(
            new User.Builder()
                .setEmailAddress("email@email.com")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
                .build());
    return createAction(action, payload, user);
  }

  private ConsoleBulkDomainAction createAction(String action, JsonElement payload, User user) {
    AuthResult authResult = AuthResult.createUser(user);
    ConsoleApiParams params = ConsoleApiParamsUtils.createFake(authResult);
    when(params.request().getMethod()).thenReturn("POST");
    fakeResponse = (FakeResponse) params.response();
    return new ConsoleBulkDomainAction(
        params, eppController, "TheRegistrar", action, Optional.ofNullable(payload));
  }
}
