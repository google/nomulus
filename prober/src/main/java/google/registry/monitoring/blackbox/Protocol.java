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

package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import io.netty.util.AttributeKey;
import io.netty.channel.ChannelHandler;
import javax.inject.Provider;

@AutoValue
public abstract class Protocol {
  final static AttributeKey<Protocol> PROTOCOL_KEY = AttributeKey.valueOf("PROTOCOL_KEY");

  /** Protocol name. */
  abstract String name();

  /** Port to bind to at remote host*/
  abstract int port();

  abstract String host();

  /** The {@link ChannelHandler} providers to use for the protocol, in order. */
  abstract ImmutableList<Provider<? extends ChannelHandler>> handlerProviders();

  static Protocol.Builder builder() {
    return new AutoValue_Protocol.Builder();
  }

  public static Protocol defaultImplementation(){
    return Protocol.builder().name("Default").port(8080).host("127.0.0.1").handlerProviders(ImmutableList.of()).build();
  }

  @AutoValue.Builder
  public static abstract class Builder {

    abstract Builder name(String value);

    abstract Builder host(String value);

    abstract Builder port(int num);

    abstract Builder handlerProviders(ImmutableList<Provider<? extends ChannelHandler>> providers);

    abstract Protocol build();
  }


}
