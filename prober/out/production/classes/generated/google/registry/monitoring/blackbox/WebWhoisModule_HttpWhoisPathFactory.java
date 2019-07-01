package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_HttpWhoisPathFactory implements Factory<String> {
  private static final WebWhoisModule_HttpWhoisPathFactory INSTANCE =
      new WebWhoisModule_HttpWhoisPathFactory();

  @Override
  public String get() {
    return proxyHttpWhoisPath();
  }

  public static WebWhoisModule_HttpWhoisPathFactory create() {
    return INSTANCE;
  }

  public static String proxyHttpWhoisPath() {
    return Preconditions.checkNotNull(
        WebWhoisModule.httpWhoisPath(), "Cannot return null from a non-@Nullable @Provides method");
  }
}
