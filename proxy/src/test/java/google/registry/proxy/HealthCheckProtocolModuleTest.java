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

package google.registry.proxy;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** End-to-end tests for {@link HealthCheckProtocolModule}. */
class HealthCheckProtocolModuleTest extends ProtocolModuleTest {

  HealthCheckProtocolModuleTest() {
    super(TestComponent::healthCheckHandlers);
  }

  @Test
  void testSuccess_expectedInboundMessage() {
    // no inbound message passed along.
    assertThat(
            channel.writeInbound(
                Unpooled.wrappedBuffer(PROXY_CONFIG.healthCheck.checkRequest.getBytes(US_ASCII))))
        .isFalse();
    ByteBuf outputBuffer = channel.readOutbound();
    // response written to channel.
    assertThat(outputBuffer.toString(US_ASCII)).isEqualTo(PROXY_CONFIG.healthCheck.checkResponse);
    assertThat(channel.isActive()).isTrue();
    // nothing more to write.
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test
  void testSuccess_InboundMessageTooShort() {
    String shortRequest = "HEALTH_CHECK";
    // no inbound message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(shortRequest.getBytes(US_ASCII))))
        .isFalse();
    // nothing to write.
    assertThat(channel.isActive()).isTrue();
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test
  void testSuccess_InboundMessageTooLong() {
    String longRequest = "HEALTH_CHECK_REQUEST HELLO";
    // no inbound message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(longRequest.getBytes(US_ASCII))))
        .isFalse();
    ByteBuf outputBuffer = channel.readOutbound();
    // The fixed length frame decoder will decode the first inbound message as "HEALTH_CHECK_
    // REQUEST", which is what this handler expects. So it will respond with the pre-defined
    // response message. This is an acceptable false-positive because the GCP health checker will
    // only send the pre-defined request message. As long as the health check can receive the
    // request it expects, we do not care if the protocol also respond to other requests.
    assertThat(outputBuffer.toString(US_ASCII)).isEqualTo(PROXY_CONFIG.healthCheck.checkResponse);
    assertThat(channel.isActive()).isTrue();
    // nothing more to write.
    assertThat((Object) channel.readOutbound()).isNull();
  }

  @Test
  void testSuccess_InboundMessageNotMatch() {
    String invalidRequest = "HEALTH_CHECK_REQUESX";
    // no inbound message passed along.
    assertThat(channel.writeInbound(Unpooled.wrappedBuffer(invalidRequest.getBytes(US_ASCII))))
        .isFalse();
    // nothing to write.
    assertThat(channel.isActive()).isTrue();
    assertThat((Object) channel.readOutbound()).isNull();
  }
}
