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

package google.registry.proxy.handler;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.proxy.Protocol.BackendProtocol;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.cert.X509Certificate;
import javax.inject.Inject;
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

  private final SslProvider sslProvider;
  private final X509Certificate[] trustedCertificates;

  @Inject
  public SslClientInitializer(SslProvider sslProvider) {
    // null uses the system default trust store.
    this(sslProvider, null);
  }

  @VisibleForTesting
  SslClientInitializer(SslProvider sslProvider, X509Certificate[] trustCertificates) {
    logger.atInfo().log("Client SSL Provider: %s", sslProvider);
    this.sslProvider = sslProvider;
    this.trustedCertificates = trustCertificates;
  }

  @Override
  protected void initChannel(C channel) throws Exception {
    BackendProtocol protocol = (BackendProtocol) channel.attr(PROTOCOL_KEY).get();
    checkNotNull(protocol, "Protocol is not set for channel: %s", channel);
    SslHandler sslHandler =
        SslContextBuilder.forClient()
            .sslProvider(sslProvider)
            .trustManager(trustedCertificates)
            .build()
            .newHandler(channel.alloc(), protocol.host(), protocol.port());

    // Enable hostname verification.
    SSLEngine sslEngine = sslHandler.engine();
    SSLParameters sslParameters = sslEngine.getSSLParameters();
    sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
    sslEngine.setSSLParameters(sslParameters);

    channel.pipeline().addLast(sslHandler);
  }
}
