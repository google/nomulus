package google.registry.monitoring.blackbox.modules;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class TokenModule_DomainNameFactory implements Factory<String> {
  private static final TokenModule_DomainNameFactory INSTANCE = new TokenModule_DomainNameFactory();

  @Override
  public String get() {
    return proxyDomainName();
  }

  public static TokenModule_DomainNameFactory create() {
    return INSTANCE;
  }

  public static String proxyDomainName() {
    return Preconditions.checkNotNull(
        TokenModule.domainName(), "Cannot return null from a non-@Nullable @Provides method");
  }
}
