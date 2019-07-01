package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpWhoisPortFactory implements Factory<Integer> {
  private static final WebWhoisModule_HttpWhoisPortFactory INSTANCE =
      new WebWhoisModule_HttpWhoisPortFactory();

  @Override
  public Integer get() {
    return proxyHttpWhoisPort();
  }

  public static WebWhoisModule_HttpWhoisPortFactory create() {
    return INSTANCE;
  }

  public static int proxyHttpWhoisPort() {
    return WebWhoisModule.httpWhoisPort();
  }
}
