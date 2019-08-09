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
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
import google.registry.monitoring.blackbox.tokens.Token;
import google.registry.util.CircularList;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

/**
 * Represents Sequence of {@link ProbingStep}s that the Prober performs in order.
 *
 * <p>Inherits from {@link CircularList}</p>, with element type of
 * {@link ProbingStep} as the manner in which the sequence is carried out is analogous to the {@link
 * CircularList}
 *
 *
 * <p>Created with {@link Builder} where we specify {@link EventLoopGroup}, {@link AbstractChannel}
 * class type, then sequentially add in the {@link ProbingStep.Builder}s in order and mark which one
 * is the first repeated step.</p>
 *
 * <p>{@link ProbingSequence} implicitly points each {@link ProbingStep} to the next one, so once
 * the first one is activated with the requisite {@link Token}, the {@link ProbingStep}s do the rest
 * of the work.</p>
 */
public class ProbingSequence extends CircularList<ProbingStep> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /**
   * Each {@link ProbingSequence} requires a start token to begin running.
   */
  private Token startToken;

  /**
   * Each {@link ProbingSequence} is considered to not be the last step unless specified by the
   * {@link Builder}.
   */
  private boolean lastStep = false;

  /**
   * Standard constructor for {@link ProbingSequence} that assigns value to the node.
   */
  private ProbingSequence(ProbingStep value) {
    super(value);
  }

  /**
   * Constructor for first {@link ProbingSequence} in the list that assigns value and token.
   */
  private ProbingSequence(ProbingStep value, Token startToken) {
    this(value);
    this.startToken = startToken;
  }

  /**
   * Method used in {@link Builder} to mark the last step in the sequence.
   */
  private void markLast() {
    lastStep = true;
  }

  /**
   * Obtains next {@link ProbingSequence} in sequence instead of next {@link CircularList}.
   */
  @Override
  public ProbingSequence next() {
    return (ProbingSequence) super.next();
  }

  /**
   * Starts ProbingSequence by calling first {@code runStep} with {@code startToken}.
   */
  public void start() {
    runStep(startToken);
  }

  /**
   * Generates new {@link ProbingAction} from {@link ProbingStep}, calls the action, then retrieves
   * the result of the action.
   *
   * @param token - used to generate the {@link ProbingAction} by calling {@code
   * get().generateAction}.
   *
   * <p>Calls {@code runNextStep} to have next {@link ProbingSequence} call {@code runStep}
   * with next token depending on if the current step is the last one in the sequence.
   *
   * <p>If unable to generate the action, or the calling the action results in an immediate error,
   * we note an error. Otherwise, if the future marked as finished when the action is completed is
   * marked as a success, we note a success. Otherwise, if the cause of failure will either be a
   * failure or error. </p>
   */
  private void runStep(Token token) {
    ProbingAction currentAction;
    //attempt to generate new action. On error, move on to next step
    try {
      currentAction = get().generateAction(token);
    } catch (UndeterminedStateException e) {
      logger.atWarning().withCause(e).log("Error in Action Generation");
      runNextStep(token);
      return;
    }

    ChannelFuture future;
    try {
      //call the generated action
      future = currentAction.call();
    } catch (Exception e) {
      //On error in calling action, log error and note an error
      logger.atWarning().withCause(e).log("Error in Action Performed");

      //Calls next runStep
      runNextStep(token);
      return;
    }

    future.addListener(f -> {
      if (f.isSuccess()) {
        //On a successful result, we log as a successful step, and not a success
        logger.atInfo().log(String.format("Successfully completed Probing Step: %s", this));

      } else {
        //On a failed result, we log the failure and note either a failure or error
        logger.atSevere().withCause(f.cause()).log("Did not result in future success");
      }

      if (get().protocol().persistentConnection()) {
        //If the connection is persistent, we store the channel in the token

        token.setChannel(currentAction.channel());
      }

      //Calls next runStep
      runNextStep(token);


    });
  }

  /**
   * Helper method to first generate the next token, then call runStep on the next {@link
   * ProbingSequence}.
   */
  private void runNextStep(Token token) {
    token = lastStep ? token.next() : token;
    next().runStep(token);

  }

  /**
   * Turns {@link ProbingStep.Builder}s into fully self-dependent sequence with supplied {@link
   * Bootstrap}.
   */
  public static class Builder extends CircularList.AbstractBuilder<ProbingStep, ProbingSequence> {

    private ProbingSequence firstRepeatedSequenceStep;

    private Token startToken;

    /**
     * This Builder must also be supplied with a {@link Token} to construct a {@link
     * ProbingSequence}.
     */
    public Builder(Token startToken) {
      this.startToken = startToken;
    }

    /**
     * We take special note of the first repeated step.
     */
    public Builder markFirstRepeated() {
      firstRepeatedSequenceStep = current;
      return this;
    }

    @Override
    public Builder add(ProbingStep value) {
      super.add(value);
      return this;
    }

    @Override
    protected ProbingSequence create(ProbingStep value) {
      if (first == null) {
        return new ProbingSequence(value, startToken);
      } else {
        return new ProbingSequence(value);
      }
    }

    /**
     * Points last {@link ProbingStep} to the {@code firstRepeatedSequenceStep} and calls private
     * constructor to create {@link ProbingSequence}.
     */
    @Override
    public ProbingSequence build() {
      if (firstRepeatedSequenceStep == null) {
        firstRepeatedSequenceStep = first;
      }

      current.markLast();
      current.setNext(firstRepeatedSequenceStep);
      return first;
    }
  }
}
