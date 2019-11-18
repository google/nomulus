// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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
import dagger.multibindings.IntoSet;
import google.registry.networking.handler.SslServerInitializer;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.handler.WebWhoisRedirectHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.ssl.SslProvider;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** A module that provides the {@link FrontendProtocol}s to redirect HTTP(S) web WHOIS requests. */
@Module
public class WebWhoisProtocolsModule {

  /** Dagger qualifier to provide HTTP whois protocol related handlers and other bindings. */
  @Qualifier
  @interface HttpWhoisProtocol {}

  /** Dagger qualifier to provide HTTPS whois protocol related handlers and other bindings. */
  @Qualifier
  @interface HttpsWhoisProtocol {}

  private static final String HTTP_PROTOCOL_NAME = "whois_http";
  private static final String HTTPS_PROTOCOL_NAME = "whois_https";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideHttpWhoisProtocol(
      @HttpWhoisProtocol int httpWhoisPort,
      @HttpWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return google.registry.proxy.Protocol.frontendBuilder()
        .name(HTTP_PROTOCOL_NAME)
        .port(httpWhoisPort)
        .hasBackend(false)
        .handlerProviders(handlerProviders)
        .build();
  }

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideHttpsWhoisProtocol(
      @HttpsWhoisProtocol int httpsWhoisPort,
      @HttpsWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return google.registry.proxy.Protocol.frontendBuilder()
        .name(HTTPS_PROTOCOL_NAME)
        .port(httpsWhoisPort)
        .hasBackend(false)
        .handlerProviders(handlerProviders)
        .build();
  }

  @Provides
  @HttpWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpWhoisHandlerProviders(
      Provider<HttpServerCodec> httpServerCodecProvider,
      Provider<HttpServerExpectContinueHandler> httpServerExpectContinueHandlerProvider,
      @HttpWhoisProtocol Provider<WebWhoisRedirectHandler> webWhoisRedirectHandlerProvides) {
    return ImmutableList.of(
        httpServerCodecProvider,
        httpServerExpectContinueHandlerProvider,
        webWhoisRedirectHandlerProvides);
  }

  @Provides
  @HttpsWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpsWhoisHandlerProviders(
      @HttpsWhoisProtocol
          Provider<SslServerInitializer<NioSocketChannel>> sslServerInitializerProvider,
      Provider<HttpServerCodec> httpServerCodecProvider,
      Provider<HttpServerExpectContinueHandler> httpServerExpectContinueHandlerProvider,
      @HttpsWhoisProtocol Provider<WebWhoisRedirectHandler> webWhoisRedirectHandlerProvides) {
    return ImmutableList.of(
        sslServerInitializerProvider,
        httpServerCodecProvider,
        httpServerExpectContinueHandlerProvider,
        webWhoisRedirectHandlerProvides);
  }

  @Provides
  static HttpServerCodec provideHttpServerCodec() {
    return new HttpServerCodec();
  }

  @Provides
  @HttpWhoisProtocol
  static WebWhoisRedirectHandler provideHttpRedirectHandler(
      google.registry.proxy.ProxyConfig config) {
    return new WebWhoisRedirectHandler(false, config.webWhois.redirectHost);
  }

  @Provides
  @HttpsWhoisProtocol
  static WebWhoisRedirectHandler provideHttpsRedirectHandler(
      google.registry.proxy.ProxyConfig config) {
    return new WebWhoisRedirectHandler(true, config.webWhois.redirectHost);
  }

  @Provides
  static HttpServerExpectContinueHandler provideHttpServerExpectContinueHandler() {
    return new HttpServerExpectContinueHandler();
  }

  @Singleton
  @Provides
  @HttpsWhoisProtocol
  static SslServerInitializer<NioSocketChannel> provideSslServerInitializer(
      SslProvider sslProvider,
      Supplier<PrivateKey> privateKeySupplier,
      Supplier<X509Certificate[]> certificatesSupplier) {
    return new SslServerInitializer<>(false, sslProvider, privateKeySupplier, certificatesSupplier);
  }
}
