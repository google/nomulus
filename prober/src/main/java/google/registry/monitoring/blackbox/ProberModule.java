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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import google.registry.monitoring.blackbox.Tokens.Token;
import google.registry.monitoring.blackbox.WebWhoisModule.HttpWhoisProtocol;
import google.registry.monitoring.blackbox.WebWhoisModule.HttpsWhoisProtocol;
import google.registry.monitoring.blackbox.WebWhoisModule.WebWhoisProtocol;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Set;
import javax.inject.Singleton;

/**
 * {@link Dagger} main module, which Provides {@link ProbingSequences} and houses {@link ProberComponent}
 *
 * <p>Provides</p>
 */
@Module
public class ProberModule {
  private final int httpWhoIsPort = 80;
  private final int httpsWhoIsPort = 443;

  @Provides
  @Singleton
  EventLoopGroup provideEventLoopGroup() {
    return new NioEventLoopGroup();
  }

  @Provides
  @HttpWhoisProtocol
  ProbingSequence<NioSocketChannel> provideHttpWhoisSequence(
      @HttpWhoisProtocol ProbingStep<NioSocketChannel> probingStep,
      EventLoopGroup eventLoopGroup) {
    return new ProbingSequence.Builder<NioSocketChannel>()
        .setClass(NioSocketChannel.class)
        .addStep(probingStep)
        .makeFirstRepeated()
        .eventLoopGroup(eventLoopGroup)
        .build();
  }

  @Provides
  @HttpsWhoisProtocol
  ProbingSequence<NioSocketChannel> provideHttpsWhoisSequence(
      @HttpsWhoisProtocol ProbingStep<NioSocketChannel> probingStep,
      EventLoopGroup eventLoopGroup) {
    return new ProbingSequence.Builder<NioSocketChannel>()
        .setClass(NioSocketChannel.class)
        .addStep(probingStep)
        .makeFirstRepeated()
        .eventLoopGroup(eventLoopGroup)
        .build();
  }

  @Provides
  @HttpWhoisProtocol
  int provideHttpWhoisPort() {
    return httpWhoIsPort;
  }

  @Provides
  @HttpsWhoisProtocol
  int provideHttpsWhoisPort() {
    return httpsWhoIsPort;
  }

  @Provides
  ImmutableMap<Integer, Protocol> providePortToProtocolMap(
      Set<Protocol> protocolSet) {
    return Maps.uniqueIndex(protocolSet, Protocol::port);
  }



  @Singleton
  @Component(
      modules = {
          ProberModule.class,
          WebWhoisModule.class,
          TokenModule.class
      })
  public interface ProberComponent {

    @HttpWhoisProtocol ProbingSequence<NioSocketChannel> provideHttpWhoisSequence();

    @HttpsWhoisProtocol ProbingSequence<NioSocketChannel> provideHttpsWhoisSequence();

    ImmutableMap<Integer, Protocol> providePortToProtocolMap();

    @WebWhoisProtocol Token provideWebWhoisToken();

  }
}
