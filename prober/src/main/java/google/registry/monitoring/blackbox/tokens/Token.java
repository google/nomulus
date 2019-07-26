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

package google.registry.monitoring.blackbox.tokens;

import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.Channel;

/**
 * Superclass that represents information passed to each {@link google.registry.monitoring.blackbox.ProbingStep}
 * in a single loop of a {@link google.registry.monitoring.blackbox.ProbingSequence}.
 *
 * <p>Modifies the message passed in to reflect information relevant to a single loop
 * in a {@link google.registry.monitoring.blackbox.ProbingSequence}. Additionally, passes
 * on channel that remains unchanged within a loop of the sequence.</p>
 *
 * <p>Also obtains the next {@link Token} corresponding to the next iteration of a loop
 * in the sequence.</p>
 */
public abstract class Token {

  /** {@link Channel} that always starts out as null. */
  protected Channel channel;

  /** Obtains next {@link Token} for next loop in sequence. */
  public abstract Token next();

  /** String corresponding to host that is relevant for loop in sequence. */
  public abstract String getHost();

  /** Modifies the {@link OutboundMessageType} in the manner necessary for each loop */
  public abstract OutboundMessageType modifyMessage(OutboundMessageType messageType) throws InternalException;

  /** Set method for {@code channel} */
  public void setChannel(Channel channel) {
    this.channel = channel;
  }

  /** Get method for {@code channel}. */
  public Channel channel() {
    return this.channel;
  }
}
