package google.registry.monitoring.blackbox;

import dagger.MembersInjector;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TestToken_MembersInjector implements MembersInjector<TestToken> {
  private final Provider<Protocol> protocolProvider;

  public TestToken_MembersInjector(Provider<Protocol> protocolProvider) {
    this.protocolProvider = protocolProvider;
  }

  public static MembersInjector<TestToken> create(Provider<Protocol> protocolProvider) {
    return new TestToken_MembersInjector(protocolProvider);
  }

  @Override
  public void injectMembers(TestToken instance) {
    injectProtocol(instance, protocolProvider.get());
  }

  public static void injectProtocol(TestToken instance, Protocol protocol) {
    instance.protocol = protocol;
  }
}
