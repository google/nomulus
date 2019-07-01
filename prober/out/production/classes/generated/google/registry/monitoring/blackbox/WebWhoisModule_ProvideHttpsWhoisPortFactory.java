package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpsWhoisPortFactory implements Factory<Integer> {
  private final WebWhoisModule module;

  public WebWhoisModule_ProvideHttpsWhoisPortFactory(WebWhoisModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpsWhoisPort(module);
  }

  public static WebWhoisModule_ProvideHttpsWhoisPortFactory create(WebWhoisModule module) {
    return new WebWhoisModule_ProvideHttpsWhoisPortFactory(module);
  }

  public static int proxyProvideHttpsWhoisPort(WebWhoisModule instance) {
    return instance.provideHttpsWhoisPort();
  }
}
