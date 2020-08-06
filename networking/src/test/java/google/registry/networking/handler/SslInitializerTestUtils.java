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
import google.registry.networking.util.SelfSignedCaCertificate;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.ssl.SslHandler;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import javax.net.ssl.SSLSession;
import org.bouncycastle.asn1.x500.X500Name;
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

  public static KeyPair getKeyPair() throws Exception {
    return KEY_PAIR_GENERATOR.generateKeyPair();
  }

  /**
   * Signs the given key pair with the given self signed certificate to generate a certificate with
   * the given validity range.
   *
   * @return signed public key (of the key pair) certificate
   */
  public static X509Certificate signKeyPair(
      SelfSignedCaCertificate ssc, KeyPair keyPair, String hostname, Date from, Date to)
      throws Exception {
    X500Name subjectDnName = new X500Name("CN=" + hostname);
    BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());
    X500Name issuerDnName = new X500Name(ssc.cert().getIssuerDN().getName());
    ContentSigner sigGen = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(ssc.key());
    X509v3CertificateBuilder v3CertGen =
        new JcaX509v3CertificateBuilder(
            issuerDnName, serialNumber, from, to, subjectDnName, keyPair.getPublic());

    X509CertificateHolder certificateHolder = v3CertGen.build(sigGen);
    return new JcaX509CertificateConverter()
        .setProvider(PROVIDER)
        .getCertificate(certificateHolder);
  }

  /**
   * Signs the given key pair with the given self signed certificate to generate a certificate that
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
        Date.from(Instant.now().minus(Duration.ofDays(1))),
        Date.from(Instant.now().plus(Duration.ofDays(1))));
  }

  /**
   * Verifies tha the SSL channel is established as expected, and also sends a message to the server
   * and verifies if it is echoed back correctly.
   *
   * @param certs The certificate that the server should provide.
   * @return The SSL session in current channel, can be used for further validation.
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
