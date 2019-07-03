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

import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;

import com.google.common.flogger.FluentLogger;
import google.registry.monitoring.blackbox.NewChannelAction;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Prober;
import google.registry.monitoring.blackbox.Protocol;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URL;
import javax.inject.Inject;
import org.joda.time.Duration;

public class WebWhoisActionHandler extends ActionHandler<HttpResponse, HttpRequest> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Inject
  public WebWhoisActionHandler() {}

  /** Method needed for workaround in order to create ProbingAction builder with Channel type specified by the current channel type */
  private <C extends AbstractChannel> NewChannelAction.Builder<HttpRequest, C> createBuilder(
      Class<? extends Channel> className, ProbingAction<HttpRequest> currentAction) {
    return ((NewChannelAction<HttpRequest, C>) currentAction).toBuilder();
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpResponse response) throws Exception{
    if (response.status() == HttpResponseStatus.OK) {
      logger.atInfo().log("Recieved Successful HttpResponseStatus");
      finished.setSuccess();
      System.out.println(response);

    } else if (response.status() == HttpResponseStatus.FOUND || response.status() == HttpResponseStatus.MOVED_PERMANENTLY) {

      //Obtain url to be redirected to
      URL url = new URL(response.headers().get("Location"));

      //From url, extract new host, port, and path
      String newHost = url.getHost();
      String newPath = url.getPath();
      int newPort = url.getDefaultPort();

      logger.atInfo().log(String.format("Redirected to %s with host: %s, port: %d, and path: %s", url, newHost, newPort, newPath));

      Protocol oldProtocol = ctx.channel().attr(PROTOCOL_KEY).get();

      //Build new Protocol from new attributes
      ProbingAction<HttpRequest> currentAction = (ProbingAction<HttpRequest>) oldProtocol.probingAction();

      //Construct new Protocol to reflect redirected host, path, and port
      Protocol newProtocol = Prober.portToProtocolMap.get(newPort).toBuilder().build()
          .host(newHost)
          .path(newPath);

      //Modify HttpRequest sent to remote host to reflect new path and host
      FullHttpRequest httpRequest = ((DefaultFullHttpRequest) currentAction.outboundMessage()).setUri(newPath);
      httpRequest.headers().set(HttpHeaderNames.HOST, newHost);

      //Create new probingAction that takes in the new Protocol and HttpRequest message
      ProbingAction<HttpRequest> redirectedAction = createBuilder(ctx.channel().getClass(), currentAction)
          .protocol(newProtocol)
          .outboundMessage(httpRequest)
          .delay(Duration.ZERO)
          .build();

      oldProtocol.probingAction(redirectedAction);
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
      finished.setFailure(new RuntimeException());
      logger.atWarning().log(String.format("Received Response: %s", response.status()));

    }
  }
}

