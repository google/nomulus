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
import static google.registry.networking.handler.SslInitializerTestUtils.getKeyPair;
import static google.registry.networking.handler.SslInitializerTestUtils.setUpSslChannel;
import static google.registry.networking.handler.SslInitializerTestUtils.signKeyPair;
import static google.registry.networking.handler.SslInitializerTestUtils.verifySslException;

import com.google.common.collect.ImmutableList;
import google.registry.networking.util.SelfSignedCaCertificate;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link SslClientInitializer}.
 *
 * <p>To validate that the handler accepts & rejects connections as expected, a test server and a
 * test client are spun up, and both connect to the {@link LocalAddress} within the JVM. This avoids
 * the overhead of routing traffic through the network layer, even if it were to go through
 * loopback. It also alleviates the need to pick a free port to use.
 *
 * <p>The local addresses used in each test method must to be different, otherwise tests run in
 * parallel may interfere with each other.
 */
class SslClientInitializerTest {

  /** Fake host to test if the SSL engine gets the correct peer host. */
  private static final String SSL_HOST = "www.example.tld";

  /** Fake port to test if the SSL engine gets the correct peer port. */
  private static final int SSL_PORT = 12345;

  private static final Function<Channel, String> hostProvider = channel -> SSL_HOST;

  private static final Function<Channel, Integer> portProvider = channel -> SSL_PORT;

  @RegisterExtension NettyExtension nettyExtension = new NettyExtension();

  @SuppressWarnings("unused")
  static Stream<Arguments> provideTestCombinations() {
    Stream.Builder<Arguments> args = Stream.builder();
    // We do our best effort to test all available SSL providers.
    args.add(Arguments.of(SslProvider.JDK));
    if (OpenSsl.isAvailable()) {
      args.add(Arguments.of(SslProvider.OPENSSL));
    }
    return args.build();
  }

  /** Saves the SNI hostname received by the server, if sent by the client. */
  private String sniHostReceived;

