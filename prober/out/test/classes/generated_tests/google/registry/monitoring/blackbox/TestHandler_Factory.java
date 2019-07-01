package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TestHandler_Factory implements Factory<TestHandler> {
  private static final TestHandler_Factory INSTANCE = new TestHandler_Factory();

  @Override
  public TestHandler get() {
    return new TestHandler();
  }

  public static TestHandler_Factory create() {
    return INSTANCE;
  }

  public static TestHandler newTestHandler() {
    return new TestHandler();
  }
}
