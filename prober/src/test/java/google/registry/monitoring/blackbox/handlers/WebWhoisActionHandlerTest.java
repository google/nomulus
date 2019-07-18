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
import static google.registry.monitoring.blackbox.ProbingStep.DEFAULT_ADDRESS;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpResponse;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpGetRequest;
import static google.registry.monitoring.blackbox.TestUtils.makeRedirectResponse;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.NewChannelAction;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.TestServers.WebWhoisServer;
import google.registry.monitoring.blackbox.TestUtils.TestProvider;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import javax.inject.Provider;
import org.joda.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link WebWhoisActionHandler}.
 *
 * <p>Attempts to test how well {@link WebWhoisActionHandler} works
 * when responding to all possible types of responses </p>
 */
@RunWith(JUnit4.class)
public class WebWhoisActionHandlerTest {
  private static final int HTTP_PORT = 80;
  private static final int HTTPS_PORT = 443;
  private static final String HTTP_REDIRECT = "http://";
  private static final String HTTPS_REDIRECT = "https://";
  private static final String REDIRECT_HOST = "www.example.com";
  private static final String REDIRECT_PATH = "/test/path";
  private static final String TARGET_HOST = "whois.nic.tld";
  private static final String DUMMY_URL = "__WILL_NOT_WORK__";
  private static final Duration DEFAULT_DURATION = new Duration(0L);
  private static final String ADDRESS_STRING ="TEST_IDENTIFICATION";

  private LocalAddress address;
  private EmbeddedChannel channel;
  private ActionHandler actionHandler;
  private ProbingAction probingAction;
  private Provider<? extends ChannelHandler> actionHandlerProvider;

  private void generateLocalAddress() {
    address = new LocalAddress(ADDRESS_STRING + System.currentTimeMillis());
  }
  /** Creates default protocol with empty list of handlers and specified other inputs */
  private Protocol createProtocol(String name, int port, String host) {
    return Protocol.builder()
        .name(name)
        .port(port)
        .handlerProviders(ImmutableList.of(actionHandlerProvider))
        .persistentConnection(false)
        .build();
  }

  /** Initializes new WebWhoisActionHandler */
  private void setupActionHandler() {
    actionHandler = new WebWhoisActionHandler();
    actionHandlerProvider = new TestProvider<>(actionHandler);
  }

  /** Sets up testing channel with requisite attributes */
  private void setupChannel(Protocol protocol, HttpRequestMessage outboundMessage) {
    setupProbingActionBasic(
        protocol,
        outboundMessage,
        makeBootstrap(new NioEventLoopGroup(1)));
    channel = new EmbeddedChannel(actionHandler);
    channel.attr(PROTOCOL_KEY).set(protocol);
  }

  private Bootstrap makeBootstrap(EventLoopGroup group) {
    return new Bootstrap()
        .group(group)
        .channel(LocalChannel.class);
  }
  /**Sets up probingAction for when testing redirection */
  private void setupProbingActionBasic(Protocol protocol, HttpRequestMessage outboundMessage, Bootstrap bootstrap) {
    probingAction = NewChannelAction.<LocalChannel>builder()
        .protocol(protocol)
        .outboundMessage(outboundMessage)
        .delay(DEFAULT_DURATION)
        .bootstrap(bootstrap)
        .host(TARGET_HOST)
        .address(DEFAULT_ADDRESS)
        .build();
  }

  private void setupProbingActionAdvanced(Protocol protocol, HttpRequestMessage outboundMessage, Bootstrap bootstrap, String host) {
    probingAction = NewChannelAction.<LocalChannel>builder()
        .protocol(protocol)
        .outboundMessage(outboundMessage)
        .delay(DEFAULT_DURATION)
        .bootstrap(bootstrap)
        .host(host)
        .address(address)
        .build();
  }

  private void setupLocalServer(String redirectInput, String destinationInput, EventLoopGroup group) {
    WebWhoisServer.strippedServer(group, address, redirectInput, destinationInput);
  }

  @Test
  public void testBasic_responseOk() throws Exception {
    //setup
    setupActionHandler();
    Protocol initialProtocol = createProtocol("responseOk", 0);
    generateLocalAddress();
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    setupChannel(initialProtocol, msg);
    //stores future
    ChannelFuture future = actionHandler.apply(makeHttpGetRequest("", ""));


    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = makeHttpResponse(HttpResponseStatus.OK);


    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that we successfully receivved good response and protocol is unchanged
    assertThat(future.isSuccess()).isTrue();
    assertThat(channel.attr(PROTOCOL_KEY).get()).isEqualTo(initialProtocol);
  }

