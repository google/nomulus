package google.registry.monitoring.blackbox.modules;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpWhoisHostFactory implements Factory<String> {
  private final WebWhoisModule module;

  public WebWhoisModule_ProvideHttpWhoisHostFactory(WebWhoisModule module) {
    this.module = module;
  }

  @Override
  public String get() {
    return proxyProvideHttpWhoisHost(module);
  }

  public static WebWhoisModule_ProvideHttpWhoisHostFactory create(WebWhoisModule module) {
    return new WebWhoisModule_ProvideHttpWhoisHostFactory(module);
  }

  public static String proxyProvideHttpWhoisHost(WebWhoisModule instance) {
    return Preconditions.checkNotNull(
        instance.provideHttpWhoisHost(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
