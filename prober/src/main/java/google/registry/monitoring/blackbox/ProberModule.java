// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.monitoring.blackbox;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.monitoring.blackbox.WebWhoisModule.WebWhoisProtocol;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import javax.inject.Singleton;
import org.joda.time.Duration;

/**
 * Dagger main module, which {@link Provides} all objects that are shared between sequences and stores
 * {@link ProberComponent}, which allows main {@link Prober} class to obtain each {@link ProbingSequence}.
 */
@Module
public class ProberModule {

  /** Default {@link Duration} chosen to be time between each {@link ProbingAction} call. */
  private static final Duration DEFAULT_DURATION = new Duration(4000L);

  /** {@link Provides} one global {@link EventLoopGroup} shared by each {@link ProbingSequence}. */
  @Provides
  @Singleton
  EventLoopGroup provideEventLoopGroup() {
    return new NioEventLoopGroup();
  }

  /** {@link Provides} above {@code DEFAULT_DURATION} for all provided {@link ProbingStep}s to use. */
  @Provides
  @Singleton
  Duration provideDuration() {
    return DEFAULT_DURATION;
  }

  /** {@link Provides} the {@link SslProvider} used by instances of {@link google.registry.monitoring.blackbox.handlers.SslClientInitializer} */
  @Provides
  static SslProvider provideSslProvider() {
    // Prefer OpenSSL.
    return OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
  }

  /** Root level {@link Component} that provides each {@link ProbingSequence}. */
  @Singleton
  @Component(
      modules = {
          ProberModule.class,
          WebWhoisModule.class,
      })
  public interface ProberComponent {

    //Standard WebWhois sequence
    @WebWhoisProtocol ProbingSequence provideWebWhoisSequence();

  }
}
