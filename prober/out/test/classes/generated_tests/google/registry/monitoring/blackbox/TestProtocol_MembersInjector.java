package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.MembersInjector;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TestProtocol_MembersInjector implements MembersInjector<TestProtocol> {
  private final Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      handlerProvidersProvider;

  public TestProtocol_MembersInjector(
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    this.handlerProvidersProvider = handlerProvidersProvider;
  }

  public static MembersInjector<TestProtocol> create(
      Provider<ImmutableList<Provider<? extends ChannelHandler>>> handlerProvidersProvider) {
    return new TestProtocol_MembersInjector(handlerProvidersProvider);
  }

  @Override
  public void injectMembers(TestProtocol instance) {
    injectTestProtocolHandlers(instance, handlerProvidersProvider.get());
  }

  public static void injectTestProtocolHandlers(
      TestProtocol instance, ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    instance.TestProtocolHandlers(handlerProviders);
  }
}
