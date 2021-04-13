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

import static google.registry.util.ResourceUtils.readResourceBytes;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.networking.handler.SslServerInitializer;
import google.registry.proxy.HttpsRelayProtocolModule.HttpsRelayProtocol;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.handler.EppServiceHandler;
import google.registry.proxy.handler.FrontendMetricsHandler;
import google.registry.proxy.handler.ProxyProtocolHandler;
import google.registry.proxy.handler.QuotaHandler.EppQuotaHandler;
import google.registry.proxy.handler.RelayHandler.FullHttpRequestRelayHandler;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.proxy.quota.QuotaConfig;
import google.registry.proxy.quota.QuotaManager;
import google.registry.proxy.quota.TokenStore;
import google.registry.util.Clock;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** A module that provides the {@link FrontendProtocol} used for epp protocol. */
@Module
public final class EppProtocolModule {

  private EppProtocolModule() {}

  /** Dagger qualifier to provide epp protocol related handlers and other bindings. */
  @Qualifier
  public @interface EppProtocol {}

  private static final String PROTOCOL_NAME = "epp";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideProtocol(
      ProxyConfig config,
      @EppProtocol int eppPort,
      @EppProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders,
      @HttpsRelayProtocol BackendProtocol.Builder backendProtocolBuilder) {
    return Protocol.frontendBuilder()
        .name(PROTOCOL_NAME)
        .port(eppPort)
        .handlerProviders(handlerProviders)
        .relayProtocol(backendProtocolBuilder.host(config.epp.relayHost).build())
        .build();
  }

  @Provides
  @EppProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      Provider<ProxyProtocolHandler> proxyProtocolHandlerProvider,
      @EppProtocol Provider<SslServerInitializer<NioSocketChannel>> sslServerInitializerProvider,
      @EppProtocol Provider<ReadTimeoutHandler> readTimeoutHandlerProvider,
      Provider<LengthFieldBasedFrameDecoder> lengthFieldBasedFrameDecoderProvider,
      Provider<LengthFieldPrepender> lengthFieldPrependerProvider,
      Provider<EppServiceHandler> eppServiceHandlerProvider,
      Provider<FrontendMetricsHandler> frontendMetricsHandlerProvider,
      Provider<EppQuotaHandler> eppQuotaHandlerProvider,
      Provider<FullHttpRequestRelayHandler> relayHandlerProvider) {
    return ImmutableList.of(
        proxyProtocolHandlerProvider,
        sslServerInitializerProvider,
        readTimeoutHandlerProvider,
        lengthFieldBasedFrameDecoderProvider,
        lengthFieldPrependerProvider,
        eppServiceHandlerProvider,
        frontendMetricsHandlerProvider,
        eppQuotaHandlerProvider,
        relayHandlerProvider);
  }

  @Provides
  static LengthFieldBasedFrameDecoder provideLengthFieldBasedFrameDecoder(ProxyConfig config) {
    return new LengthFieldBasedFrameDecoder(
        // Max message length.
        config.epp.maxMessageLengthBytes,
        // Header field location offset.
        0,
        // Header field length.
        config.epp.headerLengthBytes,
        // Adjustment applied to the header field value in order to obtain message length.
        -config.epp.headerLengthBytes,
        // Initial bytes to strip (i. e. strip the length header).
        config.epp.headerLengthBytes);
  }

  @Singleton
  @Provides
  static LengthFieldPrepender provideLengthFieldPrepender(ProxyConfig config) {
    return new LengthFieldPrepender(
        // Header field length.
        config.epp.headerLengthBytes,
        // Length includes header field length.
        true);
  }

  @Provides
  @EppProtocol
  static ReadTimeoutHandler provideReadTimeoutHandler(ProxyConfig config) {
    return new ReadTimeoutHandler(config.epp.readTimeoutSeconds);
  }

  @Singleton
  @Provides
  @Named("hello")
  static byte[] provideHelloBytes() {
    try {
      return readResourceBytes(EppProtocolModule.class, "hello.xml").read();
    } catch (IOException e) {
      throw new RuntimeException("Cannot read EPP <hello> message file.", e);
    }
  }

  @Provides
  static EppServiceHandler provideEppServiceHandler(
      @Named("accessToken") Supplier<String> accessTokenSupplier,
      @Named("hello") byte[] helloBytes,
      FrontendMetrics metrics,
      ProxyConfig config) {
    return new EppServiceHandler(
        config.epp.relayHost, config.epp.relayPath, accessTokenSupplier, helloBytes, metrics);
  }

  @Singleton
  @Provides
  @EppProtocol
  static SslServerInitializer<NioSocketChannel> provideSslServerInitializer(
      SslProvider sslProvider,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<ImmutableList<X509Certificate>> certificatesSupplier) {
    return new SslServerInitializer<>(
        true, false, sslProvider, privateKeySupplier, certificatesSupplier);
  }

  @Provides
  @EppProtocol
  static TokenStore provideTokenStore(
      ProxyConfig config, ScheduledExecutorService refreshExecutor, Clock clock) {
    return new TokenStore(new QuotaConfig(config.epp.quota, PROTOCOL_NAME), refreshExecutor, clock);
  }

  @Provides
  @Singleton
  @EppProtocol
  static QuotaManager provideQuotaManager(
      @EppProtocol TokenStore tokenStore, ExecutorService executorService) {
    return new QuotaManager(tokenStore, executorService);
  }
}
