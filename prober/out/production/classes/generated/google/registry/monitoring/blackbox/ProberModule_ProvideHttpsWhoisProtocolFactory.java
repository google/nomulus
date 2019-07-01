package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpsWhoisProtocolFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpsWhoisProtocolFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpsWhoisProtocol(module);
  }

  public static ProberModule_ProvideHttpsWhoisProtocolFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpsWhoisProtocolFactory(module);
  }

  public static int proxyProvideHttpsWhoisProtocol(ProberModule instance) {
    return instance.provideHttpsWhoisProtocol();
  }
}
