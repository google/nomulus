package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProbingStepWeb_Factory implements Factory<ProbingStepWeb> {
  private final Provider<Protocol> protocolProvider;

  public ProbingStepWeb_Factory(Provider<Protocol> protocolProvider) {
    this.protocolProvider = protocolProvider;
  }

  @Override
  public ProbingStepWeb get() {
    return new ProbingStepWeb(protocolProvider.get());
  }

  public static ProbingStepWeb_Factory create(Provider<Protocol> protocolProvider) {
    return new ProbingStepWeb_Factory(protocolProvider);
  }

  public static ProbingStepWeb newProbingStepWeb(Protocol protocol) {
    return new ProbingStepWeb(protocol);
  }
}
