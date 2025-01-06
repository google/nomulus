// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy;

import static google.registry.networking.handler.SslClientInitializer.createSslClientInitializerWithSystemTrustStore;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import google.registry.networking.handler.SslClientInitializer;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.handler.BackendMetricsHandler;
import google.registry.proxy.handler.RelayHandler.FullHttpResponseRelayHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.cert.X509Certificate;
import javax.annotation.Nullable;
import javax.inject.Provider;
import javax.inject.Qualifier;

/**
 * Module that provides a {@link BackendProtocol.Builder} for HTTP(S) protocol.
 *
 * <p>Only a builder is provided because the client protocol itself depends on the remote host
 * address, which is provided in the server protocol module that relays to this client protocol
 * module, e.g., {@link WhoisProtocolModule}.
 *
 * <p>The protocol can be configured without TLS. In this case, the remote host has to be
 * "localhost". Plan HTTP is only expected to be used when communication with Nomulus is via local
 * loopback (for security reasons), as is the case when both the proxy and Nomulus container live in
 * the same Kubernetes pod.
 *
 * @see <a href=https://kubernetes.io/docs/concepts/services-networking/>The Kubernetes network
 *     model</a>
 */
@Module
public class HttpsRelayProtocolModule {

  /** Dagger qualifier to provide https relay protocol related handlers and other bindings. */
  @Qualifier
  public @interface HttpsRelayProtocol {}

  private static final String PROTOCOL_NAME = "https_relay";

  @Provides
  @HttpsRelayProtocol
  static BackendProtocol.Builder provideProtocolBuilder(
      ProxyConfig config,
      @HttpsRelayProtocol boolean localRelay,
      @HttpsRelayProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.backendBuilder()
        .name(PROTOCOL_NAME)
        .isLocal(localRelay)
        .port(localRelay ? config.httpsRelay.localPort : config.httpsRelay.port)
        .handlerProviders(handlerProviders);
  }

  @Provides
  @HttpsRelayProtocol
  static SslClientInitializer<NioSocketChannel> provideSslClientInitializer(
      SslProvider sslProvider) {
    return createSslClientInitializerWithSystemTrustStore(
        sslProvider,
        channel -> ((BackendProtocol) channel.attr(Protocol.PROTOCOL_KEY).get()).host(),
        channel -> channel.attr(Protocol.PROTOCOL_KEY).get().port());
  }

  @Provides
  @HttpsRelayProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      @HttpsRelayProtocol boolean localRelay,
      @HttpsRelayProtocol
          Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<BackendMetricsHandler> backendMetricsHandlerProvider,
      Provider<LoggingHandler> loggingHandlerProvider,
      Provider<FullHttpResponseRelayHandler> relayHandlerProvider) {
    ImmutableList.Builder<Provider<? extends ChannelHandler>> builder =
        new ImmutableList.Builder<>();
    if (!localRelay) {
      builder.add(sslClientInitializerProvider);
    }
    builder.add(httpClientCodecProvider);
    builder.add(httpObjectAggregatorProvider);
    builder.add(backendMetricsHandlerProvider);
    builder.add(loggingHandlerProvider);
    builder.add(relayHandlerProvider);
    return builder.build();
  }

  @Provides
  static HttpClientCodec provideHttpClientCodec() {
    return new HttpClientCodec();
  }

  @Provides
  static HttpObjectAggregator provideHttpObjectAggregator(ProxyConfig config) {
    return new HttpObjectAggregator(config.httpsRelay.maxMessageLengthBytes);
  }

  @Nullable
  @Provides
  @HttpsRelayProtocol
  public static X509Certificate[] provideTrustedCertificates() {
    // null uses the system default trust store.
    return null;
  }
}
