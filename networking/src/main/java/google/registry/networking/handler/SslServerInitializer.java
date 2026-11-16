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
import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.util.X509Utils.extractHostnames;
import static google.registry.util.X509Utils.getCertificateHash;
import static google.registry.util.X509Utils.getDefaultTrustManager;
import static google.registry.util.X509Utils.validateCertificateChain;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import google.registry.util.X509Utils.CertificateChainValidationResult;
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
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509TrustManager;

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
 * as the client uses the corresponding private key to establish SSL handshake. After handshake
 * completion, this handler validates the presented certificate chain against the configured
 * truststore (without failing the handshake for legacy hash-pinned certificates) and extracts
 * canonicalized DNS hostnames when chain validation succeeds so downstream handlers can forward the
 * certificate hash, chain-validation status, and hostnames as HTTP headers.
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
   * Attribute key to whether the client certificate chain was validated against trusted root CAs,
   * set before {@link #CLIENT_CERTIFICATE_PROMISE_KEY} completes.
   */
  public static final AttributeKey<Boolean> CLIENT_CERTIFICATE_CHAIN_VALIDATED_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_CHAIN_VALIDATED_KEY");

  /**
   * Attribute key to the canonicalized, deduplicated DNS hostnames (excluding wildcard names)
   * extracted from a validated client certificate's Subject Alternative Name {@code dNSName}
   * entries, set before {@link #CLIENT_CERTIFICATE_PROMISE_KEY} completes. Empty when chain
   * validation fails or the certificate contains no valid hostnames.
   */
  public static final AttributeKey<ImmutableList<String>> CLIENT_CERTIFICATE_HOSTNAMES_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_HOSTNAMES_KEY");

  /**
   * The list of cipher suites that are currently acceptable to create a successful handshake.
   *
   * <p>This list includes all the current TLS1.3 ciphers and a collection of TLS1.2 ciphers with no
   * known security vulnerabilities. Note that OpenSSL uses a separate nomenclature for the ciphers
   * internally but the IANA names listed here will be transparently translated by the OpenSSL
   * provider (if used), so there is no need to include the OpenSSL name variants here. More
   * information about these cipher suites and their OpenSSL names can be found at ciphersuite.info.
   */
  private static final ImmutableList<String> ALLOWED_TLS_CIPHERS =
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

  /** Thankfully, the JDK supports TLS version 1.3 now. */
  private static final ImmutableList<String> SUPPORTED_TLS_VERSIONS =
      ImmutableList.of("TLSv1.3", "TLSv1.2");

  private static final int MAX_LOGGED_HOSTNAMES = 10;
  private static final int MAX_LOGGED_FAILURE_REASON_CHARS = 512;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private final boolean requireClientCert;
  // TODO(jianglai): Always validate client certs (if required).
  private final boolean validateClientCert;
  private final SslProvider sslProvider;
  // We use suppliers for the key/cert pair because they are fetched and cached from GCS, and can
  // change when the artifacts on GCS changes.
  private final Supplier<PrivateKey> privateKeySupplier;
  private final Supplier<ImmutableList<X509Certificate>> certificatesSupplier;
  private final Optional<X509TrustManager> trustManager;

  public SslServerInitializer(
      boolean requireClientCert,
      boolean validateClientCert,
      SslProvider sslProvider,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<ImmutableList<X509Certificate>> certificatesSupplier) {
    this(
        requireClientCert,
        validateClientCert,
        sslProvider,
        privateKeySupplier,
        certificatesSupplier,
        requireClientCert ? Optional.of(getDefaultTrustManager()) : Optional.empty());
  }

  @VisibleForTesting
  SslServerInitializer(
      boolean requireClientCert,
      boolean validateClientCert,
      SslProvider sslProvider,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<ImmutableList<X509Certificate>> certificatesSupplier,
      Optional<X509TrustManager> trustManager) {
    logger.atInfo().log("Server SSL Provider: %s", sslProvider);
    checkArgument(
        requireClientCert || !validateClientCert,
        "Cannot validate client certificate if client certificate is not required.");
    checkNotNull(trustManager, "trustManager");
    checkArgument(
        !requireClientCert || trustManager.isPresent(),
        "Trust manager is required when client certificates are required.");
    this.requireClientCert = requireClientCert;
    this.validateClientCert = validateClientCert;
    this.sslProvider = sslProvider;
    this.privateKeySupplier = privateKeySupplier;
    this.certificatesSupplier = certificatesSupplier;
    this.trustManager = trustManager;
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
            .protocols(SUPPORTED_TLS_VERSIONS)
            .ciphers(ALLOWED_TLS_CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .build();

    logger.atInfo().log("Available Cipher Suites: %s", sslContext.cipherSuites());
    SslHandler sslHandler = sslContext.newHandler(channel.alloc());
    sslHandler.setHandshakeTimeoutMillis(20000);

    if (requireClientCert) {
      Promise<X509Certificate> clientCertificatePromise = channel.eventLoop().newPromise();
      Future<Channel> unusedFuture =
          sslHandler
              .handshakeFuture()
              .addListener(
                  future -> {
                    if (future.isSuccess()) {
                      try {
                        SSLSession sslSession = sslHandler.engine().getSession();
                        Certificate[] peerCertificates = sslSession.getPeerCertificates();
                        X509Certificate clientCertificate = (X509Certificate) peerCertificates[0];
                        String clientCertificateHash = getCertificateHash(clientCertificate);
                        PublicKey clientPublicKey = clientCertificate.getPublicKey();
                        // Note that for non-RSA keys the length would be -1.
                        int clientCertificateLength = -1;
                        if (clientPublicKey instanceof RSAPublicKey rsaPublicKey) {
                          clientCertificateLength = rsaPublicKey.getModulus().bitLength();
                        }
                        CertificateException validityException = null;
                        try {
                          clientCertificate.checkValidity();
                        } catch (CertificateNotYetValidException | CertificateExpiredException e) {
                          validityException = e;
                        }
                        CertificateChainValidationResult validationResult =
                            validityException != null
                                ? CertificateChainValidationResult.failed(
                                    validityException.toString())
                                : validateCertificateChain(
                                    peerCertificates, trustManager.orElseThrow());
                        boolean chainValidated = validationResult.valid();
                        ImmutableList<String> hostnames =
                            chainValidated
                                ? extractHostnames(clientCertificate)
                                : ImmutableList.of();
                        logger.atInfo().log(
                            """
                            --SSL Information--
                            Client Certificate Hash: %s
                            Client Certificate Chain Validated: %s
                            Client Certificate Chain Failure Reason: %s
                            Client Certificate Hostnames: %s
                            SSL Protocol: %s
                            Cipher Suite: %s
                            Not Before: %s
                            Not After: %s
                            Client Certificate Type: %s
                            Client Certificate Length: %s
                            """,
                            clientCertificateHash,
                            chainValidated,
                            formatFailureReasonForLog(validationResult.failureReason()),
                            formatHostnamesForLog(hostnames),
                            sslSession.getProtocol(),
                            sslSession.getCipherSuite(),
                            clientCertificate.getNotBefore(),
                            clientCertificate.getNotAfter(),
                            clientPublicKey.getClass().getName(),
                            clientCertificateLength);
                        if (validityException != null) {
                          logger.atWarning().withCause(validityException).log(
                              "Client certificate is not valid.\nHash: %s", clientCertificateHash);
                          if (validateClientCert) {
                            Promise<X509Certificate> unusedPromise =
                                clientCertificatePromise.setFailure(validityException);
                            ChannelFuture unusedFuture2 = channel.close();
                            return;
                          }
                        }
                        channel.attr(CLIENT_CERTIFICATE_CHAIN_VALIDATED_KEY).set(chainValidated);
                        channel.attr(CLIENT_CERTIFICATE_HOSTNAMES_KEY).set(hostnames);
                        Promise<X509Certificate> unusedPromise =
                            clientCertificatePromise.setSuccess(clientCertificate);
                      } catch (SSLPeerUnverifiedException | RuntimeException e) {
                        logger.atWarning().withCause(e).log(
                            "Failed to inspect client certificate.");
                        Promise<X509Certificate> unusedPromise =
                            clientCertificatePromise.setFailure(e);
                        ChannelFuture unusedFuture2 = channel.close();
                      }
                    } else {
                      Promise<X509Certificate> unusedPromise =
                          clientCertificatePromise.setFailure(future.cause());
                    }
                  });
      channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).set(clientCertificatePromise);
    }
    channel.pipeline().addLast(sslHandler);
  }

  @VisibleForTesting
  static String formatHostnamesForLog(ImmutableList<String> hostnames) {
    checkNotNull(hostnames, "hostnames");
    if (hostnames.size() <= MAX_LOGGED_HOSTNAMES) {
      return hostnames.toString();
    }
    return String.format(
        "[%s, ... (%d total)]",
        Joiner.on(", ").join(hostnames.subList(0, MAX_LOGGED_HOSTNAMES)), hostnames.size());
  }

  // Sanitize and bound the failure reason logged at INFO: because this handler accepts arbitrary
  // unauthenticated client certificates, a crafted or malformed cert can cause JDK X.509/PKIX
  // exceptions to echo long or newline-containing fields (e.g., invalid extension values during
  // lazy OpenSSL parsing or large sets of unsupported critical OIDs).
  @VisibleForTesting
  static String formatFailureReasonForLog(Optional<String> failureReason) {
    checkNotNull(failureReason, "failureReason");
    if (failureReason.isEmpty()) {
      return "none";
    }
    String sanitized = failureReason.get().replace('\r', ' ').replace('\n', ' ');
    return sanitized.length() <= MAX_LOGGED_FAILURE_REASON_CHARS
        ? sanitized
        : sanitized.substring(0, MAX_LOGGED_FAILURE_REASON_CHARS - 3) + "...";
  }
}
