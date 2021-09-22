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

package google.registry.monitoring.blackbox.handler;

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.connection.ProbingAction;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.exception.ConnectionException;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.exception.UndeterminedStateException;
import google.registry.monitoring.blackbox.message.HttpRequestMessage;
import google.registry.monitoring.blackbox.message.HttpResponseMessage;
import google.registry.monitoring.blackbox.message.InboundMessageType;
import google.registry.monitoring.blackbox.module.WebWhoisModule.HttpWhoisProtocol;
import google.registry.monitoring.blackbox.module.WebWhoisModule.HttpsWhoisProtocol;
import google.registry.monitoring.blackbox.module.WebWhoisModule.WebWhoisProtocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.MalformedURLException;
import java.net.URL;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 * Subclass of {@link ActionHandler} that deals with the WebWhois Sequence
 *
 * <p>Main purpose is to verify {@link HttpResponseMessage} received is valid. If the response
 * implies a redirection it follows the redirection until either an Error Response is received, or
 * {@link HttpResponseStatus#OK} is received
 */
public class WebWhoisActionHandler extends ActionHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Dagger injected components necessary for redirect responses: */

  /** {@link Bootstrap} necessary for remaking connection on redirect response. */
  private final Bootstrap bootstrap;

  /** {@link Protocol} for when redirected to http endpoint. */
  private final Protocol httpWhoisProtocol;

  /** {@link Protocol} for when redirected to https endpoint. */
  private final Protocol httpsWhoisProtocol;

  /** {@link HttpRequestMessage} that represents default GET message to be sent on redirect. */
  private final HttpRequestMessage requestMessage;

  @Inject
  public WebWhoisActionHandler(
      @WebWhoisProtocol Bootstrap bootstrap,
      @HttpWhoisProtocol Protocol httpWhoisProtocol,
      @HttpsWhoisProtocol Protocol httpsWhoisProtocol,
      HttpRequestMessage requestMessage) {

    this.bootstrap = bootstrap;
    this.httpWhoisProtocol = httpWhoisProtocol;
    this.httpsWhoisProtocol = httpsWhoisProtocol;
    this.requestMessage = requestMessage;
  }

  /**
   * After receiving {@link HttpResponseMessage}, either notes success and marks future as finished,
   * notes an error in the received {@link URL} and throws a {@link ConnectionException}, received a
   * response indicating a Failure, or receives a redirection response, where it follows the
   * redirects until receiving one of the previous three responses.
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType msg)
      throws FailureException, UndeterminedStateException {

    HttpResponseMessage response = (HttpResponseMessage) msg;

    if (response.status().equals(HttpResponseStatus.OK)) {
      logger.atInfo().log("Received Successful HttpResponseStatus");
      logger.atInfo().log("Response Received: " + response);

      // On success, we always pass message to ActionHandler's channelRead0 method.
      super.channelRead0(ctx, msg);

    } else if (response.status().equals(HttpResponseStatus.MOVED_PERMANENTLY)
        || response.status().equals(HttpResponseStatus.FOUND)) {
      // TODO - Fix checker to better determine when we have encountered a redirection response.

      // Obtain url to be redirected to
      URL url;
      try {
        url = new URL(response.headers().get("Location"));
      } catch (MalformedURLException e) {
        // in case of error, log it, and let ActionHandler's exceptionThrown method deal with it
        throw new FailureException(
            "Redirected Location was invalid. Given Location was: "
                + response.headers().get("Location"));
      }
      // From url, extract new host, port, and path
      String newHost = url.getHost();
      String newPath = url.getPath();

      logger.atInfo().log(
          String.format(
              "Redirected to %s with host: %s, port: %d, and path: %s",
              url, newHost, url.getDefaultPort(), newPath));

      // Construct new Protocol to reflect redirected host, path, and port
      Protocol newProtocol;
      if (url.getProtocol().equals(httpWhoisProtocol.name())) {
        newProtocol = httpWhoisProtocol;
      } else if (url.getProtocol().equals(httpsWhoisProtocol.name())) {
        newProtocol = httpsWhoisProtocol;
      } else {
        throw new FailureException(
            "Redirection Location port was invalid. Given protocol name was: " + url.getProtocol());
      }

      // Obtain HttpRequestMessage with modified headers to reflect new host and path.
      HttpRequestMessage httpRequest = requestMessage.modifyMessage(newHost, newPath);

      // Create new probingAction that takes in the new Protocol and HttpRequestMessage with no
      // delay
      ProbingAction redirectedAction =
          ProbingAction.builder()
              .setBootstrap(bootstrap)
              .setProtocol(newProtocol)
              .setOutboundMessage(httpRequest)
              .setDelay(Duration.ZERO)
              .setHost(newHost)
              .build();

      // close this channel as we no longer need it
      ChannelFuture future = ctx.close();
      future.addListener(
          f -> {
            if (f.isSuccess()) {
              logger.atInfo().log("Successfully closed connection.");
            } else {
              logger.atWarning().log("Channel was unsuccessfully closed.");
            }

            // Once channel is closed, establish new connection to redirected host, and repeat
            // same actions
            ChannelFuture secondFuture = redirectedAction.call();

            // Once we have a successful call, set original ChannelPromise as success to tell
            // ProbingStep we can move on
            secondFuture.addListener(
                f2 -> {
                  if (f2.isSuccess()) {
                    super.channelRead0(ctx, msg);
                  } else {
                    if (f2 instanceof FailureException) {
                      throw new FailureException(f2.cause());
                    } else {
                      throw new UndeterminedStateException(f2.cause());
                    }
                  }
                });
          });
    } else {
      // Add in metrics Handling that informs MetricsCollector the response was a FAILURE
      logger.atWarning().log(String.format("Received unexpected response: %s", response.status()));
      throw new FailureException("Response received from remote site was: " + response.status());
    }
  }
}
