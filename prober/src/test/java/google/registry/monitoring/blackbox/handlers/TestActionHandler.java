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

package google.registry.monitoring.blackbox.handlers;

import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import io.netty.channel.ChannelHandlerContext;

/**
 * Concrete implementation of {@link ActionHandler} that does nothing different from parent class
 * other than store and return the {@code inboundMessage}
 */
public class TestActionHandler extends ActionHandler {

  private String receivedMessage;

  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType inboundMessage)
      throws FailureException, UndeterminedStateException {
    receivedMessage = inboundMessage.toString();
    super.channelRead0(ctx, inboundMessage);
  }

  @Override
  public String toString() {
    return receivedMessage;
  }

}

