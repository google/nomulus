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

import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import google.registry.monitoring.blackbox.tokens.Token;
import google.registry.monitoring.blackbox.handlers.WebWhoisMessageHandler;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer;
import google.registry.monitoring.blackbox.handlers.WebWhoisActionHandler;
import google.registry.monitoring.blackbox.tokens.WebWhoisToken;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandler;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import java.util.List;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;
import org.joda.time.Duration;

/** A module that provides the {@link Protocol}s to send HTTP(S) web WHOIS requests. */
@Module
public class WebWhoisModule {

  private final int httpWhoIsPort = 80;
  private final int httpsWhoIsPort = 443;
  private static final String HTTP_PROTOCOL_NAME = "whois_http";
  private static final String HTTPS_PROTOCOL_NAME = "whois_https";
  private static final String DOMAIN_PREFIX = "whois.nic.";

  /** Standard length of messages used by Proxy. Equates to 0.5 MB. */
  private static final int maximumMessageLengthBytes = 512 * 1024;


  /** Dagger qualifier to provide HTTP whois protocol related handlers and other bindings. */
  @Qualifier
  public @interface HttpWhoisProtocol {}

  /** Dagger qualifier to provide HTTPS whois protocol related handlers and other bindings. */
  @Qualifier
  public @interface HttpsWhoisProtocol {}

  /** Dagger qualifier to provide any WebWhois related bindings. */
  @Qualifier
  public @interface WebWhoisProtocol {}

  /** {@link Provides} standard WebWhois sequence. */
  @Provides
  @WebWhoisProtocol
  ProbingSequence provideWebWhoisSequence(
      @WebWhoisProtocol ProbingStep.Builder probingStepBuilder,
      WebWhoisToken webWhoisToken,
      @WebWhoisProtocol Bootstrap bootstrap) {

    return new ProbingSequence.Builder()
        .addToken(webWhoisToken)
        .setBootstrap(bootstrap)
        .addStep(probingStepBuilder)
        .build();
  }


  /** {@link Provides} only step used in WebWhois sequence. */
  @Provides
  @WebWhoisProtocol
  static ProbingStep.Builder provideWebWhoisStepBuilder(
      @HttpWhoisProtocol Protocol httpWhoisProtocol,
      HttpRequestMessage messageTemplate,
      Duration duration) {

    return ProbingStep.builder()
        .setProtocol(httpWhoisProtocol)
        .setMessageTemplate(messageTemplate)
        .setDuration(duration);
  }

  /** {@link Provides} the {@link Protocol} that corresponds to http connection. */
  @Singleton
  @Provides
  @HttpWhoisProtocol
  static Protocol provideHttpWhoisProtocol(
      @HttpWhoisProtocol int httpWhoisPort,
      @HttpWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .setName(HTTP_PROTOCOL_NAME)
        .setPort(httpWhoisPort)
        .setHandlerProviders(handlerProviders)
        .setPersistentConnection(false)
        .build();
  }

  /** {@link Provides} the {@link Protocol} that corresponds to https connection. */
  @Singleton
  @Provides
  @HttpsWhoisProtocol
  static Protocol provideHttpsWhoisProtocol(
      @HttpsWhoisProtocol int httpsWhoisPort,
      @HttpsWhoisProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.builder()
        .setName(HTTPS_PROTOCOL_NAME)
        .setPort(httpsWhoisPort)
        .setHandlerProviders(handlerProviders)
        .setPersistentConnection(false)
        .build();
  }

  /** {@link Provides} the prefix where we probe: "prefix.tld". */
  @Provides
  @Named("Web-WHOIS-Prefix")
  String provideWhoisPrefix() {
    return DOMAIN_PREFIX;
  }


  /** {@link Provides} the list of providers of {@link ChannelHandler}s that are used for http protocol. */
  @Provides
  @HttpWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpWhoisHandlerProviders(
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<WebWhoisMessageHandler> messageHandlerProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return ImmutableList.of(
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        messageHandlerProvider,
        webWhoisActionHandlerProvider);
  }

  /** {@link Provides} the list of providers of {@link ChannelHandler}s that are used for https protocol. */
  @Provides
  @HttpsWhoisProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> providerHttpsWhoisHandlerProviders(
      @HttpsWhoisProtocol Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<WebWhoisMessageHandler> messageHandlerProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return ImmutableList.of(
        sslClientInitializerProvider,
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        messageHandlerProvider,
        webWhoisActionHandlerProvider);
  }

  @Provides
  static HttpClientCodec provideHttpClientCodec() {
    return new HttpClientCodec();
  }

  @Provides
  static HttpObjectAggregator provideHttpObjectAggregator(@WebWhoisProtocol int maxContentLength) {
    return new HttpObjectAggregator(maxContentLength);
  }

  /** {@link Provides} the {@link SslClientInitializer} used for the {@link HttpsWhoisProtocol}. */
  @Provides
  @HttpsWhoisProtocol
  static SslClientInitializer<NioSocketChannel> provideSslClientInitializer(SslProvider sslProvider) {
    return new SslClientInitializer<>(sslProvider);
  }

  /** {@link Provides} the {@link Bootstrap} used by the WebWhois sequence. */
  @Singleton
  @Provides
  @WebWhoisProtocol
  static Bootstrap provideBootstrap(EventLoopGroup eventLoopGroup) {
    return new Bootstrap()
        .group(eventLoopGroup)
        .channel(NioSocketChannel.class);
  }

  @Provides
  @WebWhoisProtocol
  int provideMaximumMessageLengthBytes() {
    return maximumMessageLengthBytes;
  }

  /** {@link Provides} the list of top level domains to be probed */
  @Singleton
  @Provides
  @WebWhoisProtocol
  ImmutableList<String> provideTopLevelDomains() {
    return ImmutableList.of("how", "soy" , "xn--q9jyb4c");
  }

  @Provides
  @HttpWhoisProtocol
  int provideHttpWhoisPort() {
    return httpWhoIsPort;
  }

  @Provides
  @HttpsWhoisProtocol
  int provideHttpsWhoisPort() {
    return httpsWhoIsPort;
  }


}
