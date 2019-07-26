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
import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.tokens.Token;
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import java.util.function.Consumer;
import org.joda.time.Duration;

/**
 * {@link AutoValue} class that represents generator of actions performed at each step
 * in {@link ProbingSequence}.
 *
 * <p>Holds the unchanged components in a given step of the {@link ProbingSequence}, which are
 * the {@link OutboundMessageType}, {@link Protocol}, {@link Duration}, and {@link Bootstrap} instances.
 * It then modifies these components on each loop iteration with the consumed {@link Token} and from that,
 * generates a new {@link ProbingAction} to call.</p>
 *
 */
@AutoValue
public abstract class ProbingStep implements Consumer<Token> {


  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Necessary boolean to inform when to obtain next {@link Token}*/
  private boolean isLastStep = false;
  private ProbingStep nextStep;

  abstract Duration duration();
  abstract Protocol protocol();
  abstract OutboundMessageType messageTemplate();
  abstract Bootstrap bootstrap();


  @AutoValue.Builder
  public static abstract class Builder {
    public abstract Builder setDuration(Duration value);

    public abstract Builder setProtocol(Protocol value);

    public abstract Builder setMessageTemplate(OutboundMessageType value);

    public abstract Builder setBootstrap(Bootstrap value);

    public abstract ProbingStep build();
  }

  public static Builder builder() {
    return new AutoValue_ProbingStep.Builder();
  }

  void lastStep() {
    isLastStep = true;
  }

  void nextStep(ProbingStep step) {
    this.nextStep = step;
  }

  ProbingStep nextStep() {
    return this.nextStep;
  }

  /** Generates a new {@link ProbingAction} from {@code token} modified {@link OutboundMessageType} */
  private ProbingAction generateAction(Token token) throws InternalException {
    OutboundMessageType message = token.modifyMessage(messageTemplate());
    ProbingAction.Builder probingActionBuilder = ProbingAction.builder()
        .setDelay(duration())
        .setProtocol(protocol())
        .setOutboundMessage(message)
        .setHost(token.getHost())
        .setBootstrap(bootstrap());

    if (token.channel() != null)
      probingActionBuilder.setChannel(token.channel());

    return probingActionBuilder.build();
  }


  /** On the last step, gets the next {@link Token}. Otherwise, uses the same one. */
  private Token generateNextToken(Token token) {
    return (isLastStep) ? token.next() : token;
  }

  @Override
  public void accept(Token token) {
    ProbingAction currentAction;
    //attempt to generate new action. On error, move on to next step
    try {
      currentAction = generateAction(token);
    } catch(InternalException e) {
      logger.atWarning().withCause(e).log("Error in Action Generation");
      nextStep.accept(generateNextToken(token));
      return;
    }



    //call the created action
    ChannelFuture future;

    try {
      future = currentAction.call();

    } catch(InternalException e) {
      logger.atWarning().withCause(e).log("Error in Action Performed");
      nextStep.accept(generateNextToken(token));
      return;
    }


    //On result, either log success and move on, or
    future.addListener(f -> {
      if (f.isSuccess()) {
        logger.atInfo().log(String.format("Successfully completed Probing Step: %s", this));
        //If the next step maintains the connection, pass on the channel from this
      } else {
        logger.atSevere().withCause(f.cause()).log("Did not result in future success");
      }
      if (protocol().persistentConnection())
        token.setChannel(currentAction.channel());

      nextStep.accept(generateNextToken(token));


    });
  }

  @Override
  public String toString() {
    return String.format("ProbingStep with Protocol: %s\n" +
        "OutboundMessage: %s\n",
        protocol(),
        messageTemplate().getClass().getName());
  }

}


