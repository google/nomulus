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

package google.registry.monitoring.blackbox.handler;

import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.EppResponseMessage;
import google.registry.monitoring.blackbox.message.InboundMessageType;
import io.netty.channel.ChannelHandlerContext;
import jakarta.inject.Inject;

/**
 * Subclass of {@link ActionHandler} that deals with the Epp Sequence
 *
 * <p>Main purpose is to verify {@link EppResponseMessage} received is valid. If not it throws the
 * requisite error which is dealt with by the parent {@link ActionHandler}
 */
public class EppActionHandler extends ActionHandler {

  @Inject
  public EppActionHandler() {}

  /**
   * Decodes the received response to ensure that it is what we expect and resets future in case
   * {@link EppActionHandler} is reused.
   *
   * @throws FailureException if we receive a failed response from the server
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType msg)
      throws FailureException, UndeterminedStateException {
    EppResponseMessage response = (EppResponseMessage) msg;

    // Based on the expected response type, will throw ResponseFailure if we don't receive a
    // successful EPP response.
    response.verify();
    super.channelRead0(ctx, msg);

    // Reset future as there is potential to reuse same ActionHandler for a different ProbingAction
    finished = ctx.channel().newPromise();
  }
}
