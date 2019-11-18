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

package google.registry.proxy;

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
 * Module that provides a {@link BackendProtocol.Builder} for HTTPS protocol.
 *
 * <p>Only a builder is provided because the client protocol itself depends on the remote host
 * address, which is provided in the server protocol module that relays to this client protocol
 * module, e. g. {@link WhoisProtocolModule}.
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
      @HttpsRelayProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.backendBuilder()
        .name(PROTOCOL_NAME)
        .port(config.httpsRelay.port)
        .handlerProviders(handlerProviders);
  }

  @Provides
  @HttpsRelayProtocol
  static SslClientInitializer<NioSocketChannel> provideSslClientInitializer(
      SslProvider sslProvider) {
    return new SslClientInitializer<>(
        sslProvider,
        channel -> ((BackendProtocol) channel.attr(Protocol.PROTOCOL_KEY).get()).host(),
        channel -> channel.attr(Protocol.PROTOCOL_KEY).get().port());
  }

  @Provides
  @HttpsRelayProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      @HttpsRelayProtocol
          Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<BackendMetricsHandler> backendMetricsHandlerProvider,
      Provider<LoggingHandler> loggingHandlerProvider,
      Provider<FullHttpResponseRelayHandler> relayHandlerProvider) {
    return ImmutableList.of(
        sslClientInitializerProvider,
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        backendMetricsHandlerProvider,
        loggingHandlerProvider,
        relayHandlerProvider);
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
