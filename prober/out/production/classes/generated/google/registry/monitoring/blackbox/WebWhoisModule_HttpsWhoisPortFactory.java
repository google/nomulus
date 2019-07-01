package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpsWhoisPortFactory implements Factory<Integer> {
  private static final WebWhoisModule_HttpsWhoisPortFactory INSTANCE =
      new WebWhoisModule_HttpsWhoisPortFactory();

  @Override
  public Integer get() {
    return proxyHttpsWhoisPort();
  }

  public static WebWhoisModule_HttpsWhoisPortFactory create() {
    return INSTANCE;
  }

  public static int proxyHttpsWhoisPort() {
    return WebWhoisModule.httpsWhoisPort();
  }
}
