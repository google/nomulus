// Copyright 2025 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.mosapi.module;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.privileges.secretmanager.SecretManagerClient;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.OkHttpClient;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MosApiModuleTest {

  private static final String TEST_CERT_SECRET_NAME = "testCert";
  private static final String TEST_KEY_SECRET_NAME = "testKey";

  private SecretManagerClient secretManagerClient;
  private String validCertPem;
  private String validKeyPem;
  private PrivateKey generatedPrivateKey;
  private X509Certificate generatedCertificate;

  @BeforeAll
  static void setupStatics() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    secretManagerClient = mock(SecretManagerClient.class);
    generateTestCredentials();
  }

  @Test
  void testProvideMosapiTlsCert_fetchesFromConfiguredSecretName() {
    when(secretManagerClient.getSecretData(any(), any())).thenReturn(validCertPem);
    String result = MosApiModule.provideMosapiTlsCert(secretManagerClient, TEST_CERT_SECRET_NAME);
    assertThat(result).isEqualTo(validCertPem);
    verify(secretManagerClient).getSecretData(eq(TEST_CERT_SECRET_NAME), eq(Optional.of("latest")));
  }

  @Test
  void testProvideMosapiTlsKey_fetchesFromConfiguredSecretName() {
    when(secretManagerClient.getSecretData(any(), any())).thenReturn(validKeyPem);
    String result = MosApiModule.provideMosapiTlsKey(secretManagerClient, TEST_KEY_SECRET_NAME);
    assertThat(result).isEqualTo(validKeyPem);
    verify(secretManagerClient).getSecretData(eq(TEST_KEY_SECRET_NAME), eq(Optional.of("latest")));
  }

  @Test
  void testProvideCertificate_parsesValidPem() {
    Certificate cert = MosApiModule.provideCertificate(validCertPem);
    assertThat(cert).isInstanceOf(X509Certificate.class);
    // Verify the public key matches to ensure we parsed the correct cert
    assertThat(cert.getPublicKey()).isEqualTo(generatedCertificate.getPublicKey());
  }

  @Test
  void testProvideCertificate_throwsOnInvalidPem() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> MosApiModule.provideCertificate("NOT A REAL CERT"));
    assertThat(thrown).hasMessageThat().contains("Could not create X.509 certificate");
  }

  @Test
  void testProvidePrivateKey_parsesValidPem() {
    PrivateKey key = MosApiModule.providePrivateKey(validKeyPem);
    assertThat(key).isNotNull();
    assertThat(key.getAlgorithm()).isEqualTo("RSA");
    assertThat(key.getEncoded()).isEqualTo(generatedPrivateKey.getEncoded());
  }

  @Test
  void testProvidePrivateKey_throwsOnInvalidPem() {
    RuntimeException thrown =
        assertThrows(
            RuntimeException.class, () -> MosApiModule.providePrivateKey("NOT A REAL KEY"));
    assertThat(thrown).hasMessageThat().contains("Could not parse TLS private key");
  }

  @Test
  void testProvideKeyStore_createsWithCorrectAlias() throws Exception {
    KeyStore keyStore = MosApiModule.provideKeyStore(generatedPrivateKey, generatedCertificate);
    assertThat(keyStore).isNotNull();
    assertThat(keyStore.getType()).isEqualTo("PKCS12");
    assertThat(keyStore.containsAlias("client")).isTrue();
    assertThat(keyStore.getCertificate("client")).isEqualTo(generatedCertificate);
  }

  @Test
  void testProvideMosapiHttpClient_usesConfiguredSslContext() {
    SSLContext mockSslContext = mock(SSLContext.class);
    SSLSocketFactory mockSocketFactory = mock(SSLSocketFactory.class);
    X509TrustManager mockTrustManager = mock(X509TrustManager.class);
    when(mockTrustManager.getAcceptedIssuers()).thenReturn(new X509Certificate[0]);
    when(mockSslContext.getSocketFactory()).thenReturn(mockSocketFactory);
    OkHttpClient client = MosApiModule.provideMosapiHttpClient(mockSslContext, mockTrustManager);
    assertThat(client).isNotNull();
    assertThat(client.sslSocketFactory()).isEqualTo(mockSocketFactory);
  }

  private void generateTestCredentials() throws Exception {
    // 1. Generate KeyPair
    KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
    keyGen.initialize(2048);
    KeyPair keyPair = keyGen.generateKeyPair();
    this.generatedPrivateKey = keyPair.getPrivate();
    DateTimeFormatter formatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneId.of("UTC"));
    Instant now = Instant.now();
    Instant end = now.plus(Duration.ofDays(365));
    // Convert string to Bouncy Castle Time objects
    Time notBefore = new Time(new ASN1GeneralizedTime(formatter.format(now)));
    Time notAfter = new Time(new ASN1GeneralizedTime(formatter.format(end)));
    X509v3CertificateBuilder certBuilder =
        new X509v3CertificateBuilder(
            new X500Name("CN=Test"),
            BigInteger.valueOf(now.toEpochMilli()),
            notBefore,
            notAfter,
            new X500Name("CN=Test"),
            SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
    ContentSigner contentSigner =
        new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());
    this.generatedCertificate =
        new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certBuilder.build(contentSigner));
    // 4. Convert to PEM Strings
    this.validCertPem = toPem(generatedCertificate);
    this.validKeyPem = toPem(generatedPrivateKey);
  }

  private String toPem(Object object) throws Exception {
    StringWriter stringWriter = new StringWriter();
    try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
      pemWriter.writeObject(object);
    }
    return stringWriter.toString();
  }
}
