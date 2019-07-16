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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.monitoring.blackbox.ProbingAction.PROBING_ACTION_KEY;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpResponse;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpGetRequest;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.NewChannelAction;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link WebWhoisActionHandler}.
 *
 * <p>Attempts to test how well WebWhoIsActionHandler works when responding to
 * all possible types of responses </p>
 *
 * */
@RunWith(JUnit4.class)
public class WebWhoisActionHandlerTest {
  private static final String HTTP_REDIRECT = "http://";
  private static final String REDIRECT_HOST = "www.example.com";
  private static final String TARGET_HOST = "whois.nic.tld";
  private static final Duration DEFAULT_DURATION = new Duration(0L);


  private EmbeddedChannel channel;
  private ActionHandler actionHandler;
  private ProbingAction probingAction;

  /** Creates default protocol with empty list of handlers and specified other inputs */
  private Protocol createProtocol(String name, int port) {
    return Protocol.builder()
        .name(name)
        .port(port)
        .handlerProviders(ImmutableList.of())
        .persistentConnection(false)
        .build();
  }

  /** Initializes new WebWhoisActionHandler */
  private void setupActionHandler() {
    actionHandler = new WebWhoisActionHandler();
  }

  /** Sets up testing channel with requisite attributes */
  private void setupChannel(Protocol protocol, HttpRequestMessage outboundMessage) {
    setupActionHandler();
    setupProbingAction(
        protocol,
        outboundMessage,
        new Bootstrap()
          .group(new NioEventLoopGroup())
          .channel(LocalChannel.class));
    channel = new EmbeddedChannel(actionHandler);
    channel.attr(PROBING_ACTION_KEY).set(probingAction);
  }

  /**Sets up probingAction for when testing redirection */
  private void setupProbingAction(Protocol protocol, HttpRequestMessage outboundMessage, Bootstrap bootstrap) {
    probingAction = NewChannelAction.<LocalChannel>builder()
        .protocol(protocol)
        .outboundMessage(outboundMessage)
        .delay(DEFAULT_DURATION)
        .bootstrap(bootstrap)
        .host(TARGET_HOST)
        .build();
  }



  /** Creates HttpResponse given status, redirection location, and other necessary inputs */
  private static FullHttpResponse makeRedirectResponse(
      HttpResponseStatus status, String location, boolean keepAlive, boolean hsts) {
    FullHttpResponse response = makeHttpResponse("", status);
    response.headers().set("content-type", "text/plain").set("content-length", "0");
    if (location != null) {
      response.headers().set("location", location);
    }
    if (keepAlive) {
      response.headers().set("connection", "keep-alive");
    }
    if (hsts) {
      response.headers().set("Strict-Transport-Security", "max-age=31536000");
    }
    return response;
  }


  @Test
  public void testSuccess_responseOk() throws Exception {
    //setup
    Protocol initialProtocol = createProtocol("responseOk", 0);
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFuture();
    channel.writeOutbound(msg);


    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = HttpResponseMessage.fromResponse(makeHttpResponse(HttpResponseStatus.OK));


    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.isSuccess()).isTrue();
    assertThat(channel.attr(PROBING_ACTION_KEY).get()).isEqualTo(probingAction);
  }

  @Test
  public void testSuccess_responseBad() {
    //setup
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    Protocol initialProtocol = createProtocol("responseBad", 0);
    setupChannel(initialProtocol, msg);



    //stores future
    ChannelFuture future = actionHandler.getFuture();
    channel.writeOutbound(msg);

    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = HttpResponseMessage.fromResponse(makeHttpResponse(HttpResponseStatus.BAD_REQUEST));

    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that listener is triggered, but event is not success
    assertThat(testPromise.isSuccess()).isTrue();
    assertThat(future.isSuccess()).isTrue();

    //ensures Protocol is the same
    assertThat(channel.attr(PROBING_ACTION_KEY).get()).isEqualTo(probingAction);
  }

  @Test
  public void testSuccess_redirectCloseChannel() {
    //setup
    Protocol initialProtocol = createProtocol("redirectHttp", 0);
    HttpRequestMessage outboundMessage = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    setupChannel(initialProtocol, outboundMessage);

    //stores future
    ChannelFuture future = actionHandler.getFuture();
    channel.writeOutbound(outboundMessage);

    FullHttpResponse response = HttpResponseMessage.fromResponse(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, HTTP_REDIRECT + REDIRECT_HOST, true, false));


    channel.writeInbound(response);

    //makes sure old channel is shut down when attempting redirection
    assertThat(channel.isActive()).isFalse();


  }
}
