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

import io.netty.channel.Channel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.x509.X509V3CertificateGenerator;

/**
 * Utility class that provides methods used by {@link SslClientInitializerTest} and {@link
 * SslServerInitializerTest}.
 */
@SuppressWarnings("deprecation")
public final class SslInitializerTestUtils {

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  private SslInitializerTestUtils() {}

  public static KeyPair getKeyPair() throws Exception {
    KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
    keyPairGenerator.initialize(2048, new SecureRandom());
    return keyPairGenerator.generateKeyPair();
  }

  /**
   * Signs the given key pair with the given self signed certificate.
   *
   * @return signed public key (of the key pair) certificate
   */
  public static X509Certificate signKeyPair(
      SelfSignedCertificate ssc, KeyPair keyPair, String hostname) throws Exception {
    X509V3CertificateGenerator certGen = new X509V3CertificateGenerator();
    X500Principal dnName = new X500Principal("CN=" + hostname);
    certGen.setSerialNumber(BigInteger.valueOf(System.currentTimeMillis()));
    certGen.setSubjectDN(dnName);
    certGen.setIssuerDN(ssc.cert().getSubjectX500Principal());
    certGen.setNotBefore(Date.from(Instant.now().minus(Duration.ofDays(1))));
    certGen.setNotAfter(Date.from(Instant.now().plus(Duration.ofDays(1))));
    certGen.setPublicKey(keyPair.getPublic());
    certGen.setSignatureAlgorithm("SHA256WithRSAEncryption");
    return certGen.generate(ssc.key(), "BC");
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
}
