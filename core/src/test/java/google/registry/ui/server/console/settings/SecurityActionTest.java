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
import static google.registry.testing.CertificateSamples.SAMPLE_CERT2;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.loadSingleton;
import static google.registry.testing.SqlHelper.saveRegistrar;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.console.ConsoleUpdateHistory;
import google.registry.model.registrar.Registrar;
import google.registry.request.Action;
import google.registry.request.RequestModule;
import google.registry.request.auth.AuthenticatedRegistrarAccessor;
import google.registry.testing.FakeResponse;
import google.registry.ui.server.console.ConsoleActionBaseTestCase;
import google.registry.ui.server.console.ConsoleModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link google.registry.ui.server.console.settings.SecurityAction}. */
class SecurityActionTest extends ConsoleActionBaseTestCase {

  private static String jsonRegistrar1 =
      String.format(
          "{\"registrarId\": \"registrarId\", \"clientCertificate\": \"%s\","
              + " \"ipAddressAllowList\": [\"192.168.1.1/32\"]}",
          SAMPLE_CERT2);
  private Registrar testRegistrar;

  private static final String EXPIRED_CERT_PEM =
      "-----BEGIN CERTIFICATE-----\\n"
          + "MIIC/jCCAmQCCQD3249g9Nl9aTANBgkqhkiG9w0BAQsFADCBjjELMAkGA1UEBhMC\\n"
          + "VVMxETAPBgNVBAgMCENhbGlmb3JuaWExFjAUBgNVBAcMDVNhbiBGcmFuY2lzY28x\\n"
          + "GTAXBgNVBAoMEEJhZFNzbCwgSW5jLjogQ0ExGTAXBgNVBAsMEEJhZFNzbCwgSW5j\\n"
          + "LjogQ0ExGTAXBgNVBAMMEGJhZHNzbC5jb206IENBMQswCQYDVQQGEwJVUzAeFw0x\\n"
          + "NTAyMjAxODQ1NTVaFw0xNTAyMjUxODQ1NTVaMIGAMQswCQYDVQQGEwJVUzERMA8G\\n"
          + "A1UECAwIQ2FsaWZvcm5pYTEWMBQGA1UEBwwNU2FuIEZyYW5jaXNjbzEaMBgGA1UE\\n"
          + "CgwRQmFkU3NsLCBJbmMuOiBFQzEYMBYGA1UEAwwPKiouZXhwaXJlZC5iYWRzc2wx\\n"
          + "CzAJBgNVBAYTAlVTMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDP532p2L8V\\n"
          + "/x02y5I65e3FbfiM85v63j2f9Jd9C17C0WKkAmvRFCjQ8q2PcyX2z6vi/pG4ZveG\\n"
          + "L6Rjxf4u3V4h3cM3Wns2e4hFitN2aA4s2sJ3aPSlXgP9PN6A+Lwusnfk11n+r5wS\\n"
          + "gT3y5xJNL9x2YyLgGz8iE2fV9j1R1wIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQAK\\n"
          + "M/Q0GfLzozlwpi5iUSz/dcf4S/2ssUnRzkmvfVl4/NCwM95pB2fr6vM6Sc0vB3pC\\n"
          + "nQ5NtVl4jI5qgC7NaI6bT8G2n48zI9b4ks4Kixb3eU6y3V5L0asIdC2XAPd/n2dg\\n"
          + "x1sA81Yd368p36LhXXF0Q2v33qT8Z3s=\\n"
          + "-----END CERTIFICATE-----";

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

  @BeforeEach
  void beforeEach() {
    testRegistrar = saveRegistrar("registrarId");
  }

  @Test
  void testSuccess_postRegistrarInfo() throws IOException {
    clock.setTo(DateTime.parse("2020-11-01T00:00:00Z"));
    SecurityAction action =
        createAction(
            testRegistrar.getRegistrarId());
    action.run();
    assertThat(((FakeResponse) consoleApiParams.response()).getStatus()).isEqualTo(SC_OK);
    Registrar r = loadRegistrar(testRegistrar.getRegistrarId());
    assertThat(r.getClientCertificateHash().get())
        .isEqualTo("GNd6ZP8/n91t9UTnpxR8aH7aAW4+CpvufYx9ViGbcMY");
    assertThat(r.getIpAddressAllowList().get(0).getIp()).isEqualTo("192.168.1.1");
    assertThat(r.getIpAddressAllowList().get(0).getNetmask()).isEqualTo(32);
    ConsoleUpdateHistory history = loadSingleton(ConsoleUpdateHistory.class).get();
    assertThat(history.getType()).isEqualTo(ConsoleUpdateHistory.Type.REGISTRAR_SECURITY_UPDATE);
    assertThat(history.getDescription()).hasValue("registrarId|IP_CHANGE,PRIMARY_SSL_CERT_CHANGE");
  }

  @Test
  void testFailure_expiredCertificate_returnsSpecificError() throws IOException {
    String jsonWithBadCert =
        String.format(
            "{\"registrarId\": \"registrarId\", \"clientCertificate\": \"%s\"}", EXPIRED_CERT_PEM);
    SecurityAction action = createAction(testRegistrar.getRegistrarId(), jsonWithBadCert);

    action.run();

    FakeResponse response = (FakeResponse) consoleApiParams.response();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo("Certificate is expired.");
  }

  private SecurityAction createAction(String registrarId, String jsonBody) throws IOException {
    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(jsonBody)))
        .when(consoleApiParams.request())
        .getReader();
    Optional<Registrar> maybeRegistrar =
        ConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
    return new SecurityAction(
        consoleApiParams, certificateChecker, registrarAccessor, registrarId, maybeRegistrar);
  }

  private SecurityAction createAction(String registrarId) throws IOException {
    return createAction(registrarId, jsonRegistrar1);
  }
}
