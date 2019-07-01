package google.registry.monitoring.blackbox.modules;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import dagger.internal.DoubleCheck;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.Tokens.Token;
import google.registry.monitoring.blackbox.Tokens.WebWhoisToken;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer;
import google.registry.monitoring.blackbox.handlers.SslClientInitializer_Factory;
import google.registry.monitoring.blackbox.handlers.WebWhoisActionHandler_Factory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Set;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class DaggerProberModule_ProberComponent implements ProberModule.ProberComponent {
  private final ProberModule proberModule;

  private Provider<Integer> provideHttpWhoisPortProvider;

  private Provider<String> provideHttpWhoisHostProvider;

  private Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      providerHttpWhoisHandlerProvidersProvider;

  private Provider<Protocol> provideHttpWhoisProtocolProvider;

  private Provider<Integer> provideHttpsWhoisPortProvider;

  private Provider<SslClientInitializer<NioSocketChannel>> sslClientInitializerProvider;

  private Provider<ImmutableList<Provider<? extends ChannelHandler>>>
      providerHttpsWhoisHandlerProvidersProvider;

  private Provider<Protocol> provideHttpsWhoisProtocolProvider;

  private DaggerProberModule_ProberComponent(
      ProberModule proberModuleParam, WebWhoisModule webWhoisModuleParam) {
    this.proberModule = proberModuleParam;
    initialize(proberModuleParam, webWhoisModuleParam);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static ProberModule.ProberComponent create() {
    return new Builder().build();
  }

  private Set<Protocol> getSetOfProtocol() {
    return ImmutableSet.<Protocol>of(
        provideHttpWhoisProtocolProvider.get(), provideHttpsWhoisProtocolProvider.get());
  }

  private WebWhoisToken getWebWhoisToken() {
    return new WebWhoisToken(TokenModule_DomainNameFactory.proxyDomainName());
  }

  @SuppressWarnings("unchecked")
  private void initialize(
      final ProberModule proberModuleParam, final WebWhoisModule webWhoisModuleParam) {
    this.provideHttpWhoisPortProvider =
        ProberModule_ProvideHttpWhoisPortFactory.create(proberModuleParam);
    this.provideHttpWhoisHostProvider =
        WebWhoisModule_ProvideHttpWhoisHostFactory.create(webWhoisModuleParam);
    this.providerHttpWhoisHandlerProvidersProvider =
        WebWhoisModule_ProviderHttpWhoisHandlerProvidersFactory.create(
            WebWhoisModule_ProvideHttpClientCodecFactory.create(),
            WebWhoisModule_ProvideHttpObjectAggregatorFactory.create(),
            WebWhoisActionHandler_Factory.create());
    this.provideHttpWhoisProtocolProvider =
        DoubleCheck.provider(
            WebWhoisModule_ProvideHttpWhoisProtocolFactory.create(
                provideHttpWhoisPortProvider,
                provideHttpWhoisHostProvider,
                providerHttpWhoisHandlerProvidersProvider));
    this.provideHttpsWhoisPortProvider =
        ProberModule_ProvideHttpsWhoisPortFactory.create(proberModuleParam);
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
                provideHttpsWhoisPortProvider,
                provideHttpWhoisHostProvider,
                providerHttpsWhoisHandlerProvidersProvider));
  }

  @Override
  public ImmutableMap<Integer, Protocol> providePortToProtocolMap() {
    return ProberModule_ProvidePortToProtocolMapFactory.proxyProvidePortToProtocolMap(
        proberModule, getSetOfProtocol());
  }

  @Override
  public Token provideToken() {
    return TokenModule_ProvideTokenFactory.proxyProvideToken(getWebWhoisToken());
  }

  public static final class Builder {
    private ProberModule proberModule;

    private WebWhoisModule webWhoisModule;

    private Builder() {}

    public Builder proberModule(ProberModule proberModule) {
      this.proberModule = Preconditions.checkNotNull(proberModule);
      return this;
    }

    public Builder webWhoisModule(WebWhoisModule webWhoisModule) {
      this.webWhoisModule = Preconditions.checkNotNull(webWhoisModule);
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

    public ProberModule.ProberComponent build() {
      if (proberModule == null) {
        this.proberModule = new ProberModule();
      }
      if (webWhoisModule == null) {
        this.webWhoisModule = new WebWhoisModule();
      }
      return new DaggerProberModule_ProberComponent(proberModule, webWhoisModule);
    }
  }
}
