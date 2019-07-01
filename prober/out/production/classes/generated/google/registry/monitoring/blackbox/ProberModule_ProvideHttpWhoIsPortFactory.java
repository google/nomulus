package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpWhoIsPortFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpWhoIsPortFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpWhoIsPort(module);
  }

  public static ProberModule_ProvideHttpWhoIsPortFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpWhoIsPortFactory(module);
  }

  public static int proxyProvideHttpWhoIsPort(ProberModule instance) {
    return instance.provideHttpWhoIsPort();
  }
}
