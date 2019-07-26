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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.exceptions.ConnectionException;
import google.registry.monitoring.blackbox.exceptions.InternalException;
import google.registry.monitoring.blackbox.exceptions.ResponseException;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 *Superclass of all {@link io.netty.channel.ChannelHandler}s placed at end of channel pipeline
 *
 * <p> {@link ActionHandler} inherits from {@link SimpleChannelInboundHandler< InboundMessageType >}, as it should only be passed in
 * messages that implement the {@link InboundMessageType} interface.</p>
 *
 * <p> The {@link ActionHandler} skeleton exists for a few main purposes. First, it returns a {@link ChannelPromise},
 * which informs the {@link google.registry.monitoring.blackbox.ProbingAction} in charge that a response has been read.
 * Second, with any exception thrown, the connection is closed, and the ProbingAction governing this channel is informed
 * of the error, lastly, given the type of error, the status of the {@link ResponseType} is marked as a {@code FAILURE}
 * or {@code ERROR}. If no exception is thrown and the message reached {@code channelRead0}, then it is marked as {@code SUCCESS}.</p>
 *
 * <p>Subclasses specify further work to be done for specific kinds of channel pipelines. </p>
 */
public abstract class ActionHandler extends SimpleChannelInboundHandler<InboundMessageType> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Three types of responses received down pipeline */
  public enum ResponseType {SUCCESS, FAILURE, ERROR}

  /** Status of response for current {@link ActionHandler} instance */
  private static ResponseType status;

  /** {@link ChannelPromise} that informs {@link google.registry.monitoring.blackbox.ProbingAction} if response has been received. */
  protected ChannelPromise finished;

  /** Returns initialized {@link ChannelPromise} to {@link google.registry.monitoring.blackbox.ProbingAction}.*/
  public ChannelFuture getFuture() {
    return finished;
  }

  /** Initializes the same {@link ChannelPromise}*/
  @Override
  public void handlerAdded(ChannelHandlerContext ctx) {
    // Once handler is added to channel pipeline, initialize channel and future for this handler
    finished = ctx.newPromise();
  }

  /** Initializes the same {@link ChannelPromise} in case current channel is reused (usually for EPP).*/
  public void resetFuture() {
    finished = finished.channel().newPromise();
  }

  /** Marks {@link ResponseType} and {@link ChannelPromise} as success */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType inboundMessage)
      throws ResponseException, InternalException {

    status = ResponseType.SUCCESS;

    if (!finished.isSuccess()) {
      finished.setSuccess();
    }
  }

  /**
   * Logs the channel and pipeline that caused error, closes channel, then informs
   * {@link google.registry.monitoring.blackbox.ProbingAction} listeners of error.
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.atSevere().withCause(cause).log(
        String.format(
            "Attempted Action was unsuccessful with channel: %s, having pipeline: %s",
            ctx.channel().toString(), ctx.channel().pipeline().toString()));

    if (cause instanceof ResponseException) {
      //On ResponseException, we know the response is a failure. As a result,
      //we set the status to FAILURE, then inform the MetricsHandler of this
      status = ResponseType.FAILURE;

      //Since it wasn't a success, we still want to log to see what caused the FAILURE
      logger.atInfo().log(cause.getMessage());

      //As always, inform the ProbingStep that we successfully completed this action
      finished.setSuccess();

    } else if (cause instanceof ConnectionException) {
      //On ConnectionException, we know the response type is an error. As a result,
      //we set the status to ERROR, then inform the MetricsHandler of this
      status = ResponseType.ERROR;

      //Since it wasn't a success, we still log what caused the ERROR
      logger.atInfo().log(cause.getMessage());
      finished.setSuccess();

      //As this was an ERROR in the connection, we must close the channel
      ChannelFuture closedFuture = ctx.channel().close();
      closedFuture.addListener(f -> logger.atInfo().log("Unsuccessful channel connection closed"));

    } else if (cause instanceof InternalException){
      //For an internal error, metrics should not be collected, so we log what caused this, and
      //inform the ProbingStep the Prober had an internal error on this action
      logger.atSevere().withCause(cause).log("Severe internal error");
      finished.setFailure(cause);


      //As this was an internal error, we must close the channel
      ChannelFuture closedFuture = ctx.channel().close();
      closedFuture.addListener(f -> logger.atInfo().log("Unsuccessful channel connection closed"));

    } else {
      //In the case of any other kind of error, we assume it is some type of connection ERROR,
      //so we treat it as such:

      status = ResponseType.ERROR;

      logger.atInfo().log(cause.getMessage());
      finished.setSuccess();

      ChannelFuture closedFuture = ctx.channel().close();
      closedFuture.addListener(f -> logger.atInfo().log("Unsuccessful channel connection closed"));
    }



  }

  /**Only exists for testing purposes to see if {@link ActionHandler} displays the expected status's from responses. */
  @VisibleForTesting
  ResponseType getStatus() {
    return status;
  }
}

