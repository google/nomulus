// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox.handlers;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.monitoring.blackbox.ProbingAction.PROBING_ACTION_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.Protocol;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;
import javax.inject.Singleton;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

/**
 * Adds a client side SSL handler to the channel pipeline.
 *
 * <p> Code is close to unchanged from {@link SslClientInitializer}</p> in proxy, but is modified
 * for revised overall structure of connections, and to accomdate EPP connections </p>
 *
 * <p>This <b>must</b> be the first handler provided for any handler provider list, if it is
 * provided. The type parameter {@code C} is needed so that unit tests can construct this handler
 * that works with {@link EmbeddedChannel};
 */
@Singleton
@Sharable
public class SslClientInitializer<C extends Channel> extends ChannelInitializer<C> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final SslProvider sslProvider;
  private final X509Certificate[] trustedCertificates;
  private final Supplier<PrivateKey> privateKeySupplier;
  private final Supplier<X509Certificate[]> certificateSupplier;


  public SslClientInitializer(SslProvider sslProvider) {
    // null uses the system default trust store.
    //Used for WebWhois, so we don't care about privateKey and certificates, setting them to null
    this(sslProvider, null, null, null);
  }

  public SslClientInitializer(SslProvider sslProvider, Supplier<PrivateKey> privateKeySupplier,
      Supplier<X509Certificate[]> certificateSupplier) {
    //We use the default trust store here as well, setting trustCertificates to null
    this(sslProvider, null, privateKeySupplier, certificateSupplier);
  }

  @VisibleForTesting
  SslClientInitializer(SslProvider sslProvider, X509Certificate[] trustCertificates) {
    this(sslProvider, trustCertificates, null, null);
  }

  private SslClientInitializer(
      SslProvider sslProvider,
      X509Certificate[] trustCertificates,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<X509Certificate[]> certificateSupplier) {
    logger.atInfo().log("Client SSL Provider: %s", sslProvider);

    this.sslProvider = sslProvider;
    this.trustedCertificates = trustCertificates;
    this.privateKeySupplier = privateKeySupplier;
    this.certificateSupplier = certificateSupplier;
  }

  @Override
  protected void initChannel(C channel) throws Exception {
    ProbingAction action = channel.attr(PROBING_ACTION_KEY).get();
    Protocol protocol = action.protocol();

    //Builds SslHandler from Protocol, and based on if we require a privateKey and certificate
    checkNotNull(protocol, "Protocol is not set for channel: %s", channel);
    SslContextBuilder sslContextBuilder =
        SslContextBuilder.forClient()
            .sslProvider(sslProvider)
            .trustManager(trustedCertificates);
    if (privateKeySupplier != null && certificateSupplier != null) {
      sslContextBuilder = sslContextBuilder
          .keyManager(privateKeySupplier.get(), certificateSupplier.get());
    }

    SslHandler sslHandler = sslContextBuilder
        .build()
        .newHandler(channel.alloc(), action.host(), protocol.port());

    // Enable hostname verification.
    SSLEngine sslEngine = sslHandler.engine();
    SSLParameters sslParameters = sslEngine.getSSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslEngine.setSSLParameters(sslParameters);

    channel.pipeline().addLast(sslHandler);
  }
}

