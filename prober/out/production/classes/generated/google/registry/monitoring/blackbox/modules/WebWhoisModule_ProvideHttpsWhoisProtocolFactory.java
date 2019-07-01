package google.registry.monitoring.blackbox.modules;

import com.google.common.collect.ImmutableList;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.Protocol;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpsWhoisProtocolFactory implements Factory<Protocol> {
  private final Provider<Integer> httpsWhoisPortProvider;

  private final Provider<String> httpsWhoisHostProvider;

  private final Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      handlerProvidersProvider;

  public WebWhoisModule_ProvideHttpsWhoisProtocolFactory(
      Provider<Integer> httpsWhoisPortProvider,
      Provider<String> httpsWhoisHostProvider,
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    this.httpsWhoisPortProvider = httpsWhoisPortProvider;
    this.httpsWhoisHostProvider = httpsWhoisHostProvider;
    this.handlerProvidersProvider = handlerProvidersProvider;
  }

  @Override
  public Protocol get() {
    return proxyProvideHttpsWhoisProtocol(
        httpsWhoisPortProvider.get(), httpsWhoisHostProvider.get(), handlerProvidersProvider.get());
  }

  public static WebWhoisModule_ProvideHttpsWhoisProtocolFactory create(
      Provider<Integer> httpsWhoisPortProvider,
      Provider<String> httpsWhoisHostProvider,
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    return new WebWhoisModule_ProvideHttpsWhoisProtocolFactory(
        httpsWhoisPortProvider, httpsWhoisHostProvider, handlerProvidersProvider);
  }

  public static Protocol proxyProvideHttpsWhoisProtocol(
      int httpsWhoisPort,
      String httpsWhoisHost,
      ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Preconditions.checkNotNull(
        WebWhoisModule.provideHttpsWhoisProtocol(httpsWhoisPort, httpsWhoisHost, handlerProviders),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
