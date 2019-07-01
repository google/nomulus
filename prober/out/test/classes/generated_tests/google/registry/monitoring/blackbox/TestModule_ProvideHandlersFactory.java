package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TestModule_ProvideHandlersFactory
    implements Factory<ImmutableList<Provider<? extends ChannelHandler>>> {
  private final Provider<TestActionHandler> testHandlerProvider;

  public TestModule_ProvideHandlersFactory(Provider<TestActionHandler> testHandlerProvider) {
    this.testHandlerProvider = testHandlerProvider;
  }

  @Override
  public ImmutableList<Provider<? extends ChannelHandler>> get() {
    return proxyProvideHandlers(testHandlerProvider);
  }

  public static TestModule_ProvideHandlersFactory create(
      Provider<TestActionHandler> testHandlerProvider) {
    return new TestModule_ProvideHandlersFactory(testHandlerProvider);
  }

  public static ImmutableList<Provider<? extends ChannelHandler>> proxyProvideHandlers(
      Provider<TestActionHandler> testHandlerProvider) {
    return Preconditions.checkNotNull(
        TestModule.provideHandlers(testHandlerProvider),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
