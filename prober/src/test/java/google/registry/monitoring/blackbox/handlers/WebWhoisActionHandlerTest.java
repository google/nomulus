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

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.ProbingAction;
import google.registry.monitoring.blackbox.Protocol;
import google.registry.monitoring.blackbox.TestServers.WebWhoisServer;
import google.registry.monitoring.blackbox.TestUtils.TestProvider;
import google.registry.monitoring.blackbox.exceptions.FailureException;
import google.registry.monitoring.blackbox.messages.HttpRequestMessage;
import google.registry.monitoring.blackbox.messages.HttpResponseMessage;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
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
  private final Protocol STANDARD_PROTOCOL = Protocol.builder()
      .setHandlerProviders(ImmutableList.of(new TestProvider<>(new WebWhoisActionHandler(
          null, null, null, null))))
      .setName("http")
      .setPersistentConnection(false)
      .setPort(HTTP_PORT)
      .build();


  private LocalAddress address;
  private EmbeddedChannel channel;
  private ActionHandler actionHandler;
  private ProbingAction probingAction;
  private Provider<? extends ChannelHandler> actionHandlerProvider;
  private Protocol initialProtocol;
  private HttpRequestMessage msg;


  /**
   * Creates default protocol with empty list of handlers and specified other inputs
   */
  private Protocol createProtocol(String name, int port, boolean persistentConnection) {
    return Protocol.builder()
        .setName(name)
        .setPort(port)
        .setHandlerProviders(ImmutableList.of(actionHandlerProvider))
        .setPersistentConnection(persistentConnection)
        .build();
  }

  /**
   * Initializes new WebWhoisActionHandler
   */
  private void setupActionHandler(Bootstrap bootstrap, HttpRequestMessage messageTemplate) {
    actionHandler = new WebWhoisActionHandler(
        bootstrap,
        STANDARD_PROTOCOL,
        STANDARD_PROTOCOL,
        messageTemplate
    );
    actionHandlerProvider = new TestProvider<>(actionHandler);
  }

  /**
   * Sets up testing channel with requisite attributes
   */
  private void setupChannel(Protocol protocol, HttpRequestMessage outboundMessage) {
    channel = new EmbeddedChannel(actionHandler);
    channel.attr(PROTOCOL_KEY).set(protocol);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());
  }

  private Bootstrap makeBootstrap(EventLoopGroup group) {
    return new Bootstrap()
        .group(group)
        .channel(LocalChannel.class);
  }

  private void setupProbingActionWithoutChannel(Protocol protocol,
      HttpRequestMessage outboundMessage, Bootstrap bootstrap, String addressString) {
    probingAction = ProbingAction.builder()
        .setProtocol(protocol)
        .setOutboundMessage(outboundMessage)
        .setDelay(DEFAULT_DURATION)
        .setBootstrap(bootstrap)
        .setHost(addressString)
        .build();
  }

  private void setupLocalServer(String redirectInput, String destinationInput,
      EventLoopGroup group) {
    WebWhoisServer.strippedServer(group, address, redirectInput, destinationInput);
  }

  private void setup(String hostName, Bootstrap bootstrap, boolean persistentConnection) {
    msg = new HttpRequestMessage(makeHttpGetRequest(hostName, ""));
    setupActionHandler(bootstrap, msg);
    initialProtocol = createProtocol("testProtocol", 0, persistentConnection);

  }

  @Test
  public void testBasic_responseOk() {
    //setup
    setup("", null, true);
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.OK));

    //assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  public void testBasic_responseFailure_badRequest() {
    //setup
    setup("", null, false);
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response = new HttpResponseMessage(
        makeHttpResponse(HttpResponseStatus.BAD_REQUEST));

    //assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    //assesses that listener is triggered, but event is not success
    assertThat(future.isDone()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    //ensures Protocol is the same
    assertThat(future.cause() instanceof FailureException).isTrue();
  }

  @Test
  public void testBasic_responseFailure_badURL() {
    //setup
    setup("", null, false);
    setupChannel(initialProtocol, msg);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response = new HttpResponseMessage(
        makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, DUMMY_URL, true, false));

    //assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    //assesses that listener is triggered, and event is success
    assertThat(future.isDone()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    //ensures Protocol is the same
    assertThat(future.cause() instanceof FailureException);
  }

  @Test
  public void testAdvanced_redirect() {
    // Sets up EventLoopGroup with 1 thread to be blocking.
    EventLoopGroup group = new NioEventLoopGroup(1);

    // Sets up embedded channel.
    setup("", makeBootstrap(group), false);
    setupChannel(initialProtocol, msg);

    // Initializes LocalAddress with unique String.
    String host = TARGET_HOST + System.currentTimeMillis();
    address = new LocalAddress(host);

    //stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    // Sets up the local server that the handler will be redirected to.
    setupLocalServer("", host, group);

    FullHttpResponse response =
        new HttpResponseMessage(makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY,
            HTTP_REDIRECT + host, true, false));

    //checks that future has not been set to successful or a failure
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    //makes sure old channel is shut down when attempting redirection
    assertThat(channel.isActive()).isFalse();

    //assesses that we successfully received good response and protocol is unchanged
    assertThat(future.syncUninterruptibly().isSuccess()).isTrue();
  }
}
