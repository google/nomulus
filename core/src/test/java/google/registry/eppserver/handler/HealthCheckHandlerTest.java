// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver.handler;

import static com.google.common.truth.Truth.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HealthCheckHandlerTest {

  @Test
  void testHealthCheck_success() {
    HealthCheckHandler handler = new HealthCheckHandler("ping", "pong");
    EmbeddedChannel channel = new EmbeddedChannel(handler);

    ByteBuf pingMsg = Unpooled.wrappedBuffer("ping".getBytes(StandardCharsets.US_ASCII));
    channel.writeInbound(pingMsg);

    ByteBuf response = channel.readOutbound();
    assertThat(response.toString(StandardCharsets.US_ASCII)).isEqualTo("pong");
    assertThat(channel.isOpen()).isTrue();
  }

  @Test
  void testHealthCheck_ignoresOtherMessages() {
    HealthCheckHandler handler = new HealthCheckHandler("ping", "pong");
    EmbeddedChannel channel = new EmbeddedChannel(handler);

    ByteBuf otherMsg = Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.US_ASCII));
    channel.writeInbound(otherMsg);

    ByteBuf response = channel.readOutbound();
    assertThat(response).isNull();
    assertThat(channel.isOpen()).isTrue();
  }
}
