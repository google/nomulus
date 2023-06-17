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
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.client.http.HttpStatusCodes;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.InetAddresses;
import com.google.gson.Gson;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.console.GlobalRole;
import google.registry.model.console.User;
import google.registry.model.console.UserRoles;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthResult;
import google.registry.request.auth.AuthSettings.AuthLevel;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.request.auth.UserAuthInfo;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.registrar.RegistrarConsoleModule;
import google.registry.util.CidrAddressBlock;
import google.registry.util.UtilsModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link google.registry.ui.server.console.settings.SecurityAction}. */
class SecurityActionTest {

  private static String jsonRegistrar1 =
      String.format(
          "{\"registrarId\": \"registrarId\", \"clientCertificate\": \"%s\","
              + " \"ipAddressAllowList\": [{\"cidrAddressBlock\": \"192.168.1.1/32\" }]}",
          SAMPLE_CERT2);

  private Registrar testRegistrar;
  private final HttpServletRequest request = mock(HttpServletRequest.class);
  private static final Gson GSON = UtilsModule.provideGson();
  private FakeResponse response;
  private final FakeClock clock = new FakeClock();

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
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() {
    response = new FakeResponse();
    testRegistrar = saveRegistrar("registrarId");
  }

  @Test
  void testSuccess_getRegistrarInfo() throws IOException {
    persistResource(
        testRegistrar
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
            .setIpAddressAllowList(
                ImmutableSet.of(
                    CidrAddressBlock.create(InetAddresses.forString("192.168.1.1"), 32),
                    CidrAddressBlock.create(InetAddresses.forString("2001:db8::1"), 128)))
            .build());
    SecurityAction action =
        createAction(
            Action.Method.GET,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    String payload = response.getPayload().replace("\\n", "").replace("\\u003d", "=");
    assertThat(payload).contains(SAMPLE_CERT.replace("\n", ""));
    assertThat(payload).contains("192.168.1.1/32");
    assertThat(payload).contains("2001:db8:0:0:0:0:0:1/128");
  }

  @Test
  void testSuccess_postRegistrarInfo() throws IOException {
    clock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    SecurityAction action =
        createAction(
            Action.Method.POST,
            AuthResult.create(
                AuthLevel.USER,
                UserAuthInfo.create(
                    createUser(new UserRoles.Builder().setGlobalRole(GlobalRole.FTE).build()))),
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(response.getStatus()).isEqualTo(HttpStatusCodes.STATUS_CODE_OK);
    Registrar r = loadRegistrar(testRegistrar.getRegistrarId());
    assertThat(r.getClientCertificateHash().get())
        .isEqualTo("GNd6ZP8/n91t9UTnpxR8aH7aAW4+CpvufYx9ViGbcMY");
    assertThat(r.getIpAddressAllowList().get(0).getIp()).isEqualTo("192.168.1.1");
    assertThat(r.getIpAddressAllowList().get(0).getNetmask()).isEqualTo(32);
  }

  private User createUser(UserRoles userRoles) {
    return new User.Builder()
        .setEmailAddress("email@email.com")
        .setGaiaId("TestUserId")
        .setUserRoles(userRoles)
        .build();
  }

  private SecurityAction createAction(
      Action.Method method, AuthResult authResult, String registrarId) throws IOException {
    when(request.getMethod()).thenReturn(method.toString());
    if (method.equals(Action.Method.GET)) {
      return new SecurityAction(
          request,
          authResult,
          response,
          GSON,
          certificateChecker,
          registrarAccessor,
          registrarId,
          Optional.empty());
    } else {
      doReturn(new BufferedReader(new StringReader("{\"registrar\":" + jsonRegistrar1 + "}")))
          .when(request)
          .getReader();
      Optional<Registrar> maybeRegistrar =
          RegistrarConsoleModule.provideRegistrar(
              GSON, RequestModule.provideJsonBody(request, GSON));
      return new SecurityAction(
          request,
          authResult,
          response,
          GSON,
          certificateChecker,
          registrarAccessor,
          registrarId,
          maybeRegistrar);
    }
  }
}
