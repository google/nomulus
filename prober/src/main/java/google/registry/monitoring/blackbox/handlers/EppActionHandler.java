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

import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.messages.EppResponseMessage;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import io.netty.channel.ChannelHandlerContext;
import javax.inject.Inject;

/**
 *Subclass of {@link ActionHandler} that deals with the Epp Sequence
 *
 * <p> Main purpose is to verify {@link EppResponseMessage} received is valid. If not it throws
 * the requisite error which is dealt with by the parent {@link ActionHandler}</p>
 */
public class EppActionHandler extends ActionHandler {

  @Inject
  public EppActionHandler() {}

  /**
   * Decodes the received response to ensure that it is what we expect
   *
   * @throws FailureException if we receive a failed response from the server
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType msg) throws FailureException, UndeterminedStateException {
    EppResponseMessage response = (EppResponseMessage) msg;

    //Based on the expected response type, will throw ResponseFailure if we don't receive a successful EPP response
    response.verify();
    super.channelRead0(ctx, msg);
  }


}
