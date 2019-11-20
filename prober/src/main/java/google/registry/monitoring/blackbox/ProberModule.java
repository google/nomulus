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
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.module.CertificateModule;
import google.registry.monitoring.blackbox.module.EppModule;
import google.registry.monitoring.blackbox.module.WebWhoisModule;
import google.registry.networking.handler.SslClientInitializer;
import google.registry.util.Clock;
import google.registry.util.SystemClock;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslProvider;
import java.util.Set;
import javax.inject.Singleton;
import org.joda.time.Duration;

/**
 * Dagger main module, which {@link Provides} all objects that are shared between sequences and
 * stores {@link ProberComponent}, which allows main {@link Prober} class to obtain each {@link
 * ProbingSequence}.
 */
@Module
public class ProberModule {

  /** Default {@link Duration} chosen to be time between each {@link ProbingAction} call. */
  private static final Duration DEFAULT_PROBER_INTERVAL = Duration.standardSeconds(4);

  /** {@link Provides} the {@link SslProvider} used by instances of {@link SslClientInitializer} */
  @Provides
  @Singleton
  static SslProvider provideSslProvider() {
    // Prefer OpenSSL.
    return OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
  }

  /** {@link Provides} one global {@link Clock} shared by each {@link ProbingSequence}. */
  @Provides
  @Singleton
  static Clock provideClock() {
    return new SystemClock();
  }

  /** {@link Provides} one global {@link EventLoopGroup} shared by each {@link ProbingSequence}. */
  @Provides
  @Singleton
  EventLoopGroup provideEventLoopGroup() {
    return new NioEventLoopGroup();
  }

  /**
   * {@link Provides} one global {@link Channel} class that is used to construct a {@link
   * io.netty.bootstrap.Bootstrap}.
   */
  @Provides
  @Singleton
  Class<? extends Channel> provideChannelClazz() {
    return NioSocketChannel.class;
  }

  /**
   * {@link Provides} above {@code DEFAULT_PROBER_INTERVAL} for all provided {@link ProbingStep}s to
   * use.
   */
  @Provides
  @Singleton
  Duration provideProbeInterval() {
    return DEFAULT_PROBER_INTERVAL;
  }

  /**
   * {@link Provides} general {@link Bootstrap} for which a new instance is provided in any {@link
   * ProbingSequence}.
   */
  @Provides
  Bootstrap provideBootstrap(EventLoopGroup eventLoopGroup) {
    return new Bootstrap().group(eventLoopGroup).channel(NioSocketChannel.class);
  }

  /** Root level {@link Component} that provides each {@link ProbingSequence}. */
  @Singleton
  @Component(
      modules = {
        ProberModule.class,
        WebWhoisModule.class,
        EppModule.class,
        CertificateModule.class
      })
  public interface ProberComponent {

    // Standard WebWhois sequence
    Set<ProbingSequence> sequences();
  }
}
