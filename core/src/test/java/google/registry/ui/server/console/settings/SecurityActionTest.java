// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.Gson;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.console.RegistrarUpdateHistory;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.testing.ConsoleApiParamsUtils;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleApiParams;
import google.registry.ui.server.console.ConsoleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.settings.SecurityAction}. */
class SecurityActionTest {

  private static String jsonRegistrar1 =
      String.format(
          "{\"registrarId\": \"registrarId\", \"clientCertificate\": \"%s\","
              + " \"ipAddressAllowList\": [\"192.168.1.1/32\"]}",
          SAMPLE_CERT2);
  private static final Gson GSON = RequestModule.provideGson();
  private ConsoleApiParams consoleApiParams;
  private final FakeClock clock = new FakeClock();
  private Registrar testRegistrar;

  private AuthenticatedRegistrarAccessor registrarAccessor =
      AuthenticatedRegistrarAccessor.createForTesting(
          ImmutableSetMultimap.of("registrarId", AuthenticatedRegistrarAccessor.Role.ADMIN));

  private CertificateChecker certificateChecker =
      new CertificateChecker(
          ImmutableSortedMap.of(START_OF_TIME, 20825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
          30,
          15,
          2048,
          ImmutableSet.of("secp256r1", "secp384r1"),
          clock);

  @RegisterExtension
  final JpaTestExtensions.JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
  }

  @Test
  void testSuccess_postRegistrarInfo() throws IOException {
    clock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    SecurityAction action =
        createAction(
            AuthResult.createUser(DatabaseHelper.createAdminUser("email@email.com")),
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    Registrar r = loadRegistrar(testRegistrar.getRegistrarId());
    assertThat(r.getClientCertificateHash().get())
        .isEqualTo("GNd6ZP8/n91t9UTnpxR8aH7aAW4+CpvufYx9ViGbcMY");
    assertThat(r.getIpAddressAllowList().get(0).getIp()).isEqualTo("192.168.1.1");
    assertThat(r.getIpAddressAllowList().get(0).getNetmask()).isEqualTo(32);
    assertAboutImmutableObjects()
        .that(loadSingleton(RegistrarUpdateHistory.class).get().getRegistrar())
        .hasFieldsEqualTo(r);
  }

  private SecurityAction createAction(AuthResult authResult, String registrarId)
      throws IOException {
    consoleApiParams = ConsoleApiParamsUtils.createFake(authResult);
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(jsonRegistrar1)))
        .when(consoleApiParams.request())
        .getReader();
    Optional<Registrar> maybeRegistrar =
        ConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
    return new SecurityAction(
        consoleApiParams, certificateChecker, registrarAccessor, registrarId, maybeRegistrar);
  }
}