  private ChannelHandler getServerHandler(
      boolean requireClientCert, PrivateKey privateKey, X509Certificate certificate)
      throws Exception {
    SslContext sslContext =
        SslContextBuilder.forServer(privateKey, certificate)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .clientAuth(requireClientCert ? ClientAuth.REQUIRE : ClientAuth.NONE)
            .build();
    return new SniHandler(
        hostname -> {
          sniHostReceived = hostname;
          return sslContext;
        });
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_swappedInitializerWithSslHandler(SslProvider sslProvider) {
    SslClientInitializer<EmbeddedChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(), null, null);
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslClientInitializer);
    ChannelHandler firstHandler = pipeline.first();
    assertThat(firstHandler.getClass()).isEqualTo(SslHandler.class);
    SslHandler sslHandler = (SslHandler) firstHandler;
    assertThat(sslHandler.engine().getPeerHost()).isEqualTo(SSL_HOST);
    assertThat(sslHandler.engine().getPeerPort()).isEqualTo(SSL_PORT);
    assertThat(channel.isActive()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_nullHost(SslProvider sslProvider) {
    SslClientInitializer<EmbeddedChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, channel -> null, portProvider, ImmutableList.of(), null, null);
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslClientInitializer);
    // Channel initializer swallows error thrown, and closes the connection.
    assertThat(channel.isActive()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_nullPort(SslProvider sslProvider) {
    SslClientInitializer<EmbeddedChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, channel -> null, ImmutableList.of(), null, null);
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslClientInitializer);
    // Channel initializer swallows error thrown, and closes the connection.
    assertThat(channel.isActive()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_defaultTrustManager_rejectSelfSignedCert(SslProvider sslProvider)
      throws Exception {
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress =
        new LocalAddress("DEFAULT_TRUST_MANAGER_REJECT_SELF_SIGNED_CERT_" + sslProvider);
    nettyExtension.setUpServer(localAddress, getServerHandler(false, ssc.key(), ssc.cert()));
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(), null, null);
    nettyExtension.setUpClient(localAddress, sslClientInitializer);
    // The connection is now terminated, both the client side and the server side should get
    // exceptions.
    nettyExtension.assertThatClientRootCause().isInstanceOf(CertPathBuilderException.class);
    nettyExtension.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyExtension.getClientChannel().isActive()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_customTrustManager_acceptCertSignedByTrustedCa(SslProvider sslProvider)
      throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_ACCEPT_CERT_SIGNED_BY_TRUSTED_CA_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create();
    X509Certificate cert = signKeyPair(ssc, keyPair, SSL_HOST);

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyExtension.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyExtension.setUpClient(localAddress, sslClientInitializer);

    setUpSslChannel(nettyExtension.getClientChannel(), cert);
    nettyExtension.assertThatMessagesWork();

    // Verify that the SNI extension is sent during handshake.
    assertThat(sniHostReceived).isEqualTo(SSL_HOST);
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_customTrustManager_serverCertExpired(SslProvider sslProvider) throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_SERVE_CERT_EXPIRED_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create();
    X509Certificate cert =
        signKeyPair(
            ssc,
            keyPair,
            SSL_HOST,
            Date.from(Instant.now().minus(Duration.ofDays(2))),
            Date.from(Instant.now().minus(Duration.ofDays(1))));

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyExtension.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyExtension.setUpClient(localAddress, sslClientInitializer);

    verifySslException(
        nettyExtension.getClientChannel(),
        channel -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(),
        CertificateExpiredException.class);
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_customTrustManager_serverCertNotYetValid(SslProvider sslProvider)
      throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_SERVE_CERT_NOT_YET_VALID_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create();
    X509Certificate cert =
        signKeyPair(
            ssc,
            keyPair,
            SSL_HOST,
            Date.from(Instant.now().plus(Duration.ofDays(1))),
            Date.from(Instant.now().plus(Duration.ofDays(2))));

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyExtension.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyExtension.setUpClient(localAddress, sslClientInitializer);

    verifySslException(
        nettyExtension.getClientChannel(),
        channel -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(),
        CertificateNotYetValidException.class);
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_customTrustManager_acceptSelfSignedCert_clientCertRequired(
      SslProvider sslProvider) throws Exception {
    LocalAddress localAddress =
        new LocalAddress(
            "CUSTOM_TRUST_MANAGER_ACCEPT_SELF_SIGNED_CERT_CLIENT_CERT_REQUIRED_" + sslProvider);

    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    SelfSignedCaCertificate clientSsc = SelfSignedCaCertificate.create();

    // Set up the server to require client certificate.
    nettyExtension.setUpServer(
        localAddress, getServerHandler(true, serverSsc.key(), serverSsc.cert()));

    // Set up the client to trust the server certificate and use the client certificate.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider,
            hostProvider,
            portProvider,
            ImmutableList.of(serverSsc.cert()),
            () -> clientSsc.key(),
            () -> ImmutableList.of(clientSsc.cert()));
    nettyExtension.setUpClient(localAddress, sslClientInitializer);

    SSLSession sslSession = setUpSslChannel(nettyExtension.getClientChannel(), serverSsc.cert());
    nettyExtension.assertThatMessagesWork();

    // Verify that the SNI extension is sent during handshake.
    assertThat(sniHostReceived).isEqualTo(SSL_HOST);

    // Verify that the SSL session gets the client cert. Note that this SslSession is for the client
    // channel, therefore its local certificates are the remote certificates of the SslSession for
    // the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_customTrustManager_wrongHostnameInCertificate(SslProvider sslProvider)
      throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_WRONG_HOSTNAME_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create();
    X509Certificate cert = signKeyPair(ssc, keyPair, "wrong.com");

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyExtension.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyExtension.setUpClient(localAddress, sslClientInitializer);

    // When the client rejects the server cert due to wrong hostname, both the client and server
    // should throw exceptions.
    nettyExtension.assertThatClientRootCause().isInstanceOf(CertificateException.class);
    nettyExtension.assertThatClientRootCause().hasMessageThat().contains(SSL_HOST);
    nettyExtension.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyExtension.getClientChannel().isActive()).isFalse();
  }
}
