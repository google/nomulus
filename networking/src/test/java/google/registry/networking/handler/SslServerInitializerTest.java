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
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import google.registry.networking.util.SelfSignedCaCertificate;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@link SslServerInitializer}.
 *
 * <p>To validate that the handler accepts & rejects connections as expected, a test server and a
 * test client are spun up, and both connect to the {@link LocalAddress} within the JVM. This avoids
 * the overhead of routing traffic through the network layer, even if it were to go through
 * loopback. It also alleviates the need to pick a free port to use.
 *
 * <p>The local addresses used in each test method must to be different, otherwise tests run in
 * parallel may interfere with each other.
 */
class SslServerInitializerTest {

  /** Fake host to test if the SSL engine gets the correct peer host. */
  private static final String SSL_HOST = "www.example.tld";

  /** Fake port to test if the SSL engine gets the correct peer port. */
  private static final int SSL_PORT = 12345;

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

  private ChannelHandler getServerHandler(
      boolean requireClientCert,
      boolean validateClientCert,
      SslProvider sslProvider,
      PrivateKey privateKey,
      X509Certificate... certificates) {
    return new SslServerInitializer<LocalChannel>(
        requireClientCert,
        validateClientCert,
        sslProvider,
        Suppliers.ofInstance(privateKey),
        Suppliers.ofInstance(ImmutableList.copyOf(certificates)));
  }

