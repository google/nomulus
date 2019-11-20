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

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
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
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

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
@RunWith(Parameterized.class)
public class SslServerInitializerTest {

  /** Fake host to test if the SSL engine gets the correct peer host. */
  private static final String SSL_HOST = "www.example.tld";

  /** Fake port to test if the SSL engine gets the correct peer port. */
  private static final int SSL_PORT = 12345;

  @Rule public NettyRule nettyRule = new NettyRule();

  @Parameter(0)
  public SslProvider sslProvider;

  // We do our best effort to test all available SSL providers.
  @Parameters(name = "{0}")
  public static SslProvider[] data() {
    return OpenSsl.isAvailable()
        ? new SslProvider[] {SslProvider.OPENSSL, SslProvider.JDK}
        : new SslProvider[] {SslProvider.JDK};
  }

  private ChannelHandler getServerHandler(
      boolean requireClientCert, PrivateKey privateKey, X509Certificate... certificates) {
    return new SslServerInitializer<LocalChannel>(
        requireClientCert,
        sslProvider,
        Suppliers.ofInstance(privateKey),
        Suppliers.ofInstance(ImmutableList.copyOf(certificates)));
  }

  private ChannelHandler getServerHandler(PrivateKey privateKey, X509Certificate... certificates) {
    return getServerHandler(true, privateKey, certificates);
  }

  private ChannelHandler getClientHandler(
      X509Certificate trustedCertificate, PrivateKey privateKey, X509Certificate certificate) {
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

  @Test
  public void testSuccess_swappedInitializerWithSslHandler() throws Exception {
    SelfSignedCertificate ssc = new SelfSignedCertificate(SSL_HOST);
    SslServerInitializer<EmbeddedChannel> sslServerInitializer =
        new SslServerInitializer<>(
            true,
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

  @Test
  public void testSuccess_trustAnyClientCert() throws Exception {
    SelfSignedCertificate serverSsc = new SelfSignedCertificate(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("TRUST_ANY_CLIENT_CERT_" + sslProvider);

    nettyRule.setUpServer(localAddress, getServerHandler(serverSsc.key(), serverSsc.cert()));
    SelfSignedCertificate clientSsc = new SelfSignedCertificate();
    nettyRule.setUpClient(
        localAddress, getClientHandler(serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    SSLSession sslSession = setUpSslChannel(nettyRule.getChannel(), serverSsc.cert());
    nettyRule.assertThatMessagesWork();

    // Verify that the SSL session gets the client cert. Note that this SslSession is for the client
    // channel, therefore its local certificates are the remote certificates of the SslSession for
    // the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @Test
  public void testSuccess_doesNotRequireClientCert() throws Exception {
    SelfSignedCertificate serverSsc = new SelfSignedCertificate(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("DOES_NOT_REQUIRE_CLIENT_CERT_" + sslProvider);

    nettyRule.setUpServer(localAddress, getServerHandler(false, serverSsc.key(), serverSsc.cert()));
    nettyRule.setUpClient(localAddress, getClientHandler(serverSsc.cert(), null, null));

    SSLSession sslSession = setUpSslChannel(nettyRule.getChannel(), serverSsc.cert());
    nettyRule.assertThatMessagesWork();

    // Verify that the SSL session does not contain any client cert. Note that this SslSession is
    // for the client channel, therefore its local certificates are the remote certificates of the
    // SslSession for the server channel, and vice versa.
    assertThat(sslSession.getLocalCertificates()).isNull();
    assertThat(sslSession.getPeerCertificates()).asList().containsExactly(serverSsc.cert());
  }

  @Test
  public void testSuccess_CertSignedByOtherCA() throws Exception {
    // The self-signed cert of the CA.
    SelfSignedCertificate caSsc = new SelfSignedCertificate();
    KeyPair keyPair = getKeyPair();
    X509Certificate serverCert = signKeyPair(caSsc, keyPair, SSL_HOST);
    LocalAddress localAddress = new LocalAddress("CERT_SIGNED_BY_OTHER_CA_" + sslProvider);

    nettyRule.setUpServer(
        localAddress,
        getServerHandler(
            keyPair.getPrivate(),
            // Serving both the server cert, and the CA cert
            serverCert,
            caSsc.cert()));
    SelfSignedCertificate clientSsc = new SelfSignedCertificate();
    nettyRule.setUpClient(
        localAddress,
        getClientHandler(
            // Client trusts the CA cert
            caSsc.cert(), clientSsc.key(), clientSsc.cert()));

    SSLSession sslSession = setUpSslChannel(nettyRule.getChannel(), serverCert, caSsc.cert());
    nettyRule.assertThatMessagesWork();

    assertThat(sslSession.getLocalCertificates()).asList().containsExactly(clientSsc.cert());
    assertThat(sslSession.getPeerCertificates())
        .asList()
        .containsExactly(serverCert, caSsc.cert())
        .inOrder();
  }

  @Test
  public void testFailure_requireClientCertificate() throws Exception {
    SelfSignedCertificate serverSsc = new SelfSignedCertificate(SSL_HOST);
    LocalAddress localAddress = new LocalAddress("REQUIRE_CLIENT_CERT_" + sslProvider);

    nettyRule.setUpServer(localAddress, getServerHandler(serverSsc.key(), serverSsc.cert()));
    nettyRule.setUpClient(
        localAddress,
        getClientHandler(
            serverSsc.cert(),
            // No client cert/private key used.
            null,
            null));

    // When the server rejects the client during handshake due to lack of client certificate, both
    // should throw exceptions.
    nettyRule.assertThatServerRootCause().isInstanceOf(SSLHandshakeException.class);
    nettyRule.assertThatClientRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyRule.getChannel().isActive()).isFalse();
  }

  @Test
  public void testFailure_wrongHostnameInCertificate() throws Exception {
    SelfSignedCertificate serverSsc = new SelfSignedCertificate("wrong.com");
    LocalAddress localAddress = new LocalAddress("WRONG_HOSTNAME_" + sslProvider);

    nettyRule.setUpServer(localAddress, getServerHandler(serverSsc.key(), serverSsc.cert()));
    SelfSignedCertificate clientSsc = new SelfSignedCertificate();
    nettyRule.setUpClient(
        localAddress, getClientHandler(serverSsc.cert(), clientSsc.key(), clientSsc.cert()));

    // When the client rejects the server cert due to wrong hostname, both the server and the client
    // throw exceptions.
    nettyRule.assertThatClientRootCause().isInstanceOf(CertificateException.class);
    nettyRule.assertThatClientRootCause().hasMessageThat().contains(SSL_HOST);
    nettyRule.assertThatServerRootCause().isInstanceOf(SSLException.class);
    assertThat(nettyRule.getChannel().isActive()).isFalse();
  }
}
