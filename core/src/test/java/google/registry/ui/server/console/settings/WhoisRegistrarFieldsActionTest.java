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

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.gson.Gson;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.RegistrarRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.AuthenticatedRegistrarAccessor.Role;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.util.UtilsModule;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link WhoisRegistrarFieldsAction}. */
public class WhoisRegistrarFieldsActionTest {

  private static final Gson GSON = UtilsModule.provideGson();
  private final FakeClock clock = new FakeClock(DateTime.parse("2023-08-01T00:00:00.000Z"));
  private final FakeResponse fakeResponse = new FakeResponse();
  private final AuthenticatedRegistrarAccessor registrarAccessor =
      AuthenticatedRegistrarAccessor.createForTesting(
          ImmutableSetMultimap.of("TheRegistrar", Role.OWNER, "NewRegistrar", Role.OWNER));

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Test
  void testSuccess_setsAllFields() {
    Registrar oldRegistrar = Registrar.loadRequiredRegistrarCached("TheRegistrar");
    assertThat(oldRegistrar.getWhoisServer()).isEqualTo("whois.nic.fakewhois.example");
    assertThat(oldRegistrar.getUrl()).isEqualTo("http://my.fake.url");
    RegistrarAddress oldAddress = oldRegistrar.getLocalizedAddress();
    Registrar fromUi =
        oldRegistrar
            .asBuilder()
            .setWhoisServer("whois.nic.google")
            .setUrl("https://newurl.example")
            .setLocalizedAddress(oldAddress.asBuilder().setState("NL").setCountryCode("CA").build())
            .build();
    WhoisRegistrarFieldsAction action = createAction(fromUi);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    Registrar newRegistrar = Registrar.loadByRegistrarId("TheRegistrar").get(); // skip cache
    assertThat(newRegistrar).isEqualTo(fromUi);
    // the non-changed fields should be the same
    assertAboutImmutableObjects()
        .that(newRegistrar)
        .isEqualExceptFields(oldRegistrar, "whoisServer", "url", "localizedAddress");
  }

  @Test
  void testFailure_noAccessToRegistrar() {
    AuthResult onlyTheRegistrar =
        AuthResult.create(
            AuthLevel.USER,
            UserAuthInfo.create(
                new User.Builder()
                    .setEmailAddress("email@email.example")
                    .setUserRoles(
                        new UserRoles.Builder()
                            .setRegistrarRoles(
                                ImmutableMap.of("TheRegistrar", RegistrarRole.PRIMARY_CONTACT))
                            .build())
                    .build()));
    Registrar newRegistrar = Registrar.loadByRegistrarIdCached("NewRegistrar").get();
    WhoisRegistrarFieldsAction action = createAction(newRegistrar, onlyTheRegistrar);
    action.run();
    assertThat(fakeResponse.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_FORBIDDEN);
    // should be no change
    assertThat(DatabaseHelper.loadByEntity(newRegistrar)).isEqualTo(newRegistrar);
  }

  private AuthResult defaultUserAuth() {
    return AuthResult.create(
        AuthLevel.USER,
        UserAuthInfo.create(
            new User.Builder()
                .setEmailAddress("email@email.example")
                .setUserRoles(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build())
                .build()));
  }

  private WhoisRegistrarFieldsAction createAction(Registrar registrar) {
    return createAction(registrar, defaultUserAuth());
  }

  private WhoisRegistrarFieldsAction createAction(Registrar registrar, AuthResult authResult) {
    return new WhoisRegistrarFieldsAction(
        authResult, fakeResponse, GSON, registrarAccessor, Optional.of(registrar));
  }
}
