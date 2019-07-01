package google.registry.monitoring.blackbox;

import dagger.internal.Factory;
import dagger.internal.Preconditions;
import io.netty.handler.codec.http.HttpClientCodec;
import javax.annotation.Generated;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class WebWhoisModule_ProvideHttpClientCodecFactory
    implements Factory<HttpClientCodec> {
  private static final WebWhoisModule_ProvideHttpClientCodecFactory INSTANCE =
      new WebWhoisModule_ProvideHttpClientCodecFactory();

  @Override
  public HttpClientCodec get() {
    return proxyProvideHttpClientCodec();
  }

  public static WebWhoisModule_ProvideHttpClientCodecFactory create() {
    return INSTANCE;
  }

  public static HttpClientCodec proxyProvideHttpClientCodec() {
    return Preconditions.checkNotNull(
        WebWhoisModule.provideHttpClientCodec(),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
