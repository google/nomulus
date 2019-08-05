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
import static google.registry.monitoring.blackbox.ProbingAction.CONNECTION_FUTURE_KEY;
import static google.registry.monitoring.blackbox.Protocol.PROTOCOL_KEY;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpResponse;
import static google.registry.monitoring.blackbox.TestUtils.makeHttpGetRequest;
import static google.registry.monitoring.blackbox.TestUtils.makeRedirectResponse;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.TestServers.WebWhoisServer;
import google.registry.monitoring.blackbox.TestUtils.TestProvider;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.exceptions.UndeterminedStateException;
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
import io.netty.handler.codec.http.FullHttpResponse;
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
  private static final Protocol STANDARD_PROTOCOL = Protocol.builder()
      .setHandlerProviders(ImmutableList.of())
      .setName("http")
      .setPersistentConnection(false)
      .setPort(HTTPS_PORT)
      .build();


  private LocalAddress address;
  private EmbeddedChannel channel;
  private ActionHandler actionHandler;
  private ProbingAction probingAction;
  private Provider<? extends ChannelHandler> actionHandlerProvider;

  private String generateLocalAddress(String addressString) {
    String modifiedAddress = addressString + System.currentTimeMillis();
    address = new LocalAddress(modifiedAddress);
    return modifiedAddress;
  }

  /** Creates default protocol with empty list of handlers and specified other inputs */
  private Protocol createProtocol(String name, int port, boolean persistentConnection) {
    return Protocol.builder()
        .setName(name)
        .setPort(port)
        .setHandlerProviders(ImmutableList.of(actionHandlerProvider))
        .setPersistentConnection(persistentConnection)
        .build();
  }

  /** Initializes new WebWhoisActionHandler */
  private void setupActionHandler(Bootstrap bootstrap, HttpRequestMessage messageTemplate) {
    actionHandler = new WebWhoisActionHandler(
        bootstrap,
        STANDARD_PROTOCOL,
        STANDARD_PROTOCOL,
        messageTemplate
    );
    actionHandlerProvider = new TestProvider<>(actionHandler);
  }

  /** Sets up testing channel with requisite attributes */
  private void setupChannel(Protocol protocol, HttpRequestMessage outboundMessage) {
    channel = new EmbeddedChannel(actionHandler);
    channel.attr(PROTOCOL_KEY).set(protocol);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());
    setupProbingActionWithChannel(protocol, outboundMessage);
  }

  private Bootstrap makeBootstrap(EventLoopGroup group) {
    return new Bootstrap()
        .group(group)
        .channel(LocalChannel.class);
  }
  /**Sets up probingAction for when testing redirection */
  private void setupProbingActionWithChannel(Protocol protocol, HttpRequestMessage outboundMessage) {
    probingAction = ProbingAction.builder()
        .setProtocol(protocol)
        .setOutboundMessage(outboundMessage)
        .setDelay(DEFAULT_DURATION)
        .setHost(TARGET_HOST)
        .setChannel(channel)
        .build();
  }

  private void setupProbingActionWithoutChannel(Protocol protocol, HttpRequestMessage outboundMessage, Bootstrap bootstrap, String addressString) {
    probingAction = ProbingAction.builder()
        .setProtocol(protocol)
        .setOutboundMessage(outboundMessage)
        .setDelay(DEFAULT_DURATION)
        .setBootstrap(bootstrap)
        .setHost(addressString)
        .build();
  }

  private void setupLocalServer(String redirectInput, String destinationInput, EventLoopGroup group) {
    WebWhoisServer.strippedServer(group, address, redirectInput, destinationInput);
  }

  @Test
  public void testBasic_responseOk() throws Exception {
    //setup
    Bootstrap bootstrap = null;
    HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest("", ""));
    setupActionHandler(bootstrap, msg);
    Protocol initialProtocol = createProtocol("responseOk", 0, true);

    setupChannel(initialProtocol, msg);
    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);


    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.OK));


    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that we successfully receivved good response and protocol is unchanged
    assertThat(future.isSuccess()).isTrue();
    assertThat(channel.attr(PROTOCOL_KEY).get()).isEqualTo(initialProtocol);
  }

  @Test
  public void testBasic_responseFailure_badRequest() {
    //setup
    Bootstrap bootstrap = null;
    HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest("", ""));
    setupActionHandler(bootstrap, msg);
    Protocol initialProtocol = createProtocol("responseBad", 0, true);
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.BAD_REQUEST));

    //assesses that future listener isn't triggered yet.
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //assesses that listener is triggered, but event is not success
    assertThat(testPromise.isSuccess()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    //ensures Protocol is the same
    assertThat(future.cause() instanceof FailureException).isTrue();
  }
    @Test
    public void testBasic_responseFailure_badURL() {
      //setup
      Bootstrap bootstrap = null;
      HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest("", ""));
      setupActionHandler(bootstrap, msg);
      Protocol initialProtocol = createProtocol("responseError", 0, true);
      setupChannel(initialProtocol, msg);

      //stores future
      ChannelFuture future = actionHandler.getFinishedFuture();
      channel.writeOutbound(msg);

      //setup for checker to ensure future listener isn't triggered to early
      ChannelPromise testPromise = channel.newPromise();
      future.addListener(f -> testPromise.setSuccess());

      FullHttpResponse response = new HttpResponseMessage(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, DUMMY_URL, true, false));

      //assesses that future listener isn't triggered yet.
      assertThat(testPromise.isSuccess()).isFalse();

      channel.writeInbound(response);

      //assesses that listener is triggered, and event is success
      assertThat(testPromise.isSuccess()).isTrue();
      assertThat(future.isSuccess()).isFalse();
      //ensures Protocol is the same
      assertThat(future.cause() instanceof FailureException);
  }

  @Test
  public void testBasic_redirectCloseChannel() {
    //setup
    Bootstrap bootstrap = new Bootstrap()
        .group(new NioEventLoopGroup(1))
        .channel(LocalChannel.class);
    HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest("", ""));
    setupActionHandler(bootstrap, msg);
    Protocol initialProtocol = createProtocol("redirectHttp", 0, true);
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    //setup for checker to ensure future listener isn't triggered to early
    ChannelPromise testPromise = channel.newPromise();
    future.addListener(f -> testPromise.setSuccess());

    FullHttpResponse response = new HttpResponseMessage(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, HTTP_REDIRECT + REDIRECT_HOST, true, false));

    //checks that future has not been set to successful or a failure
    assertThat(testPromise.isSuccess()).isFalse();

    channel.writeInbound(response);

    //makes sure old channel is shut down when attempting redirection
    assertThat(channel.isActive()).isFalse();


  }

  @Test
  public void testAdvanced_responseOk() throws UndeterminedStateException {
    //setup
    EventLoopGroup group = new NioEventLoopGroup(1);
    String host = generateLocalAddress(TARGET_HOST);
    HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest(host, ""));
    setupActionHandler(null, msg);
    Protocol initialProtocol = createProtocol("responseOk", 0, false);
    setupLocalServer("", host, group);
    setupProbingActionWithoutChannel(initialProtocol, msg, makeBootstrap(group), host);

    //stores future
    ChannelFuture future = probingAction.call();

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.syncUninterruptibly().isSuccess()).isTrue();
  }

  @Test
  public void testAdvanced_responseFailure() throws UndeterminedStateException {
    //setup
    EventLoopGroup group = new NioEventLoopGroup(1);
    String host = generateLocalAddress(TARGET_HOST);
    HttpRequestMessage msg = new HttpRequestMessage(makeHttpGetRequest(DUMMY_URL, ""));
    setupActionHandler(null, msg);
    Protocol initialProtocol = createProtocol("responseOk", 0, false);
    setupLocalServer("", host, group);
    setupProbingActionWithoutChannel(initialProtocol, msg, makeBootstrap(group), host);

    //stores future
    ChannelFuture future = probingAction.call();

    //assesses that we successfully received good response and protocol is unchanged
    assertThrows(FailureException.class, future::syncUninterruptibly);
  }

}

