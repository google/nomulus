package google.registry.monitoring.blackbox;

import com.google.common.collect.ImmutableList;
import dagger.internal.DoubleCheck;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer_Factory;
import google.registry.monitoring.blackbox.handlers.WebWhoisActionHandler_Factory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class DaggerWebWhoisModule_WebWhoisComponent
    implements WebWhoisModule.WebWhoisComponent {
  private Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      providerHttpWhoisHandlerProvidersProvider;

  private Provider<Protocol> provideHttpWhoisProtocolProvider;

  private Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider;

  private Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      providerHttpsWhoisHandlerProvidersProvider;

  private Provider<Protocol> provideHttpsWhoisProtocolProvider;

  private DaggerWebWhoisModule_WebWhoisComponent() {

    initialize();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static WebWhoisModule.WebWhoisComponent create() {
    return new Builder().build();
  }

  @SuppressWarnings("unchecked")
  private void initialize() {
    this.providerHttpWhoisHandlerProvidersProvider =
        WebWhoisModule_ProviderHttpWhoisHandlerProvidersFactory.create(
            WebWhoisModule_ProvideHttpClientCodecFactory.create(),
            WebWhoisModule_ProvideHttpObjectAggregatorFactory.create(),
            WebWhoisActionHandler_Factory.create());
    this.provideHttpWhoisProtocolProvider =
        DoubleCheck.provider(
            WebWhoisModule_ProvideHttpWhoisProtocolFactory.create(
                WebWhoisModule_HttpWhoisPortFactory.create(),
                WebWhoisModule_HttpWhoisHostFactory.create(),
                providerHttpWhoisHandlerProvidersProvider));
    this.sslClientInitializerProvider =
        DoubleCheck.provider(
            (Provider)
                SslClientInitializer_Factory.create(
                    WebWhoisModule_ProvideSslProviderFactory.create()));
    this.providerHttpsWhoisHandlerProvidersProvider =
        WebWhoisModule_ProviderHttpsWhoisHandlerProvidersFactory.create(
            sslClientInitializerProvider,
            WebWhoisModule_ProvideHttpClientCodecFactory.create(),
            WebWhoisModule_ProvideHttpObjectAggregatorFactory.create(),
            WebWhoisActionHandler_Factory.create());
    this.provideHttpsWhoisProtocolProvider =
        DoubleCheck.provider(
            WebWhoisModule_ProvideHttpsWhoisProtocolFactory.create(
                WebWhoisModule_HttpsWhoisPortFactory.create(),
                WebWhoisModule_HttpsWhoisHostFactory.create(),
                providerHttpsWhoisHandlerProvidersProvider));
  }

  @Override
  public Protocol provideHttpWhoisProtocol() {
    return provideHttpWhoisProtocolProvider.get();
  }

  @Override
  public Protocol provideHttpsWhoisProtocol() {
    return provideHttpsWhoisProtocolProvider.get();
  }

  @Override
  public Token provideToken() {
    return TokenModule_ProvideTokenFactory.proxyProvideToken();
  }

  public static final class Builder {
    private Builder() {}

    /**
     * @deprecated This module is declared, but an instance is not used in the component. This
     *     method is a no-op. For more, see https://google.github.io/dagger/unused-modules.
     */
    @Deprecated
    public Builder webWhoisModule(WebWhoisModule webWhoisModule) {
      Preconditions.checkNotNull(webWhoisModule);
      return this;
    }

    /**
     * @deprecated This module is declared, but an instance is not used in the component. This
     *     method is a no-op. For more, see https://google.github.io/dagger/unused-modules.
     */
    @Deprecated
    public Builder tokenModule(TokenModule tokenModule) {
      Preconditions.checkNotNull(tokenModule);
      return this;
    }

    public WebWhoisModule.WebWhoisComponent build() {
      return new DaggerWebWhoisModule_WebWhoisComponent();
    }
  }
}
