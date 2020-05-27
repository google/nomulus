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
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.exception.UnrecoverableStateException;
import google.registry.monitoring.blackbox.metric.MetricsCollector;
import google.registry.monitoring.blackbox.token.Token;
import google.registry.util.CircularList;
import google.registry.util.Clock;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;

/**
 * Represents Sequence of {@link ProbingStep}s that the Prober performs in order.
 *
 * <p>Inherits from {@link CircularList}, with element type of {@link ProbingStep} as the manner in
 * which the sequence is carried out is similar to the {@link CircularList}. However, the {@link
 * Builder} of {@link ProbingSequence} override {@link CircularList.AbstractBuilder} allowing for
 * more customized flows, that are looped, but not necessarily entirely circular. Example: first
 * -&gt; second -&gt; third -&gt; fourth -&gt; second -&gt; third -&gt; fourth -&gt; second -&gt;...
 *
 * <p>Created with {@link Builder} where we specify {@link EventLoopGroup}, {@link AbstractChannel}
 * class type, then sequentially add in the {@link ProbingStep.Builder}s in order and mark which one
 * is the first repeated step.
 *
 * <p>{@link ProbingSequence} implicitly points each {@link ProbingStep} to the next one, so once
 * the first one is activated with the requisite {@link Token}, the {@link ProbingStep}s do the rest
 * of the work.
 */
