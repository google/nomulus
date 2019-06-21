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

import static com.google.common.truth.Truth.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/** Unit tests for {@link ActionHandler}. */
@RunWith(JUnit4.class)
public class TestActionHandler {

  private final ActionHandler actionHandler = new ActionHandler();
  private EmbeddedChannel channel;

  @Before
  public void setup() {
    channel = new EmbeddedChannel(actionHandler);
  }

  @Test
  public void testHandler_Params() {
    //initial check on Channel Activity
    assertThat(channel.isActive()).isTrue();


    //Ensures channel Handler points to is right what
    assertThat(actionHandler.getChannel()).isEqualTo(channel);
  }

  @Test
  public void testHandler_Behavior() {
    //initial check on Channel Activity
    assertThat(channel.isActive()).isTrue();


    ByteBuf outboundBuffer = Unpooled.copyInt(64);

    //Use ActionHandler's write method to check if the future returned is accurate
    //and that it accurately writes out the inputBuffer
    assertThat(actionHandler.apply(outboundBuffer)).isEqualTo(actionHandler.getFinished());
    assertThat(channel.outboundMessages().poll()).isEqualTo(outboundBuffer);

    //Creates Promise that is set to success when something changes on actionHandler's future
    ChannelPromise testPromise = channel.newPromise();
    actionHandler.getFinished().addListener(f -> testPromise.setSuccess());

    //Ensure that before reading inbound data, actionHandler's future stays inactive
    assertThat(testPromise.isSuccess()).isFalse();

    ByteBuf inputBuffer = Unpooled.copyInt(128);

    //Check that ActionHandler doesn't do anything to inbound Buffer
    assertThat(channel.writeInbound(inputBuffer)).isFalse();


    //ensures that actionHandler's future's listener is active and that it is set to success
    assertThat(testPromise.isSuccess()).isTrue();
    assertThat(actionHandler.getFinished().isSuccess()).isTrue();


  }

}
