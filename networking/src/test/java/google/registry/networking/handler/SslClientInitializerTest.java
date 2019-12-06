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
import static google.registry.networking.handler.SslInitializerTestUtils.verifySslExcpetion;

import com.google.common.collect.ImmutableList;
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
import io.netty.handler.ssl.util.SelfSignedCertificate;
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
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

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
@RunWith(Parameterized.class)
public class SslClientInitializerTest {

  /** Fake host to test if the SSL engine gets the correct peer host. */
  private static final String SSL_HOST = "www.example.tld";

  /** Fake port to test if the SSL engine gets the correct peer port. */
  private static final int SSL_PORT = 12345;

  private static final Function<Channel, String> hostProvider = channel -> SSL_HOST;

  private static final Function<Channel, Integer> portProvider = channel -> SSL_PORT;

  @Rule public NettyRule nettyRule = new NettyRule();

  @Parameter(0)
  public SslProvider sslProvider;

  // We do our best effort to test all available SSL providers.
  @Parameters(name = "{0}")
  public static SslProvider[] data() {
    return OpenSsl.isAvailable()
        ? new SslProvider[] {SslProvider.JDK, SslProvider.OPENSSL}
        : new SslProvider[] {SslProvider.JDK};
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

  @Test
  public void testSuccess_swappedInitializerWithSslHandler() throws Exception {
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

  @Test
  public void testSuccess_nullHost() {
    SslClientInitializer<EmbeddedChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, channel -> null, portProvider, ImmutableList.of(), null, null);
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslClientInitializer);
    // Channel initializer swallows error thrown, and closes the connection.
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testSuccess_nullPort() {
    SslClientInitializer<EmbeddedChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, channel -> null, ImmutableList.of(), null, null);
    EmbeddedChannel channel = new EmbeddedChannel();
    ChannelPipeline pipeline = channel.pipeline();
    pipeline.addLast(sslClientInitializer);
    // Channel initializer swallows error thrown, and closes the connection.
    assertThat(channel.isActive()).isFalse();
  }

  @Test
  public void testFailure_defaultTrustManager_rejectSelfSignedCert() throws Exception {
    SelfSignedCertificate ssc = new SelfSignedCertificate(SSL_HOST);
    LocalAddress localAddress =
        new LocalAddress("DEFAULT_TRUST_MANAGER_REJECT_SELF_SIGNED_CERT_" + sslProvider);
    nettyRule.setUpServer(localAddress, getServerHandler(false, ssc.key(), ssc.cert()));
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(), null, null);
    nettyRule.setUpClient(localAddress, sslClientInitializer);
    // The connection is now terminated, both the client side and the server side should get
    // exceptions.
    nettyRule.assertThatClientRootCause().isInstanceOf(CertPathBuilderException.class);
    nettyRule.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyRule.getClientChannel().isActive()).isFalse();
  }

  @Test
  public void testSuccess_customTrustManager_acceptCertSignedByTrustedCa() throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_ACCEPT_CERT_SIGNED_BY_TRUSTED_CA_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCertificate ssc = new SelfSignedCertificate();
    X509Certificate cert = signKeyPair(ssc, keyPair, SSL_HOST);

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyRule.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyRule.setUpClient(localAddress, sslClientInitializer);

    setUpSslChannel(nettyRule.getClientChannel(), cert);
    nettyRule.assertThatMessagesWork();

    // Verify that the SNI extension is sent during handshake.
    assertThat(sniHostReceived).isEqualTo(SSL_HOST);
  }

  @Test
  public void testFailure_customTrustManager_serverCertExpired() throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_SERVE_CERT_EXPIRED_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCertificate ssc = new SelfSignedCertificate();
    X509Certificate cert =
        signKeyPair(
            ssc,
            keyPair,
            SSL_HOST,
            Date.from(Instant.now().minus(Duration.ofDays(2))),
            Date.from(Instant.now().minus(Duration.ofDays(1))));

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyRule.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyRule.setUpClient(localAddress, sslClientInitializer);

    verifySslExcpetion(
        nettyRule.getClientChannel(),
        channel -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(),
        CertificateExpiredException.class);
  }

  @Test
  public void testFailure_customTrustManager_serverCertNotYetValid() throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_SERVE_CERT_NOT_YET_VALID_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCertificate ssc = new SelfSignedCertificate();
    X509Certificate cert =
        signKeyPair(
            ssc,
            keyPair,
            SSL_HOST,
            Date.from(Instant.now().plus(Duration.ofDays(1))),
            Date.from(Instant.now().plus(Duration.ofDays(2))));

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyRule.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyRule.setUpClient(localAddress, sslClientInitializer);

    verifySslExcpetion(
        nettyRule.getClientChannel(),
        channel -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(),
        CertificateNotYetValidException.class);
  }

  @Test
  public void testSuccess_customTrustManager_acceptSelfSignedCert_clientCertRequired()
      throws Exception {
    LocalAddress localAddress =
        new LocalAddress(
            "CUSTOM_TRUST_MANAGER_ACCEPT_SELF_SIGNED_CERT_CLIENT_CERT_REQUIRED_" + sslProvider);

    SelfSignedCertificate serverSsc = new SelfSignedCertificate(SSL_HOST);
    SelfSignedCertificate clientSsc = new SelfSignedCertificate();

    // Set up the server to require client certificate.
    nettyRule.setUpServer(localAddress, getServerHandler(true, serverSsc.key(), serverSsc.cert()));

    // Set up the client to trust the server certificate and use the client certificate.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider,
            hostProvider,
            portProvider,
            ImmutableList.of(serverSsc.cert()),
            () -> clientSsc.key(),
            () -> ImmutableList.of(clientSsc.cert()));
    nettyRule.setUpClient(localAddress, sslClientInitializer);

    SSLSession sslSession = setUpSslChannel(nettyRule.getClientChannel(), serverSsc.cert());
    nettyRule.assertThatMessagesWork();

    // Verify that the SNI extension is sent during handshake.
    assertThat(sniHostReceived).isEqualTo(SSL_HOST);

    // Verify that the SSL session gets the client cert. Note that this SslSession is for the client
    // channel, therefore its local certificates are the remote certificates of the SslSession for
    // the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @Test
  public void testFailure_customTrustManager_wrongHostnameInCertificate() throws Exception {
    LocalAddress localAddress =
        new LocalAddress("CUSTOM_TRUST_MANAGER_WRONG_HOSTNAME_" + sslProvider);

    // Generate a new key pair.
    KeyPair keyPair = getKeyPair();

    // Generate a self signed certificate, and use it to sign the key pair.
    SelfSignedCertificate ssc = new SelfSignedCertificate();
    X509Certificate cert = signKeyPair(ssc, keyPair, "wrong.com");

    // Set up the server to use the signed cert and private key to perform handshake;
    PrivateKey privateKey = keyPair.getPrivate();
    nettyRule.setUpServer(localAddress, getServerHandler(false, privateKey, cert));

    // Set up the client to trust the self signed cert used to sign the cert that server provides.
    SslClientInitializer<LocalChannel> sslClientInitializer =
        new SslClientInitializer<>(
            sslProvider, hostProvider, portProvider, ImmutableList.of(ssc.cert()), null, null);
    nettyRule.setUpClient(localAddress, sslClientInitializer);

    // When the client rejects the server cert due to wrong hostname, both the client and server
    // should throw exceptions.
    nettyRule.assertThatClientRootCause().isInstanceOf(CertificateException.class);
    nettyRule.assertThatClientRootCause().hasMessageThat().contains(SSL_HOST);
    nettyRule.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyRule.getClientChannel().isActive()).isFalse();
  }
}
