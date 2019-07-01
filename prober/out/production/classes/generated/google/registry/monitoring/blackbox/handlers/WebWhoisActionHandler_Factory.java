package google.registry.monitoring.blackbox.handlers;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisActionHandler_Factory implements Factory<WebWhoisActionHandler> {
  private static final WebWhoisActionHandler_Factory INSTANCE = new WebWhoisActionHandler_Factory();

  @Override
  public WebWhoisActionHandler get() {
    return new WebWhoisActionHandler();
  }

  public static WebWhoisActionHandler_Factory create() {
    return INSTANCE;
  }

  public static WebWhoisActionHandler newWebWhoisActionHandler() {
    return new WebWhoisActionHandler();
  }
}
