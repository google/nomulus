package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import google.registry.monitoring.blackbox.handlers.ActionHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TestToken_Factory implements Factory<TestToken> {
  private final Provider<ActionHandler> actionHandlerProvider;

  private final Provider<String> domainNameProvider;

  private final Provider<Protocol> protocolProvider;

  public TestToken_Factory(
      Provider<ActionHandler> actionHandlerProvider,
      Provider<String> domainNameProvider,
      Provider<Protocol> protocolProvider) {
    this.actionHandlerProvider = actionHandlerProvider;
    this.domainNameProvider = domainNameProvider;
    this.protocolProvider = protocolProvider;
  }

  @Override
  public TestToken get() {
    TestToken instance = new TestToken(actionHandlerProvider.get(), domainNameProvider.get());
    TestToken_MembersInjector.injectProtocol(instance, protocolProvider.get());
    return instance;
  }

  public static TestToken_Factory create(
      Provider<ActionHandler> actionHandlerProvider,
      Provider<String> domainNameProvider,
      Provider<Protocol> protocolProvider) {
    return new TestToken_Factory(actionHandlerProvider, domainNameProvider, protocolProvider);
  }

  public static TestToken newTestToken(ActionHandler actionHandler, String domainName) {
    return new TestToken(actionHandler, domainName);
  }
}
