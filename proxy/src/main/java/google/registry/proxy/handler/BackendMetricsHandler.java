// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.proxy.handler;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;
import static google.registry.proxy.handler.RelayHandler.RELAY_CHANNEL_KEY;

import google.registry.proxy.handler.RelayHandler.FullHttpResponseRelayHandler;
import google.registry.proxy.metric.BackendMetrics;
import google.registry.util.Clock;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Handler that records metrics for a backend channel.
 *
 * <p>This handler is added before the {@link FullHttpResponseRelayHandler} in the backend protocol
 * handler provider method. {@link FullHttpRequest} outbound messages encounter this first before
 * being handed over to HTTP related handler. {@link FullHttpResponse} inbound messages are first
 * constructed (from plain bytes) by preceding handlers and then related metrics are instrumented in
 * this handler.
 */
public class BackendMetricsHandler extends ChannelDuplexHandler {

  private final Clock clock;
  private final BackendMetrics metrics;

  private String relayedProtocolName;
  private String clientCertHash;
  private Channel relayedChannel;

  /**
   * A queue that saves the time at which a request is sent to the GAE app.
   *
   * <p>This queue is used to calculate HTTP request-response latency. HTTP 1.1 specification allows
   * for pipelining, in which a client can sent multiple requests without waiting for each
   * responses. Therefore a queue is needed to record all the requests that are sent but have not
   * yet received a response.
   *
   * <p>A server must send its response in the same order it receives requests. This invariance
   * guarantees that the request time at the head of the queue always corresponds to the response
   * received in {@link #channelRead}.
   *
   * @see <a href="https://www.w3.org/Protocols/rfc2616/rfc2616-sec8.html">RFC 2616 8.1.2.2
   *     Pipelining</a>
   */
  private final Queue<DateTime> requestSentTimeQueue = new ArrayDeque<>();

  @Inject
  BackendMetricsHandler(Clock clock, BackendMetrics metrics) {
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    // Backend channel is always established after a frontend channel is connected, so this call
    // should always return a non-null relay channel.
    relayedChannel = ctx.channel().attr(RELAY_CHANNEL_KEY).get();
    checkNotNull(relayedChannel, "No frontend channel found.");
    relayedProtocolName = relayedChannel.attr(PROTOCOL_KEY).get().name();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    checkArgument(msg instanceof FullHttpResponse, "Incoming response must be FullHttpResponse.");
    checkState(!requestSentTimeQueue.isEmpty(), "Response received before request is sent.");
    metrics.responseReceived(
        relayedProtocolName,
        clientCertHash,
        (FullHttpResponse) msg,
        new Duration(requestSentTimeQueue.remove().getMillis(), clock.nowUtc().getMillis()));
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    checkArgument(msg instanceof FullHttpRequest, "Outgoing request must be FullHttpRequest.");
    // For WHOIS, client certificate hash is always set to "none".
    // For EPP, the client hash attribute is set upon handshake completion, before the first HELLO
    // is sent to the server. Therefore the first call to write() with HELLO payload has access to
    // the hash in its channel attribute.
    if (clientCertHash == null) {
      clientCertHash =
          Optional.ofNullable(relayedChannel.attr(CLIENT_CERTIFICATE_HASH_KEY).get())
              .orElse("none");
    }
    FullHttpRequest request = (FullHttpRequest) msg;

    // Record request size now because the content would have read by the time the listener is
    // called and the readable bytes would be zero by then.
    int bytes = request.content().readableBytes();

    ChannelFuture unusedFuture =
        ctx.write(msg, promise)
            .addListener(
                future -> {
                  if (future.isSuccess()) {
                    // Only instrument request metrics when the request is actually sent to GAE.
                    metrics.requestSent(relayedProtocolName, clientCertHash, bytes);
                    requestSentTimeQueue.add(clock.nowUtc());
                  }
                });
  }
}
