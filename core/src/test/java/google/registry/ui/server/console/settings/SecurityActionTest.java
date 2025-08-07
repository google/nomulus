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

  private static final String VALIDITY_TOO_LONG_CERT_PEM =
      "-----BEGIN CERTIFICATE-----\n"
          + "MIIDejCCAv+gAwIBAgIQHNcSEt4VENkSgtozEEoQLzAKBggqhkjOPQQDAzB8MQsw\n"
          + "CQYDVQQGEwJVUzEOMAwGA1UECAwFVGV4YXMxEDAOBgNVBAcMB0hvdXN0b24xGDAW\n"
          + "BgNVBAoMD1NTTCBDb3Jwb3JhdGlvbjExMC8GA1UEAwwoU1NMLmNvbSBSb290IENl\n"
          + "cnRpZmljYXRpb24gQXV0aG9yaXR5IEVDQzAeFw0xOTAzMDcxOTQyNDJaFw0zNDAz\n"
          + "MDMxOTQyNDJaMG8xCzAJBgNVBAYTAlVTMQ4wDAYDVQQIDAVUZXhhczEQMA4GA1UE\n"
          + "BwwHSG91c3RvbjERMA8GA1UECgwIU1NMIENvcnAxKzApBgNVBAMMIlNTTC5jb20g\n"
          + "U1NMIEludGVybWVkaWF0ZSBDQSBFQ0MgUjIwdjAQBgcqhkjOPQIBBgUrgQQAIgNi\n"
          + "AASEOWn30uEYKDLFu4sCjFQ1VupFaeMtQjqVWyWSA7+KFljnsVaFQ2hgs4cQk1f/\n"
          + "RQ2INSwdVCYU0i5qsbom20rigUhDh9dM/r6bEZ75eFE899kSCI14xqThYVLPdLEl\n"
          + "+dyjggFRMIIBTTASBgNVHRMBAf8ECDAGAQH/AgEAMB8GA1UdIwQYMBaAFILRhXMw\n"
          + "5zUE044CkvvlpNHEIejNMHgGCCsGAQUFBwEBBGwwajBGBggrBgEFBQcwAoY6aHR0\n"
          + "cDovL3d3dy5zc2wuY29tL3JlcG9zaXRvcnkvU1NMY29tLVJvb3RDQS1FQ0MtMzg0\n"
          + "LVIxLmNydDAgBggrBgEFBQcwAYYUaHR0cDovL29jc3BzLnNzbC5jb20wEQYDVR0g\n"
          + "BAowCDAGBgRVHSAAMB0GA1UdJQQWMBQGCCsGAQUFBwMCBggrBgEFBQcDATA7BgNV\n"
          + "HR8ENDAyMDCgLqAshipodHRwOi8vY3Jscy5zc2wuY29tL3NzbC5jb20tZWNjLVJv\n"
          + "b3RDQS5jcmwwHQYDVR0OBBYEFA10Zgpen+Is7NXCXSUEf3Uyuv99MA4GA1UdDwEB\n"
          + "/wQEAwIBhjAKBggqhkjOPQQDAwNpADBmAjEAxYt6Ylk/N8Fch/3fgKYKwI5A011Q\n"
          + "MKW0h3F9JW/NX/F7oYtWrxljheH8n2BrkDybAjEAlCxkLE0vQTYcFzrR24oogyw6\n"
          + "VkgTm92+jiqJTO5SSA9QUa092S5cTKiHkH2cOM6m\n"
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
  void testFailure_validityPeriodTooLong_returnsSpecificError() throws IOException {
    CertificateChecker strictChecker =
        new CertificateChecker(
            ImmutableSortedMap.of(START_OF_TIME, 398), 
            30,
            15,
            2048,
            ImmutableSet.of("secp256r1", "secp384r1"),
            clock);

    clock.setTo(DateTime.parse("2025-01-01T00:00:00Z"));
    String escapedCert = VALIDITY_TOO_LONG_CERT_PEM.replace("\n", "\\n");
    String jsonWithBadCert =
        String.format(
            "{\"registrarId\": \"registrarId\", \"clientCertificate\": \"%s\"}", escapedCert);

    when(consoleApiParams.request().getMethod()).thenReturn(Action.Method.POST.toString());
    doReturn(new BufferedReader(new StringReader(jsonWithBadCert)))
        .when(consoleApiParams.request())
        .getReader();
    Optional<Registrar> maybeRegistrar =
        ConsoleModule.provideRegistrar(
            GSON, RequestModule.provideJsonBody(consoleApiParams.request(), GSON));
    SecurityAction action =
        new SecurityAction(
            consoleApiParams,
            strictChecker, 
            registrarAccessor,
            testRegistrar.getRegistrarId(),
            maybeRegistrar);
    action.run();

    String expectedError =
        "Certificate validity period is too long; it must be less than or equal to 398 days.";
    FakeResponse response = (FakeResponse) consoleApiParams.response();
    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    assertThat(response.getPayload()).isEqualTo(expectedError);
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
