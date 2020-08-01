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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.monitoring.blackbox.connection.ProbingAction.CONNECTION_FUTURE_KEY;
import static google.registry.monitoring.blackbox.connection.Protocol.PROTOCOL_KEY;
import static google.registry.monitoring.blackbox.util.WebWhoisUtils.makeHttpGetRequest;
import static google.registry.monitoring.blackbox.util.WebWhoisUtils.makeHttpResponse;
import static google.registry.monitoring.blackbox.util.WebWhoisUtils.makeRedirectResponse;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.connection.Protocol;
import google.registry.monitoring.blackbox.exception.FailureException;
import google.registry.monitoring.blackbox.message.HttpRequestMessage;
import google.registry.monitoring.blackbox.message.HttpResponseMessage;
import google.registry.monitoring.blackbox.testserver.TestServer;
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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebWhoisActionHandler}.
 *
 * <p>Attempts to test how well {@link WebWhoisActionHandler} works when responding to all possible
 * types of responses
 */
class WebWhoisActionHandlerTest {

  private static final int HTTP_PORT = 80;
  private static final String HTTP_REDIRECT = "http://";
  private static final String TARGET_HOST = "whois.nic.tld";
  private static final String DUMMY_URL = "__WILL_NOT_WORK__";
  private final Protocol standardProtocol =
      Protocol.builder()
          .setHandlerProviders(
              ImmutableList.of(() -> new WebWhoisActionHandler(null, null, null, null)))
          .setName("http")
          .setPersistentConnection(false)
          .setPort(HTTP_PORT)
          .build();

  private EmbeddedChannel channel;
  private ActionHandler actionHandler;
  private Provider<? extends ChannelHandler> actionHandlerProvider;
  private Protocol initialProtocol;
  private HttpRequestMessage msg;

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
    actionHandler =
        new WebWhoisActionHandler(bootstrap, standardProtocol, standardProtocol, messageTemplate);
    actionHandlerProvider = () -> actionHandler;
  }

  /** Sets up testing channel with requisite attributes */
  private void setupChannel(Protocol protocol) {
    channel = new EmbeddedChannel(actionHandler);
    channel.attr(PROTOCOL_KEY).set(protocol);
    channel.attr(CONNECTION_FUTURE_KEY).set(channel.newSucceededFuture());
  }

  private Bootstrap makeBootstrap(EventLoopGroup group) {
    return new Bootstrap().group(group).channel(LocalChannel.class);
  }

  private void setup(String hostName, Bootstrap bootstrap, boolean persistentConnection) {
    msg = new HttpRequestMessage(makeHttpGetRequest(hostName, ""));
    setupActionHandler(bootstrap, msg);
    initialProtocol = createProtocol("testProtocol", 0, persistentConnection);
  }

  @Test
  void testBasic_responseOk() {
    // setup
    setup("", null, true);
    setupChannel(initialProtocol);

    // stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response = new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.OK));

    // assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    // assesses that we successfully received good response and protocol is unchanged
    assertThat(future.isSuccess()).isTrue();
  }

  @Test
  void testBasic_responseFailure_badRequest() {
    // setup
    setup("", null, false);
    setupChannel(initialProtocol);

    // Stores future that informs when action is completed.
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response =
        new HttpResponseMessage(makeHttpResponse(HttpResponseStatus.BAD_REQUEST));

    // Assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    // Assesses that listener is triggered, but event is not success
    assertThat(future.isDone()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    // Ensures that we fail as a result of a FailureException.
    assertThat(future.cause() instanceof FailureException).isTrue();
  }

  @Test
  void testBasic_responseFailure_badURL() {
    // setup
    setup("", null, false);
    setupChannel(initialProtocol);

    // stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    FullHttpResponse response =
        new HttpResponseMessage(
            makeRedirectResponse(HttpResponseStatus.MOVED_PERMANENTLY, DUMMY_URL, true));

    // assesses that future listener isn't triggered yet.
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    // assesses that listener is triggered, and event is success
    assertThat(future.isDone()).isTrue();
    assertThat(future.isSuccess()).isFalse();

    // Ensures that we fail as a result of a FailureException.
    assertThat(future.cause() instanceof FailureException).isTrue();
  }

  @Test
  void testAdvanced_redirect() {
    // Sets up EventLoopGroup with 1 thread to be blocking.
    EventLoopGroup group = new NioEventLoopGroup(1);

    // Sets up embedded channel.
    setup("", makeBootstrap(group), false);
    setupChannel(initialProtocol);

    // Initializes LocalAddress with unique String.
    LocalAddress address = new LocalAddress(TARGET_HOST);

    // stores future
    ChannelFuture future = actionHandler.getFinishedFuture();
    channel.writeOutbound(msg);

    // Path that we test WebWhoisActionHandler uses.
    String path = "/test";

    // Sets up the local server that the handler will be redirected to.
    TestServer.webWhoisServer(group, address, "", TARGET_HOST, path);

    FullHttpResponse response =
        new HttpResponseMessage(
            makeRedirectResponse(
                HttpResponseStatus.MOVED_PERMANENTLY, HTTP_REDIRECT + TARGET_HOST + path, true));

    // checks that future has not been set to successful or a failure
    assertThat(future.isDone()).isFalse();

    channel.writeInbound(response);

    // makes sure old channel is shut down when attempting redirection
    assertThat(channel.isActive()).isFalse();

    // assesses that we successfully received good response and protocol is unchanged
    assertThat(future.syncUninterruptibly().isSuccess()).isTrue();
  }
}
