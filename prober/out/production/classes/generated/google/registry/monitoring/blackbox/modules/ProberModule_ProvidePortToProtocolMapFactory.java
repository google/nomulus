package google.registry.monitoring.blackbox.modules;

import com.google.common.collect.ImmutableMap;
import dagger.internal.Factory;
import dagger.internal.Preconditions;
import google.registry.monitoring.blackbox.Protocol;
import java.util.Set;
import javax.annotation.Generated;
import javax.inject.Provider;

@Generated(
  value = "dagger.internal.codegen.ComponentProcessor",
  comments = "https://google.github.io/dagger"
)
public final class ProberModule_ProvidePortToProtocolMapFactory
    implements Factory<ImmutableMap<Integer, Protocol>> {
  private final ProberModule module;

  private final Provider<Set<Protocol>> protocolSetProvider;

  public ProberModule_ProvidePortToProtocolMapFactory(
      ProberModule module, Provider<Set<Protocol>> protocolSetProvider) {
    this.module = module;
    this.protocolSetProvider = protocolSetProvider;
  }

  @Override
  public ImmutableMap<Integer, Protocol> get() {
    return proxyProvidePortToProtocolMap(module, protocolSetProvider.get());
  }

  public static ProberModule_ProvidePortToProtocolMapFactory create(
      ProberModule module, Provider<Set<Protocol>> protocolSetProvider) {
    return new ProberModule_ProvidePortToProtocolMapFactory(module, protocolSetProvider);
  }

  public static ImmutableMap<Integer, Protocol> proxyProvidePortToProtocolMap(
      ProberModule instance, Set<Protocol> protocolSet) {
    return Preconditions.checkNotNull(
        instance.providePortToProtocolMap(protocolSet),
        "Cannot return null from a non-@Nullable @Provides method");
  }
}
