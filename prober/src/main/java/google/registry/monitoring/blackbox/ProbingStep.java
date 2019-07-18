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

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.Tokens.Token;
import google.registry.monitoring.blackbox.exceptions.EppClientException;
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.local.LocalAddress;
import java.io.IOException;
import java.util.function.Consumer;
import org.joda.time.Duration;

/**
 * Represents generator of actions performed at each step in {@link ProbingSequence}
 *
 * @param <C> See {@code C} in {@link ProbingSequence}
 *
 * <p>Holds the unchanged components in a given step of the {@link ProbingSequence}, which are
 * the {@link OutboundMessageType} and {@link Protocol} instances. It then modifies
 * these components on each loop iteration with the consumed {@link Token} and from that,
 * generates new {@link ProbingAction} to perform<./p>
 *
 * <p>Subclasses specify {@link Protocol} and {@link OutboundMessageType} of the {@link ProbingStep}</p>
 */
public abstract class ProbingStep<C extends AbstractChannel> implements Consumer<Token> {

  public static final LocalAddress DEFAULT_ADDRESS = new LocalAddress("DEFAULT_ADDRESS_CHECKER");
  protected static final Duration DEFAULT_DURATION = new Duration(2000L);
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Default {@link LocalAddress} when not initialized in {@code Builder} */
  protected LocalAddress address = DEFAULT_ADDRESS;

  /** Necessary boolean to inform when to obtain next {@link Token}*/
  private boolean isLastStep = false;
  private ProbingStep<C> nextStep;
  private ProbingSequence<C> parent;

  protected Duration duration;

  protected final Protocol protocol;
  protected final OutboundMessageType message;

  protected ProbingStep(Protocol protocol, OutboundMessageType message) {
    this.protocol = protocol;
    this.message = message;
  }

  private OutboundMessageType message() {
    return message;
  }

  Protocol protocol() {
    return protocol;
  }


  void lastStep() {
    isLastStep = true;
  }

  void nextStep(ProbingStep<C> step) {
    this.nextStep = step;
  }

  ProbingStep<C> nextStep() {
    return this.nextStep;
  }

  ProbingStep<C> parent(ProbingSequence<C> parent) {
    this.parent = parent;
    return this;
  }

  /** Generates a new {@link ProbingAction} from token modified message and {@link Protocol} */
  private ProbingAction generateAction(Token token) throws InternalException {
    ProbingAction generatedAction;

    OutboundMessageType message = token.modifyMessage(message());

    //Depending on whether token passes a channel, we make a NewChannelAction or ExistingChannelAction
    if (protocol().persistentConnection() && token.channel() != null) {
      generatedAction = ExistingChannelAction.builder()
          .delay(duration)
          .protocol(protocol())
          .outboundMessage(message)
          .host(token.getHost())
          .channel(token.channel())
          .build();
    } else {
      generatedAction = NewChannelAction.<C>builder()
          .delay(duration)
          .protocol(protocol())
          .outboundMessage(message)
          .host(token.getHost())
          .bootstrap(parent.getBootstrap())
          .address(address)
          .build();

    }
    return generatedAction;
  }


  /** On the last step, get the next {@link Token}. Otherwise, use the same one. */
  private Token generateNextToken(Token token) {
    return (isLastStep) ? token.next() : token;
  }

  @Override
  public void accept(Token token) {
    ProbingAction nextAction;
    //attempt to generate new action. On error, move on to next step
    try {
      nextAction = generateAction(token);
    } catch(InternalException e) {
      logger.atWarning().withCause(e).log("Error in Action Generation");
      nextStep.accept(generateNextToken(token));
      return;
    }

    //If the next step maintains the connection, pass on the channel from this
    if (protocol().persistentConnection()) {
      token.channel(nextAction.channel());
    }

    //call the created action
    ChannelFuture future = nextAction.call();

    //On result, either log success and move on, or
    future.addListener(f -> {
      if (f.isSuccess()) {
        logger.atInfo().log(String.format("Successfully completed Probing Step: %s", this));
        nextStep.accept(generateNextToken(token));
      } else {
        logger.atSevere().withCause(f.cause()).log("Did not result in future success");
      }
    });
  }

  @Override
  public String toString() {
    return String.format("ProbingStep with Protocol: %s\n" +
        "OutboundMessage: %s\n" +
        "and parent sequence: %s",
        protocol(),
        message(),
        parent);
  }

}