  @Test
  public void testBasic_responseFailure() {
    //setup
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    setupActionHandler();
    Protocol initialProtocol = createProtocol("responseBad", 0);
    generateLocalAddress();
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.apply(makeHttpGetRequest("", ""));

    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = HttpResponseMessage
        .fromResponse(makeHttpResponse(HttpResponseStatus.BAD_REQUEST));

    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that listener is triggered, but event is not success
    assertThat(testPromise.isSuccess()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    //ensures Protocol is the same
    assertThat(channel.attr(PROTOCOL_KEY).get()).isEqualTo(initialProtocol);
  }
    @Test
    public void testBasic_responseError() {
      //setup
      HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
      setupActionHandler();
      Protocol initialProtocol = createProtocol("responseError", 0);
      generateLocalAddress();
      setupChannel(initialProtocol, msg);

      //stores future
      ChannelFuture future = actionHandler.getFuture();
      channel.writeOutbound(msg);

      //setup for checker to ensure future listener isn't triggered to early
      ChannelPromise testPromise = channel.newPromise();
      future.addListener(f -> testPromise.setSuccess());

      FullHttpResponse response = HttpResponseMessage.fromResponse(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, DUMMY_URL, true, false));

      //assesses that future listener isn't triggered yet.
      assertThat(testPromise.isSuccess()).isFalse();

      channel.writeInbound(response);

      //assesses that listener is triggered, and event is success
      assertThat(testPromise.isSuccess()).isTrue();
      assertThat(future.isSuccess()).isTrue();
      //ensures Protocol is the same
      assertThat(channel.attr(PROBING_ACTION_KEY).get()).isEqualTo(probingAction);
  }

  @Test
  public void testBasic_redirectCloseChannel() {
    //setup
    HttpRequestMessage outboundMessage = HttpRequestMessage.fromRequest(makeHttpGetRequest("", ""));
    setupActionHandler();
    Protocol initialProtocol = createProtocol("redirectHttp", 0);
    generateLocalAddress();
    setupChannel(initialProtocol, outboundMessage);

    //stores future
    ChannelFuture future = actionHandler.apply(outboundMessage);

    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = HttpResponseMessage.fromResponse(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, HTTP_REDIRECT + REDIRECT_HOST, true, false));

    //checks that future has not been set to successful or a failure
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //makes sure old channel is shut down when attempting redirection
    assertThat(channel.isActive()).isFalse();


  }

  @Test
  public void testBasic_redirectHost() {
    //setup
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest(TARGET_HOST, ""));
    setupActionHandler();
    Protocol initialProtocol = createProtocol("redirectHttp", HTTP_PORT);
    generateLocalAddress();
    setupChannel(initialProtocol, msg);
    HttpResponse originalResponse = HttpResponseMessage.fromResponse(makeRedirectResponse(HttpResponseStatus.FOUND, HTTPS_REDIRECT + REDIRECT_HOST + REDIRECT_PATH, true, false));


    //store future
    ChannelFuture future = actionHandler.getFuture();
    channel.writeOutbound(msg);


    channel.writeInbound(originalResponse);

    ProbingAction newAction = channel.attr(PROBING_ACTION_KEY).get();

    //gets changed protocol
    Protocol newProtocol = newAction.protocol();

    //ensures that the new protocol has host and port specified by redirection
    assertThat(newProtocol.port()).isEqualTo(HTTPS_PORT);
    assertThat(newAction.host()).isEqualTo(REDIRECT_HOST);
    assertThat(newAction.path()).isEqualTo(REDIRECT_PATH);
  }

  @Test
  public void testAdvanced_responseOk() {
    //setup
    EventLoopGroup group = new NioEventLoopGroup(1);
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest(TARGET_HOST, ""));
    setupActionHandler();
    Protocol initialProtocol = createProtocol("responseOk", 0);
    generateLocalAddress();
    setupProbingActionAdvanced(initialProtocol, msg, makeBootstrap(group), TARGET_HOST);
    setupLocalServer("", TARGET_HOST, group);

    //stores future
    ChannelFuture future = probingAction.call();

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.syncUninterruptibly().isSuccess()).isTrue();
  }

  @Test
  public void testAdvanced_responseFailure() {
    //setup
    EventLoopGroup group = new NioEventLoopGroup(1);
    HttpRequestMessage msg = HttpRequestMessage.fromRequest(makeHttpGetRequest(DUMMY_URL, ""));
    setupActionHandler();
    Protocol initialProtocol = createProtocol("responseOk", 0);
    generateLocalAddress();
    setupProbingActionAdvanced(initialProtocol, msg, makeBootstrap(group), DUMMY_URL);
    setupLocalServer("", TARGET_HOST, group);

    //stores future
    ChannelFuture future = probingAction.call();

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.syncUninterruptibly().isSuccess()).isTrue();
  }

}