  private ChannelHandler getClientHandler(
      SslProvider sslProvider,
      X509Certificate trustedCertificate,
      PrivateKey privateKey,
      X509Certificate certificate) {
    return new ChannelInitializer<LocalChannel>() {
      @Override
      protected void initChannel(LocalChannel ch) throws Exception {
        SslContextBuilder sslContextBuilder =
            SslContextBuilder.forClient().trustManager(trustedCertificate).sslProvider(sslProvider);
        if (privateKey != null && certificate != null) {
          sslContextBuilder.keyManager(privateKey, certificate);
        }
        SslHandler sslHandler =
            sslContextBuilder.build().newHandler(ch.alloc(), SSL_HOST, SSL_PORT);

        // Enable hostname verification.
        SSLEngine sslEngine = sslHandler.engine();
        SSLParameters sslParameters = sslEngine.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        sslEngine.setSSLParameters(sslParameters);

        ch.pipeline().addLast(sslHandler);
      }
    };
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_swappedInitializerWithSslHandler(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate ssc = SelfSignedCaCertificate.create(SSL_HOST);
    SslServerInitializer<EmbeddedChannel> sslServerInitializer =
        new SslServerInitializer<>(
            true,
            false,
            sslProvider,
            Suppliers.ofInstance(ssc.key()),
            Suppliers.ofInstance(ImmutableList.of(ssc.cert())));
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslServerInitializer);
    ChannelHandler firstHandler = pipeline.first();
    assertThat(firstHandler.getClass()).isEqualTo(SslHandler.class);
    SslHandler sslHandler = (SslHandler) firstHandler;
    assertThat(sslHandler.engine().getNeedClientAuth()).isTrue();
    assertThat(channel.isActive()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_trustAnyClientCert(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("TRUST_ANY_CLIENT_CERT_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress,
        getServerHandler(true, false, sslProvider, serverSsc.key(), serverSsc.cert()));
    SelfSignedCaCertificate clientSsc = SelfSignedCaCertificate.create();
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(sslProvider, serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    SSLSession sslSession = setUpSslChannel(nettyExtension.getClientChannel(), serverSsc.cert());
    nettyExtension.assertThatMessagesWork();

    // Verify that the SSL session gets the client cert. Note that this SslSession is for the client
    // channel, therefore its local certificates are the remote certificates of the SslSession for
    // the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_clientCertExpired(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("CLIENT_CERT_EXPIRED_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress, getServerHandler(true, true, sslProvider, serverSsc.key(), serverSsc.cert()));
    SelfSignedCaCertificate clientSsc =
        SelfSignedCaCertificate.create(
            "CLIENT",
            Date.from(Instant.now().minus(Duration.ofDays(2))),
            Date.from(Instant.now().minus(Duration.ofDays(1))));
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(sslProvider, serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    verifySslException(
        nettyExtension.getServerChannel(),
        channel -> channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).get().get(),
        CertificateExpiredException.class);
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_clientCertNotYetValid(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("CLIENT_CERT_EXPIRED_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress, getServerHandler(true, true, sslProvider, serverSsc.key(), serverSsc.cert()));
    SelfSignedCaCertificate clientSsc =
        SelfSignedCaCertificate.create(
            "CLIENT",
            Date.from(Instant.now().plus(Duration.ofDays(1))),
            Date.from(Instant.now().plus(Duration.ofDays(2))));
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(sslProvider, serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    verifySslException(
        nettyExtension.getServerChannel(),
        channel -> channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY).get().get(),
        CertificateNotYetValidException.class);
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_doesNotRequireClientCert(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("DOES_NOT_REQUIRE_CLIENT_CERT_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress,
        getServerHandler(false, false, sslProvider, serverSsc.key(), serverSsc.cert()));
    nettyExtension.setUpClient(
        localAddress, getClientHandler(sslProvider, serverSsc.cert(), null, null));

    SSLSession sslSession = setUpSslChannel(nettyExtension.getClientChannel(), serverSsc.cert());
    nettyExtension.assertThatMessagesWork();

    // Verify that the SSL session does not contain any client cert. Note that this SslSession is
    // for the client channel, therefore its local certificates are the remote certificates of the
    // SslSession for the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).isNull();
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testSuccess_CertSignedByOtherCa(SslProvider sslProvider) throws Exception {
    // The self-signed cert of the CA.
    SelfSignedCaCertificate caSsc = SelfSignedCaCertificate.create();
    KeyPair keyPair = getKeyPair();
    X509Certificate serverCert = signKeyPair(caSsc, keyPair, SSL_HOST);
    LocalAddress localAddress = new LocalAddress("CERT_SIGNED_BY_OTHER_CA_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress,
        getServerHandler(
            true,
            false,
            sslProvider,
            keyPair.getPrivate(),
            // Serving both the server cert, and the CA cert
            serverCert,
            caSsc.cert()));
    SelfSignedCaCertificate clientSsc = SelfSignedCaCertificate.create();
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(
            sslProvider,
            // Client trusts the CA cert
            caSsc.cert(),
            clientSsc.key(),
            clientSsc.cert()));

    SSLSession sslSession =
        setUpSslChannel(nettyExtension.getClientChannel(), serverCert, caSsc.cert());
    nettyExtension.assertThatMessagesWork();

    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates())
        .asList()
        .containsExactly(serverCert, caSsc.cert())
        .inOrder();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_requireClientCertificate(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("REQUIRE_CLIENT_CERT_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress,
        getServerHandler(true, false, sslProvider, serverSsc.key(), serverSsc.cert()));
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(
            sslProvider,
            serverSsc.cert(),
            // No client cert/private key used.
            null,
            null));

    // When the server rejects the client during handshake due to lack of client certificate, both
    // should throw exceptions.
    nettyExtension.assertThatServerRootCause().isInstanceOf(SSLHandshakeException.class);
    nettyExtension.assertThatClientRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyExtension.getClientChannel().isActive()).isFalse();
  }

  @ParameterizedTest
  @MethodSource("provideTestCombinations")
  void testFailure_wrongHostnameInCertificate(SslProvider sslProvider) throws Exception {
    SelfSignedCaCertificate serverSsc = SelfSignedCaCertificate.create("wrong.com");
    LocalAddress localAddress = new LocalAddress("WRONG_HOSTNAME_" + sslProvider);

    nettyExtension.setUpServer(
        localAddress,
        getServerHandler(true, false, sslProvider, serverSsc.key(), serverSsc.cert()));
    SelfSignedCaCertificate clientSsc = SelfSignedCaCertificate.create();
    nettyExtension.setUpClient(
        localAddress,
        getClientHandler(sslProvider, serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    // When the client rejects the server cert due to wrong hostname, both the server and the client
    // throw exceptions.
    nettyExtension.assertThatClientRootCause().isInstanceOf(CertificateException.class);
    nettyExtension.assertThatClientRootCause().hasMessageThat().contains(SSL_HOST);
    nettyExtension.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyExtension.getClientChannel().isActive()).isFalse();
  }
}
