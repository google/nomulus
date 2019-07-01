package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.internal.Preconditions;
import io.netty.channel.ChannelHandler;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class DaggerTestModule_TestComponent implements TestModule.TestComponent {
  private DaggerTestModule_TestComponent() {}

  public static Builder builder() {
    return new Builder();
  }

  public static TestModule.TestComponent create() {
    return new Builder().build();
  }

  @Override
  public ImmutableList<Provider<? extends ChannelHandler>> provideHandlers() {
    return TestModule_ProvideHandlersFactory.proxyProvideHandlers(TestHandler_Factory.create());
  }

  public static final class Builder {
    private Builder() {}

    /**
     * @deprecated This module is declared, but an instance is not used in the component. This
     *     method is a no-op. For more, see https://google.github.io/dagger/unused-modules.
     */
    @Deprecated
    public Builder testModule(TestModule testModule) {
      Preconditions.checkNotNull(testModule);
      return this;
    }

    public TestModule.TestComponent build() {
      return new DaggerTestModule_TestComponent();
    }
  }
}
