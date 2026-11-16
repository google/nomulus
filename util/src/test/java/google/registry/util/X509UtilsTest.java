// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.util;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.util.X509Utils.MAX_CERTIFICATE_CHAIN_LENGTH;
import static google.registry.util.X509Utils.MAX_HOSTNAMES_HEADER_BYTES;
import static google.registry.util.X509Utils.extractHostnames;
import static google.registry.util.X509Utils.formatHostnamesHeader;
import static google.registry.util.X509Utils.getDefaultTrustManager;
import static google.registry.util.X509Utils.validateCertificateChain;
import static java.time.ZoneOffset.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.testing.TestLogHandler;
import google.registry.util.X509Utils.CertificateChainValidationResult;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.DERGeneralizedTime;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERUTCTime;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.GeneralSubtree;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.NameConstraints;
import org.bouncycastle.asn1.x509.Time;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link X509Utils}. */
class X509UtilsTest {

  private static final Instant VALID_FROM = Instant.EPOCH;
  private static final Instant VALID_TO = Instant.parse("2100-01-01T00:00:00Z");
  private static final Instant EXPIRED_FROM = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant EXPIRED_TO = Instant.parse("2000-01-02T00:00:00Z");
  private static final Instant FUTURE_FROM = Instant.parse("2099-01-01T00:00:00Z");
  private static final Instant FUTURE_TO = Instant.parse("2099-01-02T00:00:00Z");

  private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
  private static final AtomicLong SERIAL_NUMBER = new AtomicLong(1);
  private static final DateTimeFormatter UTC_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(UTC);
  private static final DateTimeFormatter GENERALIZED_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(UTC);

  private static SelfSignedCaCertificate rootCa;
  private static SelfSignedCaCertificate untrustedCa;
  private static X509TrustManager rootTrustManager;
  private static KeyPair leafKeyPair;
  private static KeyPair intermediateKeyPair;
  private static X509Certificate intermediateCa;

  @FunctionalInterface
  private interface CertCustomizer {
    void customize(X509v3CertificateBuilder builder) throws Exception;
  }

