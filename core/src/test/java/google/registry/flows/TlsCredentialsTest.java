// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.flows;

import static com.google.common.truth.Truth8.assertThat;
import static google.registry.testing.CertificateSamples.SAMPLE_CERT;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.TlsCredentials.BadRegistrarIpAddressException;
import google.registry.flows.TlsCredentials.MissingRegistrarCertificateException;
import google.registry.flows.TlsCredentials.RegistrarCertificateNotConfiguredException;
import google.registry.flows.certs.CertificateChecker;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.util.CidrAddressBlock;
import google.registry.util.ProxyHttpHeaders;
import java.util.Optional;
import javax.servlet.http.HttpServletRequest;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TlsCredentials}. */
final class TlsCredentialsTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  protected final FakeClock clock = new FakeClock();

  private final CertificateChecker certificateChecker =
      new CertificateChecker(
          ImmutableSortedMap.of(START_OF_TIME, 825, DateTime.parse("2020-09-01T00:00:00Z"), 398),
          30,
          2048,
          ImmutableSet.of("secp256r1", "secp384r1"),
          clock);

  @Test
  void testProvideClientCertificateHash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader(ProxyHttpHeaders.CERTIFICATE_HASH)).thenReturn("data");
    assertThat(TlsCredentials.EppTlsModule.provideClientCertificateHash(req)).hasValue("data");
  }

  @Test
  void testClientCertificateAndHash_missing() {
    TlsCredentials tls =
        new TlsCredentials(true, Optional.empty(), Optional.of("192.168.1.1"), certificateChecker);
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
            .build());
    assertThrows(
        MissingRegistrarCertificateException.class,
        () -> tls.validateCertificateHash(Registrar.loadByClientId("TheRegistrar").get()));
  }

  @Test
  void test_missingIpAddress_doesntAllowAccess() {
    TlsCredentials tls =
        new TlsCredentials(false, Optional.of("certHash"), Optional.empty(), certificateChecker);
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, clock.nowUtc())
            .setIpAddressAllowList(ImmutableSet.of(CidrAddressBlock.create("3.5.8.13")))
            .build());
    assertThrows(
        BadRegistrarIpAddressException.class,
        () -> tls.validate(Registrar.loadByClientId("TheRegistrar").get(), "password"));
  }

  @Test
  void testClientCertificate_notConfigured() {
    TlsCredentials tls =
        new TlsCredentials(
            true, Optional.of("hash"), Optional.of("192.168.1.1"), certificateChecker);
    persistResource(loadRegistrar("TheRegistrar").asBuilder().build());
    assertThrows(
        RegistrarCertificateNotConfiguredException.class,
        () -> tls.validateCertificateHash(Registrar.loadByClientId("TheRegistrar").get()));
  }

  @Test
  void test_validateCertificateHash_canBeConfiguredToBypassCerts() throws Exception {
    TlsCredentials tls =
        new TlsCredentials(
            false, Optional.of("certHash"), Optional.of("192.168.1.1"), certificateChecker);
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(null, clock.nowUtc())
            .setFailoverClientCertificate(null, clock.nowUtc())
            .build());
    // This would throw a RegistrarCertificateNotConfiguredException if cert hashes wren't bypassed.
    tls.validateCertificateHash(Registrar.loadByClientId("TheRegistrar").get());
  }
}
