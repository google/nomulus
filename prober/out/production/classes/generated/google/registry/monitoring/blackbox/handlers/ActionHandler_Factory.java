package google.registry.monitoring.blackbox.handlers;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ActionHandler_Factory implements Factory<ActionHandler> {
  private static final ActionHandler_Factory INSTANCE = new ActionHandler_Factory();

  @Override
  public ActionHandler get() {
    return new ActionHandler();
  }

  public static ActionHandler_Factory create() {
    return INSTANCE;
  }

  public static ActionHandler newActionHandler() {
    return new ActionHandler();
  }
}
