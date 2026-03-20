// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.eppserver;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.eppserver.Protocol.FrontendProtocol;
import google.registry.eppserver.handler.HealthCheckHandler;
import io.netty.channel.ChannelHandler;
import jakarta.inject.Provider;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;

/** A module that provides the {@link FrontendProtocol} used for health check protocol. */
@Module
public final class HealthCheckProtocolModule {

  /** Dagger qualifier to provide health check protocol related handlers and other bindings. */
  @Qualifier
  public @interface HealthCheckProtocol {}

  private static final String PROTOCOL_NAME = "health_check";

  @Singleton
  @Provides
  @IntoSet
  static FrontendProtocol provideProtocol(
      @HealthCheckProtocol int healthCheckPort,
      @HealthCheckProtocol ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
    return Protocol.frontendBuilder()
        .name(PROTOCOL_NAME)
        .port(healthCheckPort)
        .handlerProviders(handlerProviders)
        .hasBackend(false)
        .build();
  }

  @Provides
  @HealthCheckProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      @HealthCheckProtocol Provider<HealthCheckHandler> healthCheckHandlerProvider) {
    return ImmutableList.of(healthCheckHandlerProvider);
  }

  @Provides
  @HealthCheckProtocol
  static HealthCheckHandler provideHealthCheckHandler() {
    // These are currently hardcoded in the handler or defaulted in RegistryConfig.
    // For now, we use the standard GCP health check strings.
    return new HealthCheckHandler("HEALTH_CHECK_REQUEST", "HEALTH_CHECK_RESPONSE");
  }
}
