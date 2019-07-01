package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpWhoisHostFactory implements Factory<String> {
  private static final WebWhoisModule_HttpWhoisHostFactory INSTANCE =
      new WebWhoisModule_HttpWhoisHostFactory();

  @Override
  public String get() {
    return proxyHttpWhoisHost();
  }

  public static WebWhoisModule_HttpWhoisHostFactory create() {
    return INSTANCE;
  }

  public static String proxyHttpWhoisHost() {
    return Preconditions.checkNotNull(
        WebWhoisModule.httpWhoisHost(), "Cannot return null from a non-@Nullable @Provides method");
  }
}