public class ProbingSequence extends CircularList<ProbingStep> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Shared {@link MetricsCollector} used to record metrics on any step performed. */
  private MetricsCollector metrics;

  /** Shared {@link Clock} used to record latency on any step performed. */
  private Clock clock;

  /** Each {@link ProbingSequence} requires a start token to begin running. */
  private Token startToken;

  /**
   * Each {@link ProbingSequence} is considered to not be the last step unless specified by the
   * {@link Builder}.
   */
  private boolean lastStep = false;

  /** {@link ProbingSequence} object that represents first step in the sequence. */
  private ProbingSequence first;

  /**
   * Standard constructor for first {@link ProbingSequence} in the list that assigns value and
   * token.
   */
  private ProbingSequence(
      ProbingStep value, MetricsCollector metrics, Clock clock, Token startToken) {

    super(value);
    this.metrics = metrics;
    this.clock = clock;
    this.startToken = startToken;
  }

  /** Method used in {@link Builder} to mark the last step in the sequence. */
  private void markLast() {
    lastStep = true;
  }

  /** Obtains next {@link ProbingSequence} in sequence. */
  @Override
  public ProbingSequence next() {
    return (ProbingSequence) super.next();
  }

  /** Starts ProbingSequence by calling first {@code runStep} with {@code startToken}. */
  public void start() {
    runStep(startToken);
  }

  /**
   * Generates new {@link ProbingAction} from {@link ProbingStep}, calls the action, then retrieves
   * the result of the action.
   *
   * <p>Calls {@code runNextStep} to have next {@link ProbingSequence} call {@code runStep} with
   * next token depending on if the current step is the last one in the sequence.
   *
   * <p>If unable to generate the action, or the calling the action results in an immediate error,
   * we note an error. Otherwise, if the future marked as finished when the action is completed is
   * marked as a success, we note a success. Otherwise, if the cause of failure will either be a
   * failure or error.
   *
   * @param token - used to generate the {@link ProbingAction} by calling {@code
   *     get().generateAction}.
   */
  private void runStep(Token token) {
    long start = clock.nowUtc().getMillis();

    ProbingAction currentAction;
    ChannelFuture future;

    try {
      // Attempt to generate new action. On error, move on to next step.
      currentAction = get().generateAction(token);

      // Call the generated action.
      future = currentAction.call();

    } catch (UnrecoverableStateException e) {
      // On an UnrecoverableStateException, terminate the sequence.
      logger.atSevere().withCause(e).log("Unrecoverable error in generating or calling action.");

      // Records gathered metrics.
      metrics.recordResult(
          get().protocol().name(),
          get().messageTemplate().name(),
          get().messageTemplate().responseName(),
          MetricsCollector.ResponseType.ERROR,
          clock.nowUtc().getMillis() - start);
      return;

    } catch (Exception e) {
      // On any other type of error, restart the sequence at the very first step.
      logger.atWarning().withCause(e).log("Error in generating or calling action.");

      // Records gathered metrics.
      metrics.recordResult(
          get().protocol().name(),
          get().messageTemplate().name(),
          get().messageTemplate().responseName(),
          MetricsCollector.ResponseType.ERROR,
          clock.nowUtc().getMillis() - start);

      // Restart the sequence at the very first step.
      restartSequence();
      return;
    }

    future.addListener(
        f -> {
          if (f.isSuccess()) {
            // On a successful result, we log as a successful step, and note a success.
            logger.atInfo().log(String.format("Successfully completed Probing Step: %s", this));

            // Records gathered metrics.
            metrics.recordResult(
                get().protocol().name(),
                get().messageTemplate().name(),
                get().messageTemplate().responseName(),
                MetricsCollector.ResponseType.SUCCESS,
                clock.nowUtc().getMillis() - start);
          } else {
            // On a failed result, we log the failure and note either a failure or error.
            logger.atSevere().withCause(f.cause()).log("Did not result in future success");

            // Records gathered metrics as either FAILURE or ERROR depending on future's cause.
            if (f.cause() instanceof FailureException) {
              metrics.recordResult(
                  get().protocol().name(),
                  get().messageTemplate().name(),
                  get().messageTemplate().responseName(),
                  MetricsCollector.ResponseType.FAILURE,
                  clock.nowUtc().getMillis() - start);
            } else {
              metrics.recordResult(
                  get().protocol().name(),
                  get().messageTemplate().name(),
                  get().messageTemplate().responseName(),
                  MetricsCollector.ResponseType.ERROR,
                  clock.nowUtc().getMillis() - start);
            }

            // If not unrecoverable, we restart the sequence.
            if (!(f.cause() instanceof UnrecoverableStateException)) {
              restartSequence();
            }
            // Otherwise, we just terminate the full sequence.
            return;
          }

          if (get().protocol().persistentConnection()) {
            // If the connection is persistent, we store the channel in the token.
            token.setChannel(currentAction.channel());
          }

          // Calls next runStep
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
   * Helper method to restart the sequence at the very first step, with a channel-less {@link
   * Token}.
   */
  private void restartSequence() {
    // Gets next possible token to insure no replicated domains used.
    Token restartToken = startToken.next();

    // Makes sure channel from original token isn't passed down.
    restartToken.setChannel(null);

    // Runs the very first step with starting token.
    first.runStep(restartToken);
  }

  /**
   * Turns {@link ProbingStep.Builder}s into fully self-dependent sequence with supplied {@link
   * Bootstrap}.
   */
  public static class Builder extends CircularList.AbstractBuilder<ProbingStep, ProbingSequence> {

    private ProbingSequence firstRepeatedSequenceStep;

    private Token startToken;

    private MetricsCollector metrics;

    private Clock clock;

    /**
     * This Builder must also be supplied with a {@link Token} to construct a {@link
     * ProbingSequence}.
     */
    public Builder(Token startToken, MetricsCollector metrics, Clock clock) {
      this.startToken = startToken;
      this.metrics = metrics;
      this.clock = clock;
    }

    /** We take special note of the first repeated step. */
    public Builder markFirstRepeated() {
      firstRepeatedSequenceStep = current;
      return this;
    }

    @Override
    public Builder add(ProbingStep value) {
      super.add(value);
      current.first = first;

      return this;
    }

    @Override
    protected ProbingSequence create(ProbingStep value) {
      return new ProbingSequence(value, metrics, clock, startToken);
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
