package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvideHttpWhoisProtocolFactory implements Factory<Integer> {
  private final ProberModule module;

  public ProberModule_ProvideHttpWhoisProtocolFactory(ProberModule module) {
    this.module = module;
  }

  @Override
  public Integer get() {
    return proxyProvideHttpWhoisProtocol(module);
  }

  public static ProberModule_ProvideHttpWhoisProtocolFactory create(ProberModule module) {
    return new ProberModule_ProvideHttpWhoisProtocolFactory(module);
  }

  public static int proxyProvideHttpWhoisProtocol(ProberModule instance) {
    return instance.provideHttpWhoisProtocol();
  }
}
