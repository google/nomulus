package google.registry.monitoring.blackbox.modules;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpWhoisPortFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpWhoisPortFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpWhoisPort(module);
  }

  public static ProberModule_ProvideHttpWhoisPortFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpWhoisPortFactory(module);
  }

  public static int proxyProvideHttpWhoisPort(ProberModule instance) {
    return instance.provideHttpWhoisPort();
  }
}
