package google.registry.monitoring.blackbox.handlers;

import dagger.internal.Factory;
import io.netty.channel.Channel;
import io.netty.handler.ssl.SslProvider;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class SslClientInitializer_Factory<C extends Channel>
    implements Factory<SslClientInitializer<C>> {
  private final Provider<SslProvider> sslProvider;

  public SslClientInitializer_Factory(Provider<SslProvider> sslProvider) {
    this.sslProvider = sslProvider;
  }

  @Override
  public SslClientInitializer<C> get() {
    return new SslClientInitializer<C>(sslProvider.get());
  }

  public static <C extends Channel> SslClientInitializer_Factory<C> create(
      Provider<SslProvider> sslProvider) {
    return new SslClientInitializer_Factory<C>(sslProvider);
  }

  public static <C extends Channel> SslClientInitializer<C> newSslClientInitializer(
      SslProvider sslProvider) {
    return new SslClientInitializer<C>(sslProvider);
  }
}
