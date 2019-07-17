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

import static google.registry.monitoring.blackbox.ProbingAction.PROBING_ACTION_KEY;

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.NewChannelAction;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Prober;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import google.registry.monitoring.blackbox.messages.InboundMessageType;
import google.registry.monitoring.blackbox.messages.OutboundMessageType;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.MalformedURLException;
import java.net.URL;
import javax.inject.Inject;
import org.joda.time.Duration;

/**
 *Subclass of {@link ActionHandler} that deals with the WebWhois Sequence
 *
 * <p> Main purpose is to verify {@link HttpResponseMessage} received is valid. If the response implies a redirection
 * it follows the redirection until either an Error Response is received, or {@link HttpResponseStatus.OK} is received</p>
 */
public class WebWhoisActionHandler extends ActionHandler {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  public WebWhoisActionHandler() {}


  /**
   * After receiving {@link HttpResponseMessage}, either notes success and marks future as finished, notes an error
   * in the received {@link URL} and throws a {@link ServerSideException}, received a response indicating a Failure,
   * or receives a redirection response, where it follows the redirections until receiving one of the previous three responses
   */
  @Override
  public void channelRead0(ChannelHandlerContext ctx, InboundMessageType msg)
      throws ServerSideException {

    HttpResponseMessage response = (HttpResponseMessage) msg;


    if (response.status() == HttpResponseStatus.OK) {
      logger.atInfo().log("Received Successful HttpResponseStatus");

      finished.setSuccess();

      logger.atInfo().log("Response Received: " + response);

    } else if (response.status() == HttpResponseStatus.FOUND || response.status() == HttpResponseStatus.MOVED_PERMANENTLY) {

      //Obtain url to be redirected to
      URL url;
      try {
        url = new URL(response.headers().get("Location"));
      } catch (MalformedURLException e) {
        //in case of error, log it, and let ActionHandler's exceptionThrown method deal with it
        throw new ServerSideException("Redirected Location was invalid. Given Location was: " + response.headers().get("Location"));
      }
      //From url, extract new host, port, and path
      String newHost = url.getHost();
      String newPath = url.getPath();
      int newPort = url.getDefaultPort();

      logger.atInfo().log(String.format("Redirected to %s with host: %s, port: %d, and path: %s", url, newHost, newPort, newPath));

      //Construct new Protocol to reflect redirected host, path, and port
      Protocol newProtocol = Prober.portToProtocolMap.get(newPort);

      //Obtain old ProbingAction, which we will use as a template for the new one
      ProbingAction oldAction = ctx.channel().attr(PROBING_ACTION_KEY).get();

      //Modify HttpRequestMessage sent to remote host to reflect new path and host
      HttpRequestMessage httpRequest = ((HttpRequestMessage)oldAction.outboundMessage()).setUri(newPath);
      httpRequest.headers().set(HttpHeaderNames.HOST, newHost);

      //Create new probingAction that takes in the new Protocol and HttpRequestMessage with no delay
      ProbingAction redirectedAction = oldAction.toBuilder()
          .protocol(newProtocol)
          .outboundMessage(httpRequest)
          .delay(Duration.ZERO)
          .host(newHost)
          .path(newPath)
          .build();

      //Mainly for testing, to check the probing action was created appropriately
      ctx.channel().attr(PROBING_ACTION_KEY).set(redirectedAction);

      //close this channel as we no longer need it
      ChannelFuture future = ctx.close();
      future.addListener(
          f -> {
            logger.atInfo().log("Successfully Closed Connection");

            //Once channel is closed, establish new connection to redirected host, and repeat same actions
            ChannelFuture secondFuture = redirectedAction.call();

            //Once we have a successful call, set original ChannelPromise as success to tell ProbingStep we can move on
            secondFuture.addListener(f2 -> finished.setSuccess());

          }
      );
    } else {
      //Add in metrics Handling that informs MetricsCollector the response was a FAILURE
      finished.setSuccess();
      logger.atWarning().log(String.format("Received unexpected response: %s", response.status()));

    }
  }
}

