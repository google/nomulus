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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Singleton;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Adds a client side SSL handler to the channel pipeline.
 *
 * <p>This <b>must</b> be the first handler provided for any handler provider list, if it is
 * provided. The type parameter {@code C} is needed so that unit tests can construct this handler
 * that works with {@link EmbeddedChannel};
 */
@Singleton
@Sharable
public class SslClientInitializer<C extends Channel> extends ChannelInitializer<C> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Function<Channel, String> hostProvider;
  private final Function<Channel, Integer> portProvider;
  private final SslProvider sslProvider;
  private final ImmutableList<X509Certificate> trustedCertificates;
  // The following two suppliers only need be none-null when client authentication is required.
  private final Supplier<PrivateKey> privateKeySupplier;
  private final Supplier<ImmutableList<X509Certificate>> certificateChainSupplier;

  public static SslClientInitializer<NioSocketChannel>
      createSslClientInitializerWithSystemTrustStore(
          SslProvider sslProvider,
          Function<Channel, String> hostProvider,
          Function<Channel, Integer> portProvider) {
    return new SslClientInitializer<>(sslProvider, hostProvider, portProvider, null, null, null);
  }

  public static SslClientInitializer<NioSocketChannel>
      createSslClientInitializerWithSystemTrustStoreAndClientAuthentication(
          SslProvider sslProvider,
          Function<Channel, String> hostProvider,
          Function<Channel, Integer> portProvider,
          Supplier<PrivateKey> privateKeySupplier,
          Supplier<ImmutableList<X509Certificate>> certificateChainSupplier) {
    return new SslClientInitializer<>(
        sslProvider,
        hostProvider,
        portProvider,
        ImmutableList.of(),
        privateKeySupplier,
        certificateChainSupplier);
  }

  @VisibleForTesting
  SslClientInitializer(
      SslProvider sslProvider,
      Function<Channel, String> hostProvider,
      Function<Channel, Integer> portProvider,
      ImmutableList<X509Certificate> trustedCertificates,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<ImmutableList<X509Certificate>> certificateChainSupplier) {
    logger.atInfo().log("Client SSL Provider: %s", sslProvider);
    this.sslProvider = sslProvider;
    this.hostProvider = hostProvider;
    this.portProvider = portProvider;
    this.trustedCertificates = trustedCertificates;
    this.privateKeySupplier = privateKeySupplier;
    this.certificateChainSupplier = certificateChainSupplier;
  }

  @Override
  protected void initChannel(C channel) throws Exception {
    checkNotNull(hostProvider.apply(channel), "Cannot obtain SSL host for channel: %s", channel);
    checkNotNull(portProvider.apply(channel), "Cannot obtain SSL port for channel: %s", channel);

    SslContextBuilder sslContextBuilder =
        SslContextBuilder.forClient()
            .sslProvider(sslProvider)
            .trustManager(
                trustedCertificates == null || trustedCertificates.isEmpty()
                    ? null
                    : trustedCertificates.toArray(new X509Certificate[0]));

    if (privateKeySupplier != null && certificateChainSupplier != null) {
      sslContextBuilder.keyManager(
          privateKeySupplier.get(), certificateChainSupplier.get().toArray(new X509Certificate[0]));
    }

    SslHandler sslHandler =
        sslContextBuilder
            .build()
            .newHandler(channel.alloc(), hostProvider.apply(channel), portProvider.apply(channel));

    // Enable hostname verification.
    SSLEngine sslEngine = sslHandler.engine();
    SSLParameters sslParameters = sslEngine.getSSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslEngine.setSSLParameters(sslParameters);

    channel.pipeline().addLast(sslHandler);
  }
}
