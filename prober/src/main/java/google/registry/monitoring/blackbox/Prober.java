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
import google.registry.monitoring.blackbox.ProberModule.ProberComponent;
import google.registry.monitoring.blackbox.Tokens.Token;
import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Main class of the Prober, which obtains the {@link ProbingSequences}s provided by {@link Dagger} and runs them
 */
public class Prober {

  /** Main {@link Dagger} Component */
  private static ProberComponent proberComponent = DaggerProberModule_ProberComponent.builder().build();

  /** {@link ImmutableMap} of {@code port}s to {@link Protocol}s for WebWhois Redirects */
  public static final ImmutableMap<Integer, Protocol> portToProtocolMap = proberComponent.providePortToProtocolMap();


  public static void main(String[] args) {

    ProbingSequence<NioSocketChannel> httpsSequence = proberComponent.provideHttpsWhoisSequence();
    Token httpsToken = proberComponent.provideWebWhoisToken();

    ProbingSequence<NioSocketChannel> httpSequence = proberComponent.provideHttpWhoisSequence();
    Token httpToken = proberComponent.provideWebWhoisToken();
    httpsSequence.start(httpsToken);
    httpSequence.start(httpToken);
  }
}

