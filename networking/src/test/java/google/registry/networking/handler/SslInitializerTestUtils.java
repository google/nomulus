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

package google.registry.networking.handler;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Throwables;
import google.registry.util.SelfSignedCaCertificate;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.ssl.SslHandler;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Utility class that provides methods used by {@link SslClientInitializerTest} and {@link
 * SslServerInitializerTest}.
 */
public final class SslInitializerTestUtils {

  private static final BouncyCastleProvider PROVIDER = new BouncyCastleProvider();
  private static final KeyPairGenerator KEY_PAIR_GENERATOR = getKeyPairGenerator();
  private static final KeyPairGenerator EC_KEY_PAIR_GENERATOR = getEcKeyPairGenerator();
  private static final AtomicLong SERIAL_NUMBER = new AtomicLong(1);

  private SslInitializerTestUtils() {}

  private static KeyPairGenerator getKeyPairGenerator() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", PROVIDER);
      keyPairGenerator.initialize(2048, new SecureRandom());
      return keyPairGenerator;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static KeyPairGenerator getEcKeyPairGenerator() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", PROVIDER);
      keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
      return keyPairGenerator;
    } catch (GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  public static KeyPair getKeyPair() throws Exception {
    return KEY_PAIR_GENERATOR.generateKeyPair();
  }

  public static KeyPair getEcKeyPair() {
    return EC_KEY_PAIR_GENERATOR.generateKeyPair();
  }

  /**
   * Signs the given key pair with the given issuer key and certificate to generate a certificate
   * with the given validity range.
   *
   * @return signed public key (of the key pair) certificate
   */
  public static X509Certificate signKeyPair(
      PrivateKey issuerKey,
      X509Certificate issuerCert,
      KeyPair keyPair,
      String hostname,
      Instant from,
      Instant to,
      String... dnsSans)
      throws Exception {
    return signKeyPair(
        issuerKey, issuerCert, keyPair, hostname, from, to, Optional.empty(), dnsSans);
  }

  private static X509Certificate signKeyPair(
      PrivateKey issuerKey,
      X509Certificate issuerCert,
      KeyPair keyPair,
      String hostname,
      Instant from,
      Instant to,
      Optional<KeyPurposeId> eku,
      String... dnsSans)
      throws Exception {
    X500Name subjectDnName = new X500Name("CN=" + hostname);
    BigInteger serialNumber = BigInteger.valueOf(SERIAL_NUMBER.incrementAndGet());
    X500Name issuerDnName = X500Name.getInstance(issuerCert.getSubjectX500Principal().getEncoded());
    ContentSigner sigGen =
        new JcaContentSignerBuilder(signatureAlgorithmFor(issuerKey)).build(issuerKey);
    X509v3CertificateBuilder v3CertGen =
        new JcaX509v3CertificateBuilder(
            issuerDnName,
            serialNumber,
            Date.from(from),
            Date.from(to),
            subjectDnName,
            keyPair.getPublic());
    if (eku.isPresent()) {
      v3CertGen.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(eku.get()));
    }
    if (dnsSans.length > 0) {
      GeneralName[] generalNames =
          Arrays.stream(dnsSans)
              .map(san -> new GeneralName(GeneralName.dNSName, san))
              .toArray(GeneralName[]::new);
      v3CertGen.addExtension(
          Extension.subjectAlternativeName, false, new GeneralNames(generalNames));
    }

    X509CertificateHolder certificateHolder = v3CertGen.build(sigGen);
    return new JcaX509CertificateConverter()
        .setProvider(PROVIDER)
        .getCertificate(certificateHolder);
  }

  /**
   * Signs the given key pair with the given self-signed certificate to generate a certificate with
   * the given validity range.
   *
   * @return signed public key (of the key pair) certificate
   */
  public static X509Certificate signKeyPair(
      SelfSignedCaCertificate ssc,
      KeyPair keyPair,
      String hostname,
      Instant from,
      Instant to,
      String... dnsSans)
      throws Exception {
    return signKeyPair(ssc.key(), ssc.cert(), keyPair, hostname, from, to, dnsSans);
  }

  /** Signs the given key pair as an intermediate CA certificate. */
  public static X509Certificate signIntermediateCa(
      SelfSignedCaCertificate issuer, KeyPair keyPair, String cn, Instant from, Instant to)
      throws Exception {
    X500Name subjectDnName = new X500Name("CN=" + cn);
    BigInteger serialNumber = BigInteger.valueOf(SERIAL_NUMBER.incrementAndGet());
    X500Name issuerDnName =
        X500Name.getInstance(issuer.cert().getSubjectX500Principal().getEncoded());
    ContentSigner sigGen =
        new JcaContentSignerBuilder(signatureAlgorithmFor(issuer.key())).build(issuer.key());
    X509v3CertificateBuilder v3CertGen =
        new JcaX509v3CertificateBuilder(
            issuerDnName,
            serialNumber,
            Date.from(from),
            Date.from(to),
            subjectDnName,
            keyPair.getPublic());
    v3CertGen.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
    return new JcaX509CertificateConverter()
        .setProvider(PROVIDER)
        .getCertificate(v3CertGen.build(sigGen));
  }

  /**
   * Signs the given key pair with the given self-signed certificate to generate a certificate that
   * is valid from yesterday to tomorrow.
   *
   * @return signed public key (of the key pair) certificate
   */
  public static X509Certificate signKeyPair(
      SelfSignedCaCertificate ssc, KeyPair keyPair, String hostname) throws Exception {
    return signKeyPair(
        ssc,
        keyPair,
        hostname,
        Instant.now().minus(1, ChronoUnit.DAYS),
        Instant.now().plus(1, ChronoUnit.DAYS));
  }

  /**
   * Signs the given key pair with the given self-signed certificate and DNS Subject Alternative
   * Names using the issuer certificate's validity window.
   */
  public static X509Certificate signKeyPairWithSans(
      SelfSignedCaCertificate ssc, KeyPair keyPair, String hostname, String... dnsSans)
      throws Exception {
    return signKeyPair(
        ssc,
        keyPair,
        hostname,
        ssc.cert().getNotBefore().toInstant(),
        ssc.cert().getNotAfter().toInstant(),
        dnsSans);
  }

  /**
   * Signs the given key pair with the given self-signed certificate, Extended Key Usage, and DNS
   * Subject Alternative Names using the issuer certificate's validity window.
   */
  public static X509Certificate signKeyPairWithEku(
      SelfSignedCaCertificate ssc,
      KeyPair keyPair,
      String hostname,
      KeyPurposeId eku,
      String... dnsSans)
      throws Exception {
    return signKeyPair(
        ssc.key(),
        ssc.cert(),
        keyPair,
        hostname,
        ssc.cert().getNotBefore().toInstant(),
        ssc.cert().getNotAfter().toInstant(),
        Optional.of(eku),
        dnsSans);
  }

  private static String signatureAlgorithmFor(PrivateKey key) {
    return "EC".equals(key.getAlgorithm()) ? "SHA256WithECDSA" : "SHA256WithRSAEncryption";
  }

  /** Creates a PKIX {@link X509TrustManager} trusting the given CA certificates. */
  static X509TrustManager createTrustManager(X509Certificate... trustedCerts) throws Exception {
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    for (int i = 0; i < trustedCerts.length; i++) {
      keyStore.setCertificateEntry("ca-" + i, trustedCerts[i]);
    }
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
    trustManagerFactory.init(keyStore);
    return (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
  }

  /**
   * Verifies tha the SSL channel is established as expected, and also sends a message to the server
   * and verifies if it is echoed back correctly.
   *
   * @param certs The certificate that the server should provide.
   * @return The SSL session in the current channel, can be used for further validation.
   */
  static SSLSession setUpSslChannel(Channel channel, X509Certificate... certs) throws Exception {
    SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
    // Wait till the handshake is complete.
    sslHandler.handshakeFuture().get();

    assertThat(channel.isActive()).isTrue();
    assertThat(sslHandler.handshakeFuture().isSuccess()).isTrue();
    assertThat(sslHandler.engine().getSession().isValid()).isTrue();
    assertThat(sslHandler.engine().getSession().getPeerCertificates())
        .asList()
        .containsExactlyElementsIn(certs);
    // Returns the SSL session for further assertion.
    return sslHandler.engine().getSession();
  }

  /** Verifies tha the SSL channel cannot be established due to a given exception. */
  static void verifySslException(
      Channel channel, CheckedConsumer<Channel> operation, Class<? extends Exception> cause)
      throws Exception {
    // Extract SSL exception from the handshake future.
    Exception exception = assertThrows(ExecutionException.class, () -> operation.accept(channel));

    // Verify that the exception is caused by the expected cause.
    assertThat(Throwables.getRootCause(exception)).isInstanceOf(cause);

    // Ensure that the channel is closed. If this step times out, the channel is not properly
    // closed.
    ChannelFuture unusedFuture = channel.closeFuture().syncUninterruptibly();
  }

  /** A consumer that can throw checked exceptions. */
  @FunctionalInterface
  interface CheckedConsumer<T> {
    void accept(T t) throws Exception;
  }
}
