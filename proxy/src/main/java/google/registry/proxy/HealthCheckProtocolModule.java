// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy;

import com.google.common.collect.ImmutableList;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.handler.HealthCheckHandler;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import javax.inject.Provider;
import javax.inject.Qualifier;
import javax.inject.Singleton;

/**
 * Module that provides a {@link FrontendProtocol} used for GCP load balancer health checking.
 *
 * <p>The load balancer sends health checking messages to the GCE instances to assess whether they
 * are ready to receive traffic. No relay channel needs to be established for this protocol.
 */
@Module
public class HealthCheckProtocolModule {

  /** Dagger qualifier to provide health check protocol related handlers and other bindings. */
  @Qualifier
  @interface HealthCheckProtocol {}

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
        .hasBackend(false)
        .handlerProviders(handlerProviders)
        .build();
  }

  @Provides
  @HealthCheckProtocol
  static ImmutableList<Provider<? extends ChannelHandler>> provideHandlerProviders(
      Provider<FixedLengthFrameDecoder> fixedLengthFrameDecoderProvider,
      Provider<HealthCheckHandler> healthCheckHandlerProvider) {
    return ImmutableList.of(fixedLengthFrameDecoderProvider, healthCheckHandlerProvider);
  }

  @Provides
  static FixedLengthFrameDecoder provideFixedLengthFrameDecoder(ProxyConfig config) {
    return new FixedLengthFrameDecoder(config.healthCheck.checkRequest.length());
  }

  @Provides
  static HealthCheckHandler provideHealthCheckHandler(ProxyConfig config) {
    return new HealthCheckHandler(
        config.healthCheck.checkRequest, config.healthCheck.checkResponse);
  }
}
