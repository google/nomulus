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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.X509Utils.getCertificateHash;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.util.Clock;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.function.Supplier;
import javax.net.ssl.SSLSession;
import org.joda.time.DateTime;

/**
 * Adds a server side SSL handler to the channel pipeline.
 *
 * <p>This <b>should</b> be the first handler provided for any handler provider list, if it is
 * provided. Unless you wish to first process the PROXY header with another handler, which should
 * come before this handler. The type parameter {@code C} is needed so that unit tests can construct
 * this handler that works with {@link EmbeddedChannel};
 *
 * <p>The ssl handler added can require client authentication, but it uses an {@link
 * InsecureTrustManagerFactory}, which accepts any ssl certificate presented by the client, as long
 * as the client uses the corresponding private key to establish SSL handshake. The client
 * certificate hash will be passed along to GAE as an HTTP header for verification (not handled by
 * this handler).
 */
@Sharable
public class SslServerInitializer<C extends Channel> extends ChannelInitializer<C> {

  /**
   * Attribute key to the client certificate promise whose value is set when SSL handshake completes
   * successfully.
   */
  public static final AttributeKey<Promise<X509Certificate>> CLIENT_CERTIFICATE_PROMISE_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_PROMISE_KEY");

  /**
   * The list of cipher suites that are currently acceptable to create a successful handshake.
   *
   * <p>This list includes all of the current TLS1.3 ciphers and a collection of TLS1.2 ciphers with
   * no known security vulnerabilities. Note that OpenSSL uses a separate nomenclature for the
   * ciphers internally but the IANA names listed here will be transparently translated by the
   * OpenSSL provider (if used), so there is no need to include the OpenSSL name variants here. More
   * information about these cipher suites and their OpenSSL names can be found at ciphersuite.info.
   */
  private static final ImmutableList ALLOWED_TLS_CIPHERS =
      ImmutableList.of(
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_AES_128_GCM_SHA256",
          "TLS_AES_256_GCM_SHA384",
          "TLS_CHACHA20_POLY1305_SHA256",
          "TLS_AES_128_CCM_SHA256",
          "TLS_AES_128_CCM_8_SHA256");

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final boolean requireClientCert;
  // TODO(jianglai): Always validate client certs (if required).
  private final boolean validateClientCert;
  private final SslProvider sslProvider;
  // We use suppliers for the key/cert pair because they are fetched and cached from GCS, and can
  // change when the artifacts on GCS changes.
  private final Supplier<PrivateKey> privateKeySupplier;
  private final Supplier<ImmutableList<X509Certificate>> certificatesSupplier;
  private final ImmutableList<String> supportedSslVersions;
  // TODO(sarahbot): Remove this variable and its check after enforcement start date has passed.
  private final ImmutableList<String> oldSupportedSslVersions;
  private final DateTime enforcementStartTime;
  private final Clock clock;

  public SslServerInitializer(
      boolean requireClientCert,
      boolean validateClientCert,
      SslProvider sslProvider,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<ImmutableList<X509Certificate>> certificatesSupplier,
      DateTime enforcementStartTime,
      Clock clock) {
    logger.atInfo().log("Server SSL Provider: %s", sslProvider);
    checkArgument(
        requireClientCert || !validateClientCert,
        "Cannot validate client certificate if client certificate is not required.");
    this.requireClientCert = requireClientCert;
    this.validateClientCert = validateClientCert;
    this.sslProvider = sslProvider;
    this.privateKeySupplier = privateKeySupplier;
    this.certificatesSupplier = certificatesSupplier;
    this.supportedSslVersions =
        sslProvider == SslProvider.OPENSSL
            ? ImmutableList.of("TLSv1.3", "TLSv1.2")
            // JDK support for TLS 1.3 won't be available until 2021-04-20 at the earliest.
            // See: https://java.com/en/jre-jdk-cryptoroadmap.html
            : ImmutableList.of("TLSv1.2");
    this.oldSupportedSslVersions =
        sslProvider == SslProvider.OPENSSL
            ? ImmutableList.of("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
            : ImmutableList.of("TLSv1.2", "TLSv1.1", "TLSv1");
    this.enforcementStartTime = enforcementStartTime;
    this.clock = clock;
  }

  @Override
  protected void initChannel(C channel) throws Exception {
    SslContext sslContext =
        SslContextBuilder.forServer(
                privateKeySupplier.get(),
                certificatesSupplier.get().toArray(new X509Certificate[0]))
            .sslProvider(sslProvider)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .clientAuth(requireClientCert ? ClientAuth.REQUIRE : ClientAuth.NONE)
            .protocols(
                enforcementStartTime.isBefore(clock.nowUtc())
                    ? supportedSslVersions
                    : oldSupportedSslVersions)
            .ciphers(
                enforcementStartTime.isBefore(clock.nowUtc()) ? ALLOWED_TLS_CIPHERS : null,
                SupportedCipherSuiteFilter.INSTANCE)
            .build();

    logger.atInfo().log("Available Cipher Suites: %s", sslContext.cipherSuites());
    SslHandler sslHandler = sslContext.newHandler(channel.alloc());
    if (requireClientCert) {
      Promise<X509Certificate> clientCertificatePromise = channel.eventLoop().newPromise();
      Future<Channel> unusedFuture =
          sslHandler
              .handshakeFuture()
              .addListener(
                  future -> {
                    if (future.isSuccess()) {
                      SSLSession sslSession = sslHandler.engine().getSession();
                      X509Certificate clientCertificate =
                          (X509Certificate) sslSession.getPeerCertificates()[0];
                      PublicKey clientPublicKey = clientCertificate.getPublicKey();
                      // Note that for non-RSA keys the length would be -1.
                      int clientCertificateLength = -1;
                      if (clientPublicKey instanceof RSAPublicKey) {
                        clientCertificateLength =
                            ((RSAPublicKey) clientPublicKey).getModulus().bitLength();
                      }
                      logger.atInfo().log(
                          "--SSL Information--\n"
                              + "Client Certificate Hash: %s\n"
                              + "SSL Protocol: %s\n"
                              + "Cipher Suite: %s\n"
                              + "Not Before: %s\n"
                              + "Not After: %s\n"
                              + "Client Certificate Type: %s\n"
                              + "Client Certificate Length: %s\n",
                          getCertificateHash(clientCertificate),
                          sslSession.getProtocol(),
                          sslSession.getCipherSuite(),
                          clientCertificate.getNotBefore(),
                          clientCertificate.getNotAfter(),
                          clientPublicKey.getClass().getName(),
                          clientCertificateLength);
                      try {
                        clientCertificate.checkValidity();
                      } catch (CertificateNotYetValidException | CertificateExpiredException e) {
                        logger.atWarning().withCause(e).log(
                            "Client certificate is not valid.\nHash: %s",
                            getCertificateHash(clientCertificate));
                        if (validateClientCert) {
                          Promise<X509Certificate> unusedPromise =
                              clientCertificatePromise.setFailure(e);
                          ChannelFuture unusedFuture2 = channel.close();
                          return;
                        }
                      }
                      Promise<X509Certificate> unusedPromise =
                          clientCertificatePromise.setSuccess(clientCertificate);
                    } else {
                      Promise<X509Certificate> unusedPromise =
                          clientCertificatePromise.setFailure(future.cause());
                    }
                  });
      channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).set(clientCertificatePromise);
    }
    channel.pipeline().addLast(sslHandler);
  }
}
