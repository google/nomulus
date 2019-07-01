package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpsWhoisHostFactory implements Factory<String> {
  private static final WebWhoisModule_HttpsWhoisHostFactory INSTANCE =
      new WebWhoisModule_HttpsWhoisHostFactory();

  @Override
  public String get() {
    return proxyHttpsWhoisHost();
  }

  public static WebWhoisModule_HttpsWhoisHostFactory create() {
    return INSTANCE;
  }

  public static String proxyHttpsWhoisHost() {
    return Preconditions.checkNotNull(
        WebWhoisModule.httpsWhoisHost(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
