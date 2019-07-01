package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpsWhoisPortFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpsWhoisPortFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpsWhoisPort(module);
  }

  public static ProberModule_ProvideHttpsWhoisPortFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpsWhoisPortFactory(module);
  }

  public static int proxyProvideHttpsWhoisPort(ProberModule instance) {
    return instance.provideHttpsWhoisPort();
  }
}
