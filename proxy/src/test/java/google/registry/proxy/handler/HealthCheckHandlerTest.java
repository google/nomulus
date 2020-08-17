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

package google.registry.proxy.handler;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link HealthCheckHandler}. */
class HealthCheckHandlerTest {

  private static final String CHECK_REQ = "REQUEST";
  private static final String CHECK_RES = "RESPONSE";

  private final HealthCheckHandler healthCheckHandler =
      new HealthCheckHandler(CHECK_REQ, CHECK_RES);
  private final EmbeddedChannel channel = new EmbeddedChannel(healthCheckHandler);

  @Test
  void testSuccess_ResponseSent() {
    ByteBuf input = Unpooled.wrappedBuffer(CHECK_REQ.getBytes(US_ASCII));
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(input)).isFalse();
    ByteBuf output = channel.readOutbound();
    assertThat(channel.isActive()).isTrue();
    assertThat(output.toString(US_ASCII)).isEqualTo(CHECK_RES);
  }

  @Test
  void testSuccess_IgnoreUnrecognizedRequest() {
    String unrecognizedInput = "1234567";
    ByteBuf input = Unpooled.wrappedBuffer(unrecognizedInput.getBytes(US_ASCII));
    // No inbound message passed to the next handler.
    assertThat(channel.writeInbound(input)).isFalse();
    // No response is sent.
    assertThat(channel.isActive()).isTrue();
    assertThat((Object) channel.readOutbound()).isNull();
  }
}
