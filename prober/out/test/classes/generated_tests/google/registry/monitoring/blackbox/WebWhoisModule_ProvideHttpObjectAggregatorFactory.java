package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import io.netty.handler.codec.http.HttpObjectAggregator;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpObjectAggregatorFactory
    implements Factory<HttpObjectAggregator> {
  private static final WebWhoisModule_ProvideHttpObjectAggregatorFactory INSTANCE =
      new WebWhoisModule_ProvideHttpObjectAggregatorFactory();

  @Override
  public HttpObjectAggregator get() {
    return proxyProvideHttpObjectAggregator();
  }

  public static WebWhoisModule_ProvideHttpObjectAggregatorFactory create() {
    return INSTANCE;
  }

  public static HttpObjectAggregator proxyProvideHttpObjectAggregator() {
    return Preconditions.checkNotNull(
        WebWhoisModule.provideHttpObjectAggregator(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
