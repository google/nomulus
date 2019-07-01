package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpsWhoisPathFactory implements Factory<String> {
  private static final WebWhoisModule_HttpsWhoisPathFactory INSTANCE =
      new WebWhoisModule_HttpsWhoisPathFactory();

  @Override
  public String get() {
    return proxyHttpsWhoisPath();
  }

  public static WebWhoisModule_HttpsWhoisPathFactory create() {
    return INSTANCE;
  }

  public static String proxyHttpsWhoisPath() {
    return Preconditions.checkNotNull(
        WebWhoisModule.httpsWhoisPath(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
