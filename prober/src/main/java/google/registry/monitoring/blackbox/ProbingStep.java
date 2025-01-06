// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.google.auto.value.AutoBuilder;
import com.google.auto.value.AutoValue;
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.OutboundMessageType;
import google.registry.monitoring.blackbox.token.Token;
import io.netty.bootstrap.Bootstrap;
import org.joda.time.Duration;

/**
 * {@link AutoValue} class that represents generator of actions performed at each step in {@link
 * ProbingSequence}.
 *
 * <p>Holds the unchanged components in a given step of the {@link ProbingSequence}, which are the
 * {@link OutboundMessageType}, {@link Protocol}, {@link Duration}, and {@link Bootstrap} instances.
 * It then modifies these components on each loop iteration with the consumed {@link Token} and from
 * that, generates a new {@link ProbingAction} to call.
 *
 * @param duration Time delay duration between actions.
 * @param protocol {@link Protocol} type for this step.
 * @param messageTemplate {@link OutboundMessageType} instance that serves as template to be
 *     modified by {@link Token}.
 * @param bootstrap {@link Bootstrap} instance provided by parent {@link ProbingSequence} that
 *     allows for creation of new channels.
 */
public record ProbingStep(
    Duration duration,
    Protocol protocol,
    OutboundMessageType messageTemplate,
    Bootstrap bootstrap) {

  public static Builder builder() {
    return new AutoBuilder_ProbingStep_Builder();
  }

  /**
   * Generates a new {@link ProbingAction} from {@code token} modified {@link OutboundMessageType}
   */
  public ProbingAction generateAction(Token token) throws UndeterminedStateException {
    OutboundMessageType message = token.modifyMessage(messageTemplate());
    ProbingAction.Builder probingActionBuilder =
        ProbingAction.builder()
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
  public String toString() {
    return String.format(
        """
            ProbingStep with Protocol: %s
            OutboundMessage: %s
            """,
        protocol(), messageTemplate().getClass().getName());
  }

  /** Builder for {@link ProbingStep}. */
  @AutoBuilder
  public interface Builder {

    Builder setDuration(Duration value);

    Builder setProtocol(Protocol value);

    Builder setMessageTemplate(OutboundMessageType value);

    Builder setBootstrap(Bootstrap value);

    ProbingStep build();
  }
}
