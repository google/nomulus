package google.registry.monitoring.blackbox.handlers;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoIsHandler_Factory implements Factory<WebWhoIsHandler> {
  private static final WebWhoIsHandler_Factory INSTANCE = new WebWhoIsHandler_Factory();

  @Override
  public WebWhoIsHandler get() {
    return new WebWhoIsHandler();
  }

  public static WebWhoIsHandler_Factory create() {
    return INSTANCE;
  }

  public static WebWhoIsHandler newWebWhoIsHandler() {
    return new WebWhoIsHandler();
  }
}
