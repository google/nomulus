package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisToken_Factory implements Factory<WebWhoisToken> {
  private static final WebWhoisToken_Factory INSTANCE = new WebWhoisToken_Factory();

  @Override
  public WebWhoisToken get() {
    return new WebWhoisToken();
  }

  public static WebWhoisToken_Factory create() {
    return INSTANCE;
  }

  public static WebWhoisToken newWebWhoisToken() {
    return new WebWhoisToken();
  }
}
