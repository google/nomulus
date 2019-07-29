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

package google.registry.monitoring.blackbox;

import com.google.auto.value.AutoValue;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.tokens.Token;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.bootstrap.Bootstrap;
import org.joda.time.Duration;

/**
 * {@link AutoValue} class that represents generator of actions performed at each step in {@link
 * ProbingSequence}.
 *
 * <p>Holds the unchanged components in a given step of the {@link ProbingSequence}, which are
 * the {@link OutboundMessageType}, {@link Protocol}, {@link Duration}, and {@link Bootstrap}
 * instances. It then modifies these components on each loop iteration with the consumed {@link
 * Token} and from that, generates a new {@link ProbingAction} to call.</p>
 */
@AutoValue
public abstract class ProbingStep {

  public static Builder builder() {
    return new AutoValue_ProbingStep.Builder();
  }

  /**
   * Time delay duration between actions.
   */
  abstract Duration duration();

  /**
   * {@link Protocol} type for this step.
   */
  abstract Protocol protocol();

  /**
   * {@link OutboundMessageType} instance that serves as template to be modified by {@link Token}.
   */
  abstract OutboundMessageType messageTemplate();

  /**
   * {@link Bootstrap} instance provided by parent {@link ProbingSequence} that allows for creation
   * of new channels.
   */
  abstract Bootstrap bootstrap();

  /**
   * Generates a new {@link ProbingAction} from {@code token} modified {@link OutboundMessageType}
   */
  public ProbingAction generateAction(Token token) throws UndeterminedStateException {
    OutboundMessageType message = token.modifyMessage(messageTemplate());
    ProbingAction.Builder probingActionBuilder = ProbingAction.builder()
        .setDelay(duration())
        .setProtocol(protocol())
        .setOutboundMessage(message)
        .setHost(token.host());

    if (token.channel() != null) {
      probingActionBuilder.setChannel(token.channel());
    } else {
      probingActionBuilder.setBootstrap(bootstrap());
    }

    return probingActionBuilder.build();
  }

  @Override
  public final String toString() {
    return String.format("ProbingStep with Protocol: %s\n"
            + "OutboundMessage: %s\n",
        protocol(),
        messageTemplate().getClass().getName());
  }

  /**
   * Standard {@link AutoValue.Builder} for {@link ProbingStep}.
   */
  @AutoValue.Builder
  public abstract static class Builder {

    public abstract Builder setDuration(Duration value);

    public abstract Builder setProtocol(Protocol value);

    public abstract Builder setMessageTemplate(OutboundMessageType value);

    public abstract Builder setBootstrap(Bootstrap value);

    public abstract ProbingStep build();
  }
}

