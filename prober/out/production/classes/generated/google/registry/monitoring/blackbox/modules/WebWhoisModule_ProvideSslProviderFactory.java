package google.registry.monitoring.blackbox.modules;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import io.netty.handler.ssl.SslProvider;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideSslProviderFactory implements Factory<SslProvider> {
  private static final WebWhoisModule_ProvideSslProviderFactory INSTANCE =
      new WebWhoisModule_ProvideSslProviderFactory();

  @Override
  public SslProvider get() {
    return proxyProvideSslProvider();
  }

  public static WebWhoisModule_ProvideSslProviderFactory create() {
    return INSTANCE;
  }

  public static SslProvider proxyProvideSslProvider() {
    return Preconditions.checkNotNull(
        WebWhoisModule.provideSslProvider(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
