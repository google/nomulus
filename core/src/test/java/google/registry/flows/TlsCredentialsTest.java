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
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.TlsCredentials.BadRegistrarIpAddressException;
import google.registry.flows.TlsCredentials.MissingRegistrarCertificateException;
import google.registry.flows.TlsCredentials.RegistrarCertificateNotConfiguredException;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineExtension;
import google.registry.util.CidrAddressBlock;
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

  @Test
  void testProvideClientCertificateHash() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-SSL-Certificate")).thenReturn("data");
    assertThat(TlsCredentials.EppTlsModule.provideClientCertificateHash(req)).hasValue("data");
  }

  @Test
  void testClientCertificateAndHash_missing() {
    TlsCredentials tls =
        new TlsCredentials(true, Optional.empty(), Optional.empty(), Optional.of("192.168.1.1"));
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, DateTime.now(UTC))
            .build());
    assertThrows(
        MissingRegistrarCertificateException.class,
        () -> tls.validateCertificate(Registrar.loadByClientId("TheRegistrar").get()));
  }

  @Test
  void test_missingIpAddress_doesntAllowAccess() {
    TlsCredentials tls =
        new TlsCredentials(false, Optional.of("certHash"), Optional.empty(), Optional.empty());
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(SAMPLE_CERT, DateTime.now(UTC))
            .setIpAddressAllowList(ImmutableSet.of(CidrAddressBlock.create("3.5.8.13")))
            .build());
    assertThrows(
        BadRegistrarIpAddressException.class,
        () -> tls.validate(Registrar.loadByClientId("TheRegistrar").get(), "password"));
  }

  @Test
  void test_validateCertificate_canBeConfiguredToBypassCertHashes() throws Exception {
    TlsCredentials tls =
        new TlsCredentials(
            false, Optional.of("certHash"), Optional.of("cert"), Optional.of("192.168.1.1"));
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(null, DateTime.now(UTC))
            .setFailoverClientCertificate(null, DateTime.now(UTC))
            .build());
    // This would throw a RegistrarCertificateNotConfiguredException if cert hashes were not
    // bypassed
    tls.validateCertificate(Registrar.loadByClientId("TheRegistrar").get());
  }

  void testProvideClientCertificate() {
    HttpServletRequest req = mock(HttpServletRequest.class);
    when(req.getHeader("X-SSL-Full-Certificate")).thenReturn("data");
    assertThat(TlsCredentials.EppTlsModule.provideClientCertificate(req)).isEqualTo("data");
  }

  @Test
  void testClientCertificate_notConfigured() {
    TlsCredentials tls =
        new TlsCredentials(
            true, Optional.of("hash"), Optional.of(SAMPLE_CERT), Optional.of("192.168.1.1"));
    persistResource(loadRegistrar("TheRegistrar").asBuilder().build());
    assertThrows(
        RegistrarCertificateNotConfiguredException.class,
        () -> tls.validateCertificate(Registrar.loadByClientId("TheRegistrar").get()));
  }

  @Test
  void test_validateCertificate_canBeConfiguredToBypassCerts() throws Exception {
    TlsCredentials tls =
        new TlsCredentials(
            false, Optional.of("certHash"), Optional.of("cert"), Optional.of("192.168.1.1"));
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setClientCertificate(null, DateTime.now(UTC))
            .setFailoverClientCertificate(null, DateTime.now(UTC))
            .build());
    // This would throw a RegistrarCertificateNotConfiguredException if cert hashes wren't bypassed.
    tls.validateCertificate(Registrar.loadByClientId("TheRegistrar").get());
  }
}
