package google.registry.monitoring.blackbox.handlers;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class RedirectHandler_Factory implements Factory<RedirectHandler> {
  private static final RedirectHandler_Factory INSTANCE = new RedirectHandler_Factory();

  @Override
  public RedirectHandler get() {
    return new RedirectHandler();
  }

  public static RedirectHandler_Factory create() {
    return INSTANCE;
  }

  public static RedirectHandler newRedirectHandler() {
    return new RedirectHandler();
  }
}