  @BeforeAll
  static void setUpSharedFixtures() throws Exception {
    KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA", PROVIDER);
    rsaGen.initialize(2048, new SecureRandom());
    leafKeyPair = rsaGen.generateKeyPair();
    intermediateKeyPair = rsaGen.generateKeyPair();

    rootCa = SelfSignedCaCertificate.create("root-ca.tld", VALID_FROM, VALID_TO);
    untrustedCa = SelfSignedCaCertificate.create("untrusted-ca.tld", VALID_FROM, VALID_TO);
    rootTrustManager = createTrustManager(rootCa.cert());
    intermediateCa =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            intermediateKeyPair.getPublic(),
            new X500Name("CN=intermediate-ca.tld"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true)));
  }

  // Construct ASN.1 Time directly because :runPresubmits bans legacy Date in new files.
  private static Time toAsn1Time(Instant instant) {
    return instant.atOffset(UTC).getYear() >= 2050
        ? new Time(new DERGeneralizedTime(GENERALIZED_TIME_FORMATTER.format(instant)))
        : new Time(new DERUTCTime(UTC_TIME_FORMATTER.format(instant)));
  }

  private static X509TrustManager createTrustManager(X509Certificate... trustedCerts)
      throws Exception {
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    for (int i = 0; i < trustedCerts.length; i++) {
      keyStore.setCertificateEntry("ca-" + i, trustedCerts[i]);
    }
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
    trustManagerFactory.init(keyStore);
    return (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
  }

  private static void assertChainValid(Certificate[] certs, X509TrustManager trustManager) {
    CertificateChainValidationResult result = validateCertificateChain(certs, trustManager);
    assertThat(result.valid()).isTrue();
    assertThat(result.failureReason()).isEmpty();
  }

  private static CertificateChainValidationResult assertChainInvalid(
      @Nullable Certificate[] certs, @Nullable X509TrustManager trustManager) {
    CertificateChainValidationResult result = validateCertificateChain(certs, trustManager);
    assertThat(result.valid()).isFalse();
    assertThat(result.failureReason()).isPresent();
    assertThat(result.failureReason().get()).isNotEmpty();
    return result;
  }

  private static X509Certificate signCert(
      PrivateKey issuerKey,
      X509Certificate issuerCert,
      PublicKey subjectKey,
      X500Name subjectDn,
      Instant from,
      Instant to,
      @Nullable CertCustomizer customizer)
      throws Exception {
    String sigAlg =
        "EC".equals(issuerKey.getAlgorithm()) ? "SHA256WithECDSA" : "SHA256WithRSAEncryption";
    ContentSigner sigGen = new JcaContentSignerBuilder(sigAlg).build(issuerKey);
    X509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            X500Name.getInstance(issuerCert.getSubjectX500Principal().getEncoded()),
            BigInteger.valueOf(SERIAL_NUMBER.incrementAndGet()),
            toAsn1Time(from),
            toAsn1Time(to),
            subjectDn,
            subjectKey);
    if (customizer != null) {
      customizer.customize(builder);
    }
    return new JcaX509CertificateConverter()
        .setProvider(PROVIDER)
        .getCertificate(builder.build(sigGen));
  }

  private static X509Certificate signLeaf(SelfSignedCaCertificate ssc, String cn, String... dnsSans)
      throws Exception {
    return signCert(
        ssc.key(),
        ssc.cert(),
        leafKeyPair.getPublic(),
        new X500Name("CN=" + cn),
        VALID_FROM,
        VALID_TO,
        dnsSans.length == 0 ? null : builder -> addDnsSans(builder, dnsSans));
  }

  private static X509Certificate signLeafWithGeneralNames(
      X500Name subjectDn, GeneralName... subjectAltNames) throws Exception {
    return signCert(
        rootCa.key(),
        rootCa.cert(),
        leafKeyPair.getPublic(),
        subjectDn,
        VALID_FROM,
        VALID_TO,
        subjectAltNames.length == 0
            ? null
            : builder ->
                builder.addExtension(
                    Extension.subjectAlternativeName, false, new GeneralNames(subjectAltNames)));
  }

  private static void addDnsSans(X509v3CertificateBuilder builder, String... dnsSans)
      throws Exception {
    GeneralName[] generalNames =
        Arrays.stream(dnsSans)
            .map(san -> new GeneralName(GeneralName.dNSName, san))
            .toArray(GeneralName[]::new);
    builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(generalNames));
  }

  private static X509Certificate toJdkCertificate(X509Certificate cert) throws Exception {
    return (X509Certificate)
        CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(cert.getEncoded()));
  }

  @Test
  void testCertificateChainValidationResult_validation() {
    assertThat(CertificateChainValidationResult.ok().valid()).isTrue();
    assertThat(CertificateChainValidationResult.ok().failureReason()).isEmpty();
    assertThrows(NullPointerException.class, () -> new CertificateChainValidationResult(null));
    assertThrows(NullPointerException.class, () -> CertificateChainValidationResult.failed(null));
    assertThrows(IllegalArgumentException.class, () -> CertificateChainValidationResult.failed(""));
    assertThrows(
        IllegalArgumentException.class, () -> CertificateChainValidationResult.failed("   "));
  }

  @Test
  void testGetDefaultTrustManager_returnsNonEmptyMemoizedInstance() {
    X509TrustManager trustManager = getDefaultTrustManager();
    assertThat(trustManager).isNotNull();
    assertThat(trustManager.getAcceptedIssuers()).isNotEmpty();
    assertThat(getDefaultTrustManager()).isSameInstanceAs(trustManager);
  }

  @Test
  void testValidateCertificateChain_directAndMultiTierChains_succeed() throws Exception {
    X509Certificate directLeaf = signLeaf(rootCa, "epp.direct.example");
    assertChainValid(new Certificate[] {directLeaf}, rootTrustManager);
    assertChainValid(new Certificate[] {directLeaf, rootCa.cert()}, rootTrustManager);
    assertChainValid(new Certificate[] {directLeaf, null}, rootTrustManager);

    // 3-tier chain (including out-of-order and extra unrelated CA).
    X509Certificate threeTierLeaf =
        signCert(
            intermediateKeyPair.getPrivate(),
            intermediateCa,
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.three-tier.example"),
            VALID_FROM,
            VALID_TO,
            null);
    assertChainValid(new Certificate[] {threeTierLeaf, intermediateCa}, rootTrustManager);
    assertChainValid(
        new Certificate[] {threeTierLeaf, rootCa.cert(), intermediateCa}, rootTrustManager);
    assertChainValid(
        new Certificate[] {threeTierLeaf, intermediateCa, untrustedCa.cert()}, rootTrustManager);

    // Explicit critical BasicConstraints(cA=false) leaf is accepted (getBasicConstraints() == -1).
    X509Certificate explicitNonCaLeaf =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.nonca.example"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(
                    Extension.basicConstraints, true, new BasicConstraints(false)));
    assertChainValid(new Certificate[] {explicitNonCaLeaf}, rootTrustManager);

    // Exact MAX_CERTIFICATE_CHAIN_LENGTH (10) succeeds.
    Certificate[] exactMaxChain = new Certificate[MAX_CERTIFICATE_CHAIN_LENGTH];
    exactMaxChain[0] = directLeaf;
    Arrays.fill(exactMaxChain, 1, MAX_CERTIFICATE_CHAIN_LENGTH, rootCa.cert());
    assertChainValid(exactMaxChain, rootTrustManager);
  }

  @Test
  void testValidateCertificateChain_ecdsaAndEd25519Chains_succeed() throws Exception {
    KeyPairGenerator ecGen = KeyPairGenerator.getInstance("EC", PROVIDER);
    ecGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
    SelfSignedCaCertificate ecRootCa =
        SelfSignedCaCertificate.create(
            ecGen.generateKeyPair(), "ec-root-ca.tld", VALID_FROM, VALID_TO);
    KeyPair ecInterKeyPair = ecGen.generateKeyPair();
    X509Certificate ecIntermediateCa =
        signCert(
            ecRootCa.key(),
            ecRootCa.cert(),
            ecInterKeyPair.getPublic(),
            new X500Name("CN=ec-intermediate-ca.tld"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true)));
    X509Certificate ecLeaf =
        signCert(
            ecInterKeyPair.getPrivate(),
            ecIntermediateCa,
            ecGen.generateKeyPair().getPublic(),
            new X500Name("CN=epp.ecdsa.example"),
            VALID_FROM,
            VALID_TO,
            builder -> addDnsSans(builder, "epp.ecdsa.example"));
    assertChainValid(
        new Certificate[] {ecLeaf, ecIntermediateCa}, createTrustManager(ecRootCa.cert()));

    KeyPair ed25519KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    X509Certificate ed25519Leaf =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            ed25519KeyPair.getPublic(),
            new X500Name("CN=epp.ed25519.example"),
            VALID_FROM,
            VALID_TO,
            builder -> {
              builder.addExtension(
                  Extension.extendedKeyUsage,
                  false,
                  new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
              builder.addExtension(
                  Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            });
    assertChainValid(new Certificate[] {ed25519Leaf}, rootTrustManager);
  }

  @Test
  void testValidateCertificateChain_supportedExtendedKeyUsages_succeed() throws Exception {
    for (ExtendedKeyUsage eku :
        ImmutableList.of(
            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            new ExtendedKeyUsage(
                new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}),
            new ExtendedKeyUsage(KeyPurposeId.anyExtendedKeyUsage))) {
      X509Certificate leaf =
          signCert(
              rootCa.key(),
              rootCa.cert(),
              leafKeyPair.getPublic(),
              new X500Name("CN=epp.eku.example"),
              VALID_FROM,
              VALID_TO,
              builder -> {
                builder.addExtension(Extension.extendedKeyUsage, false, eku);
                builder.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
              });
      assertChainValid(new Certificate[] {leaf}, rootTrustManager);
    }
  }

  @Test
  void testValidateCertificateChain_alwaysUsesCheckServerTrustedOnly() throws Exception {
    X509Certificate leaf = signLeaf(rootCa, "epp.example");
    X509TrustManager acceptingTm = mock(X509TrustManager.class);
    doNothing().when(acceptingTm).checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
    assertChainValid(new Certificate[] {leaf}, acceptingTm);
    verify(acceptingTm).checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
    verify(acceptingTm, never()).checkClientTrusted(any(X509Certificate[].class), eq("UNKNOWN"));

    X509TrustManager rejectingTm = mock(X509TrustManager.class);
    doThrow(new CertificateException("distrusted CA"))
        .when(rejectingTm)
        .checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
    assertThat(assertChainInvalid(new Certificate[] {leaf}, rejectingTm).failureReason().get())
        .contains("distrusted CA");
    verify(rejectingTm).checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
    verify(rejectingTm, never()).checkClientTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
  }

  @Test
  void testValidateCertificateChain_missingNonX509OrOversizedChains_rejected() throws Exception {
    X509Certificate directLeaf = signLeaf(rootCa, "epp.direct.example");
    assertThat(assertChainInvalid(null, rootTrustManager).failureReason())
        .hasValue("No peer certificates presented");
    assertThat(assertChainInvalid(new Certificate[0], rootTrustManager).failureReason())
        .hasValue("No peer certificates presented");
    assertThat(assertChainInvalid(new Certificate[] {null}, rootTrustManager).failureReason())
        .hasValue("Leaf certificate is not an X509Certificate");
    assertThat(
            assertChainInvalid(new Certificate[] {mock(Certificate.class)}, rootTrustManager)
                .failureReason())
        .hasValue("Leaf certificate is not an X509Certificate");
    assertThrows(
        NullPointerException.class,
        () -> validateCertificateChain(new Certificate[] {directLeaf}, null));
    assertThrows(NullPointerException.class, () -> validateCertificateChain(null, null));

    // Chain exceeding MAX_CERTIFICATE_CHAIN_LENGTH is rejected before calling trustManager.
    Certificate[] oversizedChain = new Certificate[MAX_CERTIFICATE_CHAIN_LENGTH + 1];
    oversizedChain[0] = directLeaf;
    Arrays.fill(oversizedChain, 1, oversizedChain.length, rootCa.cert());
    X509TrustManager mockTm = mock(X509TrustManager.class);
    assertThat(assertChainInvalid(oversizedChain, mockTm).failureReason())
        .hasValue(
            String.format(
                "Certificate chain length %d exceeds maximum of %d",
                MAX_CERTIFICATE_CHAIN_LENGTH + 1, MAX_CERTIFICATE_CHAIN_LENGTH));
    verify(mockTm, never()).checkServerTrusted(any(), any());
    verify(mockTm, never()).checkClientTrusted(any(), any());
  }

  @Test
  void testValidateCertificateChain_caAsLeafAndUntrustedChains_rejected() throws Exception {
    assertThat(
            assertChainInvalid(new Certificate[] {rootCa.cert()}, rootTrustManager).failureReason())
        .hasValue("Leaf certificate must not be a CA");
    assertThat(
            assertChainInvalid(new Certificate[] {intermediateCa}, rootTrustManager)
                .failureReason())
        .hasValue("Leaf certificate must not be a CA");
    // pathLenConstraint=0 returns getBasicConstraints() == 0 (not > 0); must still be rejected.
    X509Certificate pathLen0Ca =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=pathlen0-ca.tld"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0)));
    assertThat(assertChainInvalid(new Certificate[] {pathLen0Ca}, rootTrustManager).failureReason())
        .hasValue("Leaf certificate must not be a CA");

    // Untrusted CA.
    X509Certificate untrustedLeaf = signLeaf(untrustedCa, "epp.untrusted.example");
    assertThat(
            assertChainInvalid(new Certificate[] {untrustedLeaf}, rootTrustManager)
                .failureReason()
                .get())
        .contains("PKIX path building failed");

    // Missing intermediate CA.
    X509Certificate threeTierLeaf =
        signCert(
            intermediateKeyPair.getPrivate(),
            intermediateCa,
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.three-tier.example"),
            VALID_FROM,
            VALID_TO,
            null);
    assertChainInvalid(new Certificate[] {threeTierLeaf}, rootTrustManager);

    // Non-CA leaf acting as an intermediate cannot mint valid child certificates.
    X509Certificate nonCaIntermediate =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            intermediateKeyPair.getPublic(),
            new X500Name("CN=epp.compromised-leaf.example"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(
                    Extension.basicConstraints, true, new BasicConstraints(false)));
    X509Certificate childUnderNonCa =
        signCert(
            intermediateKeyPair.getPrivate(),
            nonCaIntermediate,
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.forged-child.example"),
            VALID_FROM,
            VALID_TO,
            null);
    assertChainInvalid(new Certificate[] {childUnderNonCa, nonCaIntermediate}, rootTrustManager);

    // Forged signature (claims rootCa issuer DN, signed with untrustedCa's key).
    X509Certificate forgedSigLeaf =
        signCert(
            untrustedCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.forged.example"),
            VALID_FROM,
            VALID_TO,
            null);
    assertChainInvalid(new Certificate[] {forgedSigLeaf}, rootTrustManager);
  }

  @Test
  void testValidateCertificateChain_expiredAndNotYetValid_rejected() throws Exception {
    X509Certificate expiredLeaf =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.expired.example"),
            EXPIRED_FROM,
            EXPIRED_TO,
            null);
    assertThat(
            assertChainInvalid(new Certificate[] {expiredLeaf}, rootTrustManager)
                .failureReason()
                .get())
        .contains("CertificateExpiredException:");

    X509Certificate notYetValidLeaf =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.future.example"),
            FUTURE_FROM,
            FUTURE_TO,
            null);
    assertThat(
            assertChainInvalid(new Certificate[] {notYetValidLeaf}, rootTrustManager)
                .failureReason()
                .get())
        .contains("CertificateNotYetValidException:");

    // Expired/future leaf pinned directly as a trust anchor (0-length PKIX path) must still fail
    // via our explicit leafCert.checkValidity() check.
    assertThat(
            assertChainInvalid(new Certificate[] {expiredLeaf}, createTrustManager(expiredLeaf))
                .failureReason()
                .get())
        .contains("CertificateExpiredException:");
    assertThat(
            assertChainInvalid(
                    new Certificate[] {notYetValidLeaf}, createTrustManager(notYetValidLeaf))
                .failureReason()
                .get())
        .contains("CertificateNotYetValidException:");

    // Expired intermediate CA with a valid leaf.
    X509Certificate expiredIntermediate =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            intermediateKeyPair.getPublic(),
            new X500Name("CN=expired-intermediate.tld"),
            EXPIRED_FROM,
            EXPIRED_TO,
            builder ->
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true)));
    X509Certificate leafUnderExpiredInter =
        signCert(
            intermediateKeyPair.getPrivate(),
            expiredIntermediate,
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.expired-inter.example"),
            VALID_FROM,
            VALID_TO,
            null);
    assertChainInvalid(
        new Certificate[] {leafUnderExpiredInter, expiredIntermediate}, rootTrustManager);
  }

  @Test
  void testValidateCertificateChain_disallowedEkuAndKeyUsage_rejected() throws Exception {
    for (KeyPurposeId disallowedPurpose :
        ImmutableList.of(KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_codeSigning)) {
      X509Certificate leaf =
          signCert(
              rootCa.key(),
              rootCa.cert(),
              leafKeyPair.getPublic(),
              new X500Name("CN=epp.disallowed-eku.example"),
              VALID_FROM,
              VALID_TO,
              builder ->
                  builder.addExtension(
                      Extension.extendedKeyUsage, false, new ExtendedKeyUsage(disallowedPurpose)));
      CertificateChainValidationResult result =
          assertChainInvalid(new Certificate[] {leaf}, rootTrustManager);
      assertThat(result.failureReason().get()).contains("Extended key usage");
    }

    // Malformed EKU extension fails closed.
    X509Certificate malformedEkuLeaf =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.malformed-eku.example"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(Extension.extendedKeyUsage, false, new byte[] {1, 2, 3}));
    assertChainInvalid(new Certificate[] {malformedEkuLeaf}, rootTrustManager);

    // Leaf missing digitalSignature KeyUsage (including keyEncipherment-only under "UNKNOWN") is
    // rejected.
    for (int disallowedKu : ImmutableList.of(KeyUsage.cRLSign, KeyUsage.keyEncipherment)) {
      X509Certificate badKuLeaf =
          signCert(
              rootCa.key(),
              rootCa.cert(),
              leafKeyPair.getPublic(),
              new X500Name("CN=epp.badku.example"),
              VALID_FROM,
              VALID_TO,
              builder -> {
                builder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
                builder.addExtension(Extension.keyUsage, true, new KeyUsage(disallowedKu));
              });
      assertChainInvalid(new Certificate[] {badKuLeaf}, rootTrustManager);
    }
  }

  @Test
  void testValidateCertificateChain_runtimeExceptions_failClosedWithoutThrowing() throws Exception {
    // LazyX509Certificate-style RuntimeException on leaf inspection fails closed.
    X509Certificate brokenLeaf = mock(X509Certificate.class);
    when(brokenLeaf.getBasicConstraints())
        .thenThrow(new IllegalStateException(new CertificateException("malformed DER")));
    assertThat(
            assertChainInvalid(new Certificate[] {brokenLeaf}, rootTrustManager)
                .failureReason()
                .get())
        .contains("IllegalStateException");

    // TrustManager RuntimeException, plus null/blank toString() fallback to class name.
    X509Certificate directLeaf = signLeaf(rootCa, "epp.runtime.example");
    X509TrustManager providerExceptionTm = mock(X509TrustManager.class);
    doThrow(new ProviderException("provider failure"))
        .when(providerExceptionTm)
        .checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
    assertThat(
            assertChainInvalid(new Certificate[] {directLeaf}, providerExceptionTm)
                .failureReason()
                .get())
        .contains("provider failure");

    RuntimeException blankToString =
        new RuntimeException() {
          @Override
          @SuppressWarnings("OverrideThrowableToString")
          public String toString() {
            return "   ";
          }
        };
    RuntimeException nullToString =
        new RuntimeException() {
          @Override
          @SuppressWarnings({"OverrideThrowableToString", "ToStringReturnsNull"})
          public String toString() {
            return null;
          }
        };
    for (RuntimeException ex : ImmutableList.of(blankToString, nullToString)) {
      X509TrustManager tm = mock(X509TrustManager.class);
      doThrow(ex).when(tm).checkServerTrusted(any(X509Certificate[].class), eq("UNKNOWN"));
      assertThat(assertChainInvalid(new Certificate[] {directLeaf}, tm).failureReason())
          .hasValue(ex.getClass().getName());
    }
  }

  @Test
  void testExtractHostnames_dnsSansCanonicalization() throws Exception {
    // Mixed case, trailing dot, duplicates, punycode, and excluded wildcards.
    X509Certificate dnsSanCert =
        signLeaf(
            rootCa,
            "Ignored.CN.Example",
            "EPP1.Example.TLD.",
            "epp1.example.tld",
            "XN--Q9JYB4C.Example.COM",
            "*.Example.COM",
            "*.Sub.Example.COM");
    assertThat(extractHostnames(dnsSanCert))
        .containsExactly("epp1.example.tld", "xn--q9jyb4c.example.com")
        .inOrder();
    assertThat(extractHostnames(toJdkCertificate(dnsSanCert)))
        .containsExactly("epp1.example.tld", "xn--q9jyb4c.example.com")
        .inOrder();

    // Non-DNS SAN types (IP, email, URI) are ignored while DNS SANs are kept.
    X509Certificate mixedSanTypesCert =
        signLeafWithGeneralNames(
            new X500Name("CN=ignored.cn.example"),
            new GeneralName(GeneralName.iPAddress, "192.0.2.1"),
            new GeneralName(GeneralName.rfc822Name, "ops@example.tld"),
            new GeneralName(GeneralName.uniformResourceIdentifier, "https://epp.example.tld"),
            new GeneralName(GeneralName.dNSName, "epp.mixed-types.example"));
    assertThat(extractHostnames(mixedSanTypesCert)).containsExactly("epp.mixed-types.example");
    assertThat(extractHostnames(toJdkCertificate(mixedSanTypesCert)))
        .containsExactly("epp.mixed-types.example");

    // Malformed SAN list entries (null, short list, non-String value) are skipped.
    X509Certificate certWithMalformedSanEntries = mock(X509Certificate.class);
    List<List<?>> mixedMalformedSans =
        Arrays.asList(
            null,
            ImmutableList.of(GeneralName.dNSName),
            ImmutableList.of(GeneralName.dNSName, new byte[] {1, 2, 3}),
            ImmutableList.of(GeneralName.dNSName, "valid.example.tld"));
    when(certWithMalformedSanEntries.getSubjectAlternativeNames()).thenReturn(mixedMalformedSans);
    assertThat(extractHostnames(certWithMalformedSanEntries)).containsExactly("valid.example.tld");

    // RFC 5280 section 4.1.2.6: empty Subject DN with critical SAN validates and extracts cleanly.
    X509Certificate emptySubjectCert =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name(""),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(
                    Extension.subjectAlternativeName,
                    true,
                    new GeneralNames(
                        new GeneralName(GeneralName.dNSName, "epp.empty-subject.example"))));
    assertChainValid(new Certificate[] {emptySubjectCert}, rootTrustManager);
    assertThat(extractHostnames(emptySubjectCert)).containsExactly("epp.empty-subject.example");
  }

  @Test
  void testValidateCertificateChain_nameConstraints_enforcedAndCnCannotBypass() throws Exception {
    X509Certificate constrainedIntermediate =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            intermediateKeyPair.getPublic(),
            new X500Name("CN=constrained-intermediate.tld"),
            VALID_FROM,
            VALID_TO,
            builder -> {
              builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
              builder.addExtension(
                  Extension.nameConstraints,
                  true,
                  new NameConstraints(
                      new GeneralSubtree[] {
                        new GeneralSubtree(new GeneralName(GeneralName.dNSName, ".allowed.example"))
                      },
                      null));
            });

    // Permitted DNS SAN succeeds and extracts cleanly.
    X509Certificate permittedLeaf =
        signCert(
            intermediateKeyPair.getPrivate(),
            constrainedIntermediate,
            leafKeyPair.getPublic(),
            new X500Name("CN=epp.allowed.example"),
            VALID_FROM,
            VALID_TO,
            builder -> addDnsSans(builder, "epp.allowed.example"));
    assertChainValid(new Certificate[] {permittedLeaf, constrainedIntermediate}, rootTrustManager);
    assertThat(extractHostnames(permittedLeaf)).containsExactly("epp.allowed.example");

    // Disallowed DNS SAN (including trailing-dot variant) is rejected by PKIX NameConstraints.
    for (String disallowedSan : ImmutableList.of("epp.victim.example", "epp.victim.example.")) {
      X509Certificate disallowedSanLeaf =
          signCert(
              intermediateKeyPair.getPrivate(),
              constrainedIntermediate,
              leafKeyPair.getPublic(),
              new X500Name("CN=ok.allowed.example"),
              VALID_FROM,
              VALID_TO,
              builder -> addDnsSans(builder, disallowedSan));
      assertChainInvalid(
          new Certificate[] {disallowedSanLeaf, constrainedIntermediate}, rootTrustManager);
    }

    // Without a SAN extension, CN-only bypass payloads (trailing dot, multi-CN with permitted or
    // display-string last CN, and Unicode IDN CN) never yield extracted hostnames.
    ImmutableList<X500Name> cnBypassSubjects =
        ImmutableList.of(
            new X500Name("CN=epp.victim.example."),
            new X500Name("CN=epp.victim.example,CN=ok.allowed.example"),
            new X500Name("CN=epp.victim.example,CN=Acme Corp"),
            new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "epp.みんな").build());
    for (X500Name subject : cnBypassSubjects) {
      X509Certificate cnOnlyLeaf =
          signCert(
              intermediateKeyPair.getPrivate(),
              constrainedIntermediate,
              leafKeyPair.getPublic(),
              subject,
              VALID_FROM,
              VALID_TO,
              null);
      assertThat(extractHostnames(cnOnlyLeaf)).isEmpty();
      assertThat(extractHostnames(toJdkCertificate(cnOnlyLeaf))).isEmpty();
    }
  }

  @Test
  void testExtractHostnames_ignoresSubjectCnAndEmptyOrMalformedSans() throws Exception {
    // Per RFC 9525 section 6.2, Subject CN is never used even when no SAN extension is present.
    X509Certificate cnOnlyCert = signLeafWithGeneralNames(new X500Name("CN=EPP.Fallback.Example."));
    assertThat(extractHostnames(cnOnlyCert)).isEmpty();
    assertThat(extractHostnames(toJdkCertificate(cnOnlyCert))).isEmpty();

    // Non-DNS SANs and rejected wildcard SANs return empty without falling back to Subject CN.
    assertThat(
            extractHostnames(
                signLeafWithGeneralNames(
                    new X500Name("CN=fallback.example.tld"),
                    new GeneralName(GeneralName.iPAddress, "192.0.2.1"))))
        .isEmpty();
    assertThat(
            extractHostnames(
                signLeafWithGeneralNames(
                    new X500Name("CN=fallback.example.tld"),
                    new GeneralName(GeneralName.dNSName, "*.com"))))
        .isEmpty();

    // Degenerate empty-SEQUENCE SAN extension returns empty (both BC and JDK).
    X509Certificate emptySanSequenceCert =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=fallback.example.tld"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(Extension.subjectAlternativeName, false, new DERSequence()));
    assertThat(extractHostnames(emptySanSequenceCert)).isEmpty();
    assertThat(extractHostnames(toJdkCertificate(emptySanSequenceCert))).isEmpty();

    // Malformed SAN extension fails closed (both BC and JDK).
    X509Certificate malformedSanCert =
        signCert(
            rootCa.key(),
            rootCa.cert(),
            leafKeyPair.getPublic(),
            new X500Name("CN=fallback.example.tld"),
            VALID_FROM,
            VALID_TO,
            builder ->
                builder.addExtension(
                    Extension.subjectAlternativeName, false, new byte[] {0x01, 0x02, 0x03}));
    assertThrows(CertificateParsingException.class, malformedSanCert::getSubjectAlternativeNames);
    assertThat(extractHostnames(malformedSanCert)).isEmpty();
    assertThat(extractHostnames(toJdkCertificate(malformedSanCert))).isEmpty();
  }

  @Test
  void testExtractHostnames_rejectsWildcardsAndInvalidNames() throws Exception {
    ImmutableList<String> rejectedNames =
        ImmutableList.of(
            "",
            "   ",
            "localhost",
            "_epp.example.tld",
            "*.example.com",
            "*.sub.example.com",
            "*.com",
            "192.0.2.1",
            "a.example,b.example",
            "evil.example\r\nX-Injected: 1",
            "epp.example.tld\0.evil.com");
    for (String rejectedSan : rejectedNames) {
      X509Certificate invalidDnsSanCert =
          signLeaf(rootCa, "ignored.example", rejectedSan, "valid.example.tld");
      assertThat(extractHostnames(invalidDnsSanCert)).containsExactly("valid.example.tld");
    }
  }

  @Test
  void testExtractHostnames_handlesNullAndRuntimeExceptions() throws Exception {
    assertThrows(NullPointerException.class, () -> extractHostnames(null));
    assertThat(extractHostnames(signLeafWithGeneralNames(new X500Name("O=Example")))).isEmpty();

    X509Certificate emptySanCollectionCert = mock(X509Certificate.class);
    when(emptySanCollectionCert.getSubjectAlternativeNames()).thenReturn(ImmutableList.of());
    assertThat(extractHostnames(emptySanCollectionCert)).isEmpty();

    X509Certificate throwingSanCert = mock(X509Certificate.class);
    when(throwingSanCert.getSubjectAlternativeNames())
        .thenThrow(new IllegalStateException("malformed DER"));
    assertThat(extractHostnames(throwingSanCert)).isEmpty();
  }

  @Test
  void testFormatHostnamesHeader_nullEmptyAndBasicFormatting() {
    assertThat(formatHostnamesHeader(null, "cert-hash")).isEmpty();
    assertThat(formatHostnamesHeader(ImmutableList.of(), "cert-hash")).isEmpty();
    assertThat(formatHostnamesHeader(ImmutableList.of(""), "cert-hash")).isEmpty();
    assertThat(formatHostnamesHeader(ImmutableList.of("epp.example.tld"), "cert-hash"))
        .hasValue("epp.example.tld");
    assertThat(
            formatHostnamesHeader(
                ImmutableList.of("epp1.example.tld", "epp2.example.tld"), "cert-hash"))
        .hasValue("epp1.example.tld,epp2.example.tld");
  }

  @Test
  void testFormatHostnamesHeader_exactBoundariesAndTruncation() {
    // Exact MAX_HOSTNAMES_HEADER_BYTES (4096) fits without truncation.
    // 127 * 31 + 32 + 127 commas = 4096 bytes.
    ImmutableList<String> exactMaxHostnames =
        IntStream.range(0, 128)
            .mapToObj(
                i ->
                    i == 127
                        ? String.format("epp-host-%03d.reg-last.example.tl", i)
                        : String.format("epp-host-%03d.reg-pad.example.tl", i))
            .collect(toImmutableList());
    String exactFormatted = formatHostnamesHeader(exactMaxHostnames, "cert-hash").orElseThrow();
    assertThat(exactFormatted).hasLength(MAX_HOSTNAMES_HEADER_BYTES);

    // Comma right at index 4096 truncates cleanly to the exact 4096-byte prefix.
    ImmutableList<String> commaAtLimit =
        ImmutableList.<String>builder().addAll(exactMaxHostnames).add("x.tld").build();
    assertThat(formatHostnamesHeader(commaAtLimit, "cert-hash")).hasValue(exactFormatted);

    // Mid-hostname overflow drops the overflowing hostname and stops in prefix order.
    ImmutableList<String> prefixWithMidOverflow =
        ImmutableList.<String>builder()
            .addAll(exactMaxHostnames.subList(0, 127))
            .add("epp-host-127.reg-last.example.tld") // 33 bytes -> total 4097
            .add("a.tl")
            .build();
    assertThat(formatHostnamesHeader(prefixWithMidOverflow, "cert-hash"))
        .hasValue(String.join(",", exactMaxHostnames.subList(0, 127)));

    // Entry exceeding MAX_HOSTNAMES_HEADER_BYTES (or empty first entry before overflow) returns
    // empty rather than an empty string; empty middle entry produces no trailing comma.
    assertThat(
            formatHostnamesHeader(
                ImmutableList.of("a".repeat(MAX_HOSTNAMES_HEADER_BYTES + 1), "b.tld"), "cert-hash"))
        .isEmpty();
    assertThat(
            formatHostnamesHeader(
                ImmutableList.of("", "a".repeat(MAX_HOSTNAMES_HEADER_BYTES + 1)), "cert-hash"))
        .isEmpty();
    assertThat(
            formatHostnamesHeader(
                ImmutableList.of("a.example.tld", "", "b".repeat(MAX_HOSTNAMES_HEADER_BYTES)),
                "cert-hash"))
        .hasValue("a.example.tld");

    // Realistic oversized multi-SAN UCC cert (300 hostnames) is capped cleanly at <= 4096 bytes.
    ImmutableList<String> oversizedHostnames =
        IntStream.range(0, 300)
            .mapToObj(i -> String.format("epp-host-%03d.registrar.example.tld", i))
            .collect(toImmutableList());
    String formatted = formatHostnamesHeader(oversizedHostnames, "cert-hash").orElseThrow();
    assertThat(formatted.length()).isAtMost(MAX_HOSTNAMES_HEADER_BYTES);
    List<String> included = Splitter.on(',').splitToList(formatted);
    assertThat(included).hasSize(117);
    assertThat(included).isEqualTo(oversizedHostnames.subList(0, included.size()));
    assertThat(formatted.length() + 1 + oversizedHostnames.get(included.size()).length())
        .isGreaterThan(MAX_HOSTNAMES_HEADER_BYTES);
  }

  @Test
  void testFormatHostnamesHeader_truncationAndOmissionLogCertHash() {
    Logger logger = Logger.getLogger(X509Utils.class.getName());
    TestLogHandler logHandler = new TestLogHandler();
    logger.addHandler(logHandler);
    try {
      ImmutableList<String> oversized =
          IntStream.range(0, 150)
              .mapToObj(i -> String.format("epp-host-%03d.registrar.example.tld", i))
              .collect(toImmutableList());

      String formatted = formatHostnamesHeader(oversized, "abc123hash").orElseThrow();
      assertThat(formatHostnamesHeader(oversized, null)).hasValue(formatted);
      assertThat(
              formatHostnamesHeader(
                  ImmutableList.of("a".repeat(MAX_HOSTNAMES_HEADER_BYTES + 1)), "abc123hash"))
          .isEmpty();

      ImmutableList<LogRecord> warnings =
          logHandler.getStoredLogRecords().stream()
              .filter(record -> record.getLevel().equals(Level.WARNING))
              .collect(toImmutableList());
      assertThat(warnings).hasSize(3);
      assertThat(warnings.get(0).getMessage())
          .isEqualTo(
              String.format(
                  "Client certificate (hash=abc123hash) truncated X-SSL-Certificate-Hostnames"
                      + " header: kept 117 of 150 hostnames (%d bytes, limit 4096 bytes).",
                  formatted.length()));
      assertThat(warnings.get(1).getMessage())
          .isEqualTo(
              String.format(
                  "Client certificate (hash=unknown) truncated X-SSL-Certificate-Hostnames"
                      + " header: kept 117 of 150 hostnames (%d bytes, limit 4096 bytes).",
                  formatted.length()));
      assertThat(warnings.get(2).getMessage())
          .isEqualTo(
              "Client certificate (hash=abc123hash) omitted X-SSL-Certificate-Hostnames header: 0"
                  + " of 1 hostname(s) fit within 4096 bytes.");
    } finally {
      logger.removeHandler(logHandler);
    }
  }
}
