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

package google.registry.ui.server.console.settings;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ImmutableObjectSubject.assertAboutImmutableObjects;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Maps;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.ui.server.console.ConsoleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** Tests for {@link RdapRegistrarFieldsAction}. */
public class RdapRegistrarFieldsActionTest extends ConsoleActionBaseTestCase {

  private final AuthenticatedRegistrarAccessor registrarAccessor =
      AuthenticatedRegistrarAccessor.createForTesting(
          ImmutableSetMultimap.of("TheRegistrar", Role.OWNER, "NewRegistrar", Role.OWNER));

  private final HashMap<String, Object> uiRegistrarMap =
      Maps.newHashMap(
          ImmutableMap.of(
              "registrarId",
              "TheRegistrar",
              "whoisServer",
              "whois.nic.google",
              "type",
              "REAL",
              "emailAddress",
              "the.registrar@example.com",
              "state",
              "ACTIVE",
              "url",
              "\"http://my.fake.url\"",
              "localizedAddress",
              "{\"street\": [\"123 Example Boulevard\"], \"city\": \"Williamsburg\", \"state\":"
                  + " \"NY\", \"zip\": \"11201\", \"countryCode\": \"US\"}"));

  @Test
  void testSuccess_setsAllFields() throws Exception {
    Registrar oldRegistrar = Registrar.loadRequiredRegistrarCached("TheRegistrar");
    ImmutableMap<String, Object> addressMap =
        ImmutableMap.of(
            "street",
            ImmutableList.of("123 Fake St"),
            "city",
            "Fakeville",
            "state",
            "NL",
            "zip",
            "10011",
            "countryCode",
            "CA");
    uiRegistrarMap.putAll(
        ImmutableMap.of(
            "icannReferralEmail",
            "lol@sloth.test",
            "phoneNumber",
            "+1.4155552671",
            "faxNumber",
            "+1.4155552672",
            "localizedAddress",
            "{\"street\": [\"123 Fake St\"], \"city\": \"Fakeville\", \"state\":"
                + " \"NL\", \"zip\": \"10011\", \"countryCode\": \"CA\"}"));
    RdapRegistrarFieldsAction action = createAction();
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    Registrar newRegistrar = Registrar.loadByRegistrarId("TheRegistrar").get(); // skip cache
    assertThat(newRegistrar.getLocalizedAddress().toJsonMap()).isEqualTo(addressMap);
    assertThat(newRegistrar.getPhoneNumber()).isEqualTo("+1.4155552671");
    assertThat(newRegistrar.getFaxNumber()).isEqualTo("+1.4155552672");
    // the non-changed fields should be the same
    assertAboutImmutableObjects()
        .that(newRegistrar)
        .isEqualExceptFields(oldRegistrar, "localizedAddress", "phoneNumber", "faxNumber");
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.REGISTRAR_UPDATE);
    assertThat(history.getDescription()).hasValue("TheRegistrar|ADDRESS,PHONE,FAX");
  }

  @Test
  void testFailure_noAccessToRegistrar() throws Exception {
    Registrar newRegistrar = Registrar.loadByRegistrarIdCached("NewRegistrar").get();
    User onlyTheRegistrar =
        new User.Builder()
            .setEmailAddress("email@email.example")
            .setUserRoles(
                new UserRoles.Builder()
                    .setRegistrarRoles(
                        ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                    .build())
            .build();
    uiRegistrarMap.put("registrarId", "NewRegistrar");
    RdapRegistrarFieldsAction action = createAction(onlyTheRegistrar);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_FORBIDDEN);
    // should be no change
    assertThat(DatabaseHelper.loadByEntity(newRegistrar)).isEqualTo(newRegistrar);
  }

  private RdapRegistrarFieldsAction createAction() throws IOException {
    return createAction(fteUser);
  }

  private RdapRegistrarFieldsAction createAction(User user) throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(AuthResult.createUser(user));
    response = (FakeResponse) consoleApiParams.response();
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(uiRegistrarMap.toString())))
        .when(consoleApiParams.request())
        .getReader();
    return new RdapRegistrarFieldsAction(
        consoleApiParams,
        registrarAccessor,
        ConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON)));
  }
}
