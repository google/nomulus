package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpsWhoIsPortFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpsWhoIsPortFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpsWhoIsPort(module);
  }

  public static ProberModule_ProvideHttpsWhoIsPortFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpsWhoIsPortFactory(module);
  }

  public static int proxyProvideHttpsWhoIsPort(ProberModule instance) {
    return instance.provideHttpsWhoIsPort();
  }
}
