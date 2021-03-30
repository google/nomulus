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

import static com.google.common.base.Preconditions.checkState;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;

import google.registry.proxy.metric.FrontendMetrics;
import google.registry.util.Clock;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import javax.inject.Inject;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Handler that records metrics for a fronend channel.
 *
 * <p>This handler is added before the {@link RelayHandler} in the frontend protocol handler
 * provider method. Outbound messages encounter this first before being handed over to
 * protocol-specific handlers. Inbound messages are first constructed (from plain bytes) by
 * preceding handlers and then related metrics are instrumented in this handler.
 */
public class FrontendMetricsHandler extends ChannelDuplexHandler {

  private final Clock clock;
  private final FrontendMetrics metrics;

  private String protocolName;
  private String clientCertHash;

  /**
   * A queue that saves the time at which a request is received from the client.
   *
   * <p>This queue is used to calculate frontend request-response latency.
   *
   * <p>For the WHOIS protocol, the TCP connection closes after one request-response round trip and
   * the request always comes first. The queue for WHOIS therefore only need to store one value.
   *
   * <p>For the EPP protocol, the specification allows for pipelining, in which a client can sent
   * multiple requests without waiting for each responses. Therefore a queue is needed to record all
   * the requests that are sent but have not yet received a response.
   *
   * <p>A server must send its response in the same order it receives requests. This invariance
   * guarantees that the request time at the head of the queue always corresponds to the response
   * received in {@link #channelRead}.
   *
   * @see <a href="https://tools.ietf.org/html/rfc3912">RFC 3912 WHOIS Protocol Specification</a>
   * @see <a href="https://tools.ietf.org/html/rfc5734#section-3">RFC 5734 Extensible Provisioning
   *     Protocol (EPP) Transport over TCP</a>
   */
  private final Queue<DateTime> requestReceivedTimeQueue = new ArrayDeque<>();

  @Inject
  FrontendMetricsHandler(Clock clock, FrontendMetrics metrics) {
    this.clock = clock;
    this.metrics = metrics;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    protocolName = ctx.channel().attr(PROTOCOL_KEY).get().name();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    requestReceivedTimeQueue.add(clock.nowUtc());
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
    // Only instrument request metrics when the response is actually sent to client.
    // It is OK to check the queue size preemptively here, not when the front element of the queue
    // is acutally removed after the write to the client is successful,  because responses are
    // written to the client in order. Hence there cannot be any response succsssfully sent to the
    // client (which reduces the queue size) before this current request is sent. The queue *can*
    // increase in size if more requests are received from the client, but that does not invalidate
    // this check.
    checkState(!requestReceivedTimeQueue.isEmpty(), "Response sent before request is received.");
    // For WHOIS, client certificate hash is always set to "none".
    // For EPP, the client hash attribute is set upon handshake completion, before the first HELLO
    // is sent to the server. Therefore the first call to write() with HELLO payload has access to
    // the hash in its channel attribute.
    if (clientCertHash == null) {
      clientCertHash =
          Optional.ofNullable(ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).get()).orElse("none");
    }
    ChannelFuture unusedFuture =
        ctx.write(msg, promise)
            .addListener(
                future -> {
                  if (future.isSuccess()) {
                    metrics.responseSent(
                        protocolName,
                        clientCertHash,
                        new Duration(requestReceivedTimeQueue.remove(), clock.nowUtc()));
                  }
                });
  }
}
