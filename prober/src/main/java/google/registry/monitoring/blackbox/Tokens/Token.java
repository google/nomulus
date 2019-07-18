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

package google.registry.monitoring.blackbox.Tokens;

import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.Channel;

/**
 * Superclass that represents information passed to each {@link ProbingStep}
 * a given loop in a {@link ProbingSequence}.
 *
 * <p>Should modify the message passed in to reflect the current host, should pass down
 * a channel if a persistent connection exists in the sequence, and should be able to obtain
 * the next Token when moving on to the next iteration of a loop in the sequence.</p>
 */
public abstract class Token {

  protected Channel channel;

  public abstract Token next();

  public abstract OutboundMessageType modifyMessage(OutboundMessageType message)
      throws InternalException;

  public abstract String getHost();

  public void channel(Channel channel) {
    this.channel = channel;
  }

  public Channel channel() {
    return this.channel;
  }
}
