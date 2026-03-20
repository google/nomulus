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

package google.registry.eppserver;

import static google.registry.util.ResourceUtils.readResourceBytes;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.config.RegistryConfig.Config;
import google.registry.config.RegistryConfigSettings;
import google.registry.eppserver.Protocol.FrontendProtocol;
import google.registry.eppserver.handler.EppProxyProtocolHandler;
import google.registry.eppserver.handler.EppServiceHandler;
import google.registry.eppserver.quota.QuotaManager;
import google.registry.networking.handler.SslServerInitializer;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.timeout.ReadTimeoutHandler;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.function.Supplier;
import redis.clients.jedis.UnifiedJedis;

/** A module that provides the {@link FrontendProtocol} used for epp protocol. */
@Module
public final class EppProtocolModule {

  private EppProtocolModule() {}

  @Qualifier
  public @interface EppProtocol {}

  @Qualifier
  public @interface IpQuota {}

  @Qualifier
  public @interface CommandQuota {}

  private static final String PROTOCOL_NAME = "epp";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideProtocol(
      @EppProtocol int eppPort,
      @EppProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.frontendBuilder()
        .name(PROTOCOL_NAME)
        .port(eppPort)
        .handlerProviders(handlerProviders)
        .hasBackend(false)
        .build();
  }

  @Provides
  @EppProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      Provider<EppProxyProtocolHandler> proxyProtocolHandlerProvider,
      @EppProtocol Provider<SslServerInitializer<NioSocketChannel>> sslServerInitializerProvider,
      @EppProtocol Provider<ReadTimeoutHandler> readTimeoutHandlerProvider,
      Provider<LengthFieldBasedFrameDecoder> lengthFieldBasedFrameDecoderProvider,
      Provider<LengthFieldPrepender> lengthFieldPrependerProvider,
      Provider<EppServiceHandler> eppServiceHandlerProvider) {
    return ImmutableList.of(
        proxyProtocolHandlerProvider,
        sslServerInitializerProvider,
        readTimeoutHandlerProvider,
        lengthFieldBasedFrameDecoderProvider,
        lengthFieldPrependerProvider,
        eppServiceHandlerProvider);
  }

  @Provides
  static LengthFieldBasedFrameDecoder provideLengthFieldBasedFrameDecoder(
      @Config("eppServerMaxMessageLengthBytes") int maxMessageLengthBytes,
      @Config("eppServerHeaderLengthBytes") int headerLengthBytes) {
    return new LengthFieldBasedFrameDecoder(
        maxMessageLengthBytes, 0, headerLengthBytes, -headerLengthBytes, headerLengthBytes);
  }

  @Singleton
  @Provides
  static LengthFieldPrepender provideLengthFieldPrepender(
      @Config("eppServerHeaderLengthBytes") int headerLengthBytes) {
    return new LengthFieldPrepender(headerLengthBytes, true);
  }

  @Provides
  @EppProtocol
  static ReadTimeoutHandler provideReadTimeoutHandler(
      @Config("eppServerReadTimeoutSeconds") int readTimeoutSeconds) {
    return new ReadTimeoutHandler(readTimeoutSeconds);
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
  @Singleton
  @CommandQuota
  static QuotaManager provideCommandQuotaManager(
      @Config("eppServerQuota") RegistryConfigSettings.Quota quota, Optional<UnifiedJedis> jedis) {
    return new QuotaManager(quota, jedis.orElse(null), "command");
  }
}
