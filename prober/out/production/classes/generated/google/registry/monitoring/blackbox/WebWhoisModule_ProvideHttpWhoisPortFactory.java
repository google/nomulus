package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpWhoisPortFactory implements Factory<Integer> {
  private final WebWhoisModule module;

  public WebWhoisModule_ProvideHttpWhoisPortFactory(WebWhoisModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpWhoisPort(module);
  }

  public static WebWhoisModule_ProvideHttpWhoisPortFactory create(WebWhoisModule module) {
    return new WebWhoisModule_ProvideHttpWhoisPortFactory(module);
  }

  public static int proxyProvideHttpWhoisPort(WebWhoisModule instance) {
    return instance.provideHttpWhoisPort();
  }
}
