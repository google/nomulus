package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpWhoisProtocolFactory implements Factory<Protocol> {
  private final Provider<Integer> httpWhoisPortProvider;

  private final Provider<String> httpWhoisHostProvider;

  private final Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      handlerProvidersProvider;

  public WebWhoisModule_ProvideHttpWhoisProtocolFactory(
      Provider<Integer> httpWhoisPortProvider,
      Provider<String> httpWhoisHostProvider,
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    this.httpWhoisPortProvider = httpWhoisPortProvider;
    this.httpWhoisHostProvider = httpWhoisHostProvider;
    this.handlerProvidersProvider = handlerProvidersProvider;
  }

  @Override
  public Protocol get() {
    return proxyProvideHttpWhoisProtocol(
        httpWhoisPortProvider.get(), httpWhoisHostProvider.get(), handlerProvidersProvider.get());
  }

  public static WebWhoisModule_ProvideHttpWhoisProtocolFactory create(
      Provider<Integer> httpWhoisPortProvider,
      Provider<String> httpWhoisHostProvider,
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    return new WebWhoisModule_ProvideHttpWhoisProtocolFactory(
        httpWhoisPortProvider, httpWhoisHostProvider, handlerProvidersProvider);
  }

  public static Protocol proxyProvideHttpWhoisProtocol(
      int httpWhoisPort,
      String httpWhoisHost,
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Preconditions.checkNotNull(
        WebWhoisModule.provideHttpWhoisProtocol(httpWhoisPort, httpWhoisHost, handlerProviders),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
