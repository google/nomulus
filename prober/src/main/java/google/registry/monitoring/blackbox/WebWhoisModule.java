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

package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;

import google.registry.monitoring.blackbox.handlers.MessageHandler;
import google.registry.monitoring.blackbox.handlers.WebWhoisMessageHandler;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer;
import google.registry.monitoring.blackbox.handlers.WebWhoisActionHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/** A module that provides the {@link Protocol}s to send HTTP(S) web WHOIS requests. */
@Module
public class WebWhoisModule {

  final static String DOMAIN_SUFFIX = "whois.nic.";


  /** Dagger qualifier to provide HTTP whois protocol related handlers and other bindings. */
  @Qualifier
  @interface HttpWhoisProtocol {}

  /** Dagger qualifier to provide HTTPS whois protocol related handlers and other bindings. */
  @Qualifier
  @interface HttpsWhoisProtocol {}

  /** Dagger qualifier to provide any WebWhois related bindings. */
  @Qualifier
  public @interface WebWhoisProtocol {}



  private static final String HTTP_PROTOCOL_NAME = "whois_http";
  private static final String HTTPS_PROTOCOL_NAME = "whois_https";


  @Provides
  @HttpWhoisProtocol
  static ProbingStep<NioSocketChannel> provideHttpWhoisProbingSequence(
      @HttpWhoisProtocol Protocol httpWhoisProtocol) {
    return new ProbingStepWeb<>(httpWhoisProtocol);
  }

  @Provides
  @HttpsWhoisProtocol
  static ProbingStep<NioSocketChannel> provideHttpsWhoisProbingStep(
      @HttpsWhoisProtocol Protocol httpsWhoisProtocol) {
    return new ProbingStepWeb<>(httpsWhoisProtocol);
  }


  @Singleton
  @Provides
  @HttpWhoisProtocol
  static Protocol provideHttpWhoisProtocol(
      @HttpWhoisProtocol int httpWhoisPort,
      @HttpWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .name(HTTP_PROTOCOL_NAME)
        .port(httpWhoisPort)
        .handlerProviders(handlerProviders)
        .persistentConnection(false)
        .build();
  }

  @Singleton
  @Provides
  @IntoSet
  static Protocol provideHttpProtocolForSet(
      @HttpWhoisProtocol int httpWhoisPort,
      @HttpWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .name(HTTP_PROTOCOL_NAME)
        .port(httpWhoisPort)
        .handlerProviders(handlerProviders)
        .persistentConnection(false)
        .build();
  }


  @Singleton
  @Provides
  @HttpsWhoisProtocol
  static Protocol provideHttpsWhoisProtocol(
      @HttpsWhoisProtocol int httpsWhoisPort,
      @HttpsWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .name(HTTPS_PROTOCOL_NAME)
        .port(httpsWhoisPort)
        .handlerProviders(handlerProviders)
        .persistentConnection(false)
        .build();
  }

  @Singleton
  @Provides
  @IntoSet
  static Protocol provideHttpsProtocolForSet(
      @HttpsWhoisProtocol int httpsWhoisPort,
      @HttpsWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .name(HTTPS_PROTOCOL_NAME)
        .port(httpsWhoisPort)
        .handlerProviders(handlerProviders)
        .persistentConnection(false)
        .build();
  }

  @Provides
  @WebWhoisProtocol
  String provideHttpWhoisHost() {
    return "app";
  }


  @Provides
  @HttpWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpWhoisHandlerProviders(
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      @WebWhoisProtocol Provider<MessageHandler> messageHandlerProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return ImmutableList.of(
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        messageHandlerProvider,
        webWhoisActionHandlerProvider);
  }

  @Provides
  @HttpsWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpsWhoisHandlerProviders(
      @HttpsWhoisProtocol Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      @WebWhoisProtocol Provider<MessageHandler> messageHandlerProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return ImmutableList.of(
        sslClientInitializerProvider,
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        messageHandlerProvider,
        webWhoisActionHandlerProvider);
  }


  @Provides
  @WebWhoisProtocol
  static MessageHandler provideMessageHandler() {
    return new WebWhoisMessageHandler();
  }

  @Provides
  static HttpClientCodec provideHttpClientCodec() {
    return new HttpClientCodec();
  }

  @Provides
  static HttpObjectAggregator provideHttpObjectAggregator() {
    return new HttpObjectAggregator(1048576);
  }

  @Provides
  static SslProvider provideSslProvider() {
    // Prefer OpenSSL.
    return OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
  }

  @Provides
  @HttpsWhoisProtocol
  static SslClientInitializer<NioSocketChannel> provideSslClientInitializer(SslProvider sslProvider) {
    return new SslClientInitializer<>(sslProvider);
  }


}