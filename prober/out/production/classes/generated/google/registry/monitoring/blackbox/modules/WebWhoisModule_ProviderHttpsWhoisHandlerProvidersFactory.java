package google.registry.monitoring.blackbox.modules;

import com.google.common.collect.ImmutableList;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer;
import google.registry.monitoring.blackbox.handlers.WebWhoisActionHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProviderHttpsWhoisHandlerProvidersFactory
    implements Factory<ImmutableList<Provider<? extends ChannelHandler>>> {
  private final Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider;

  private final Provider<HttpClientCodec> httpClientCodecProvider;

  private final Provider<HttpObjectAggregator> httpObjectAggregatorProvider;

  private final Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider;

  public WebWhoisModule_ProviderHttpsWhoisHandlerProvidersFactory(
      Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    this.sslClientInitializerProvider = sslClientInitializerProvider;
    this.httpClientCodecProvider = httpClientCodecProvider;
    this.httpObjectAggregatorProvider = httpObjectAggregatorProvider;
    this.webWhoisActionHandlerProvider = webWhoisActionHandlerProvider;
  }

  @Override
  public ImmutableList<Provider<? extends ChannelHandler>> get() {
    return proxyProviderHttpsWhoisHandlerProviders(
        sslClientInitializerProvider,
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        webWhoisActionHandlerProvider);
  }

  public static WebWhoisModule_ProviderHttpsWhoisHandlerProvidersFactory create(
      Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
      Provider<HttpClientCodec> httpClientCodecProvider,
      Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
      Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return new WebWhoisModule_ProviderHttpsWhoisHandlerProvidersFactory(
        sslClientInitializerProvider,
        httpClientCodecProvider,
        httpObjectAggregatorProvider,
        webWhoisActionHandlerProvider);
  }

  public static ImmutableList<Provider<? extends ChannelHandler>>
      proxyProviderHttpsWhoisHandlerProviders(
          Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider,
          Provider<HttpClientCodec> httpClientCodecProvider,
          Provider<HttpObjectAggregator> httpObjectAggregatorProvider,
          Provider<WebWhoisActionHandler> webWhoisActionHandlerProvider) {
    return Preconditions.checkNotNull(
        WebWhoisModule.providerHttpsWhoisHandlerProviders(
            sslClientInitializerProvider,
            httpClientCodecProvider,
            httpObjectAggregatorProvider,
            webWhoisActionHandlerProvider),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
