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

package google.registry.proxy;

import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.RelayHandler.RELAY_BUFFER_KEY;
import static google.registry.proxy.handler.RelayHandler.RELAY_CHANNEL_KEY;
import static google.registry.proxy.handler.RelayHandler.writeToRelayChannel;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.google.monitoring.metrics.MetricReporter;
import google.registry.proxy.Protocol.BackendProtocol;
import google.registry.proxy.Protocol.FrontendProtocol;
import google.registry.proxy.ProxyConfig.Environment;
import google.registry.proxy.ProxyModule.ProxyComponent;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.JdkLoggerFactory;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import javax.inject.Provider;

/**
 * A multi-protocol proxy server that listens for protocols in {@link
 * ProxyModule.ProxyComponent#protocols()} }.
 */
public class ProxyServer implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Maximum length of the queue of incoming connections. */
  private static final int MAX_SOCKET_BACKLOG = 128;

  private final ImmutableSet<FrontendProtocol> protocols;
  private final HashMap<Integer, Channel> portToChannelMap = new HashMap<>();
  private final EventLoopGroup eventGroup = new NioEventLoopGroup();

  ProxyServer(ProxyComponent proxyComponent) {
    this.protocols = ImmutableSet.copyOf(proxyComponent.protocols());
  }

  /**
   * A {@link ChannelInitializer} for connections from a client of a certain protocol.
   *
   * <p>The {@link #initChannel(NioSocketChannel)} method does the following:
   *
   * <ol>
   *   <li>Determine the {@link FrontendProtocol} of the inbound {@link Channel} from its parent
   *       {@link Channel}, i. e. the {@link Channel} that binds to local port and listens.
   *   <li>Add handlers for the {@link FrontendProtocol} to the inbound {@link Channel}.
   *   <li>Establish an outbound {@link Channel} that serves as the relay channel of the inbound
   *       {@link Channel}, as specified by {@link FrontendProtocol#relayProtocol}.
   *   <li>After the outbound {@link Channel} connects successfully, enable {@link
   *       ChannelOption#AUTO_READ} on the inbound {@link Channel} to start reading.
   * </ol>
   */
  private static class ServerChannelInitializer extends ChannelInitializer<NioSocketChannel> {
    @Override
    protected void initChannel(NioSocketChannel inboundChannel) throws Exception {
      // Add inbound channel handlers.
      FrontendProtocol inboundProtocol =
          (FrontendProtocol) inboundChannel.parent().attr(PROTOCOL_KEY).get();
      inboundChannel.attr(PROTOCOL_KEY).set(inboundProtocol);
      inboundChannel.attr(RELAY_BUFFER_KEY).set(new ArrayDeque<>());
      addHandlers(inboundChannel.pipeline(), inboundProtocol.handlerProviders());

      if (!inboundProtocol.hasBackend()) {
        // If the frontend has no backend to relay to (health check, web WHOIS redirect, etc), start
        // reading immediately.
        inboundChannel.config().setAutoRead(true);
      } else {
        logger.atInfo().log(
            "Connection established: %s %s", inboundProtocol.name(), inboundChannel);
        // Connect to the relay (outbound) channel specified by the BackendProtocol.
        BackendProtocol outboundProtocol = inboundProtocol.relayProtocol();
        Bootstrap bootstrap =
            new Bootstrap()
                // Use the same thread to connect to the relay channel, therefore avoiding
                // synchronization handling due to interactions between the two channels
                .group(inboundChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .handler(
                    new ChannelInitializer<NioSocketChannel>() {
                      @Override
                      protected void initChannel(NioSocketChannel outboundChannel)
                          throws Exception {
                        addHandlers(
                            outboundChannel.pipeline(), outboundProtocol.handlerProviders());
                      }
                    })
                .option(ChannelOption.SO_KEEPALIVE, true)
                // Outbound channel relays to inbound channel.
                .attr(RELAY_CHANNEL_KEY, inboundChannel)
                .attr(PROTOCOL_KEY, outboundProtocol);

        connectOutboundChannel(bootstrap, inboundProtocol, outboundProtocol, inboundChannel);
        // If the inbound connection is closed, close its outbound relay connection as well. There
        // is no way to recover from an inbound connection termination, as the connection can only
        // be initiated by the client.
        ChannelFuture unusedChannelFuture =
            inboundChannel
                .closeFuture()
                .addListener(
                    (future) -> {
                      logger.atInfo().log(
                          "Connection terminated: %s %s", inboundProtocol.name(), inboundChannel);
                      // Check if there's a relay connection. In case that the outbound connection
                      // is not successful, this attribute is not set.
                      Channel outboundChannel = inboundChannel.attr(RELAY_CHANNEL_KEY).get();
                      if (outboundChannel != null) {
                        ChannelFuture unusedChannelFuture2 = outboundChannel.close();
                      }
                      // If the frontend channel is closed and there are messages remaining in the
                      // buffer, we should make sure that they are released (if the messages are
                      // reference counted).
                      inboundChannel
                          .attr(RELAY_BUFFER_KEY)
                          .get()
                          .forEach(
                              msg -> {
                                logger.atWarning().log(
                                    "Unfinished relay for connection %s\nHASH: %s",
                                    inboundChannel, msg.hashCode());
                                ReferenceCountUtil.release(msg);
                              });
                    });
      }
    }

    /**
     * Establishes an outbound relay channel and sets the relevant metadata on both channels.
     *
     * <p>This method also adds a listener that is called when the established outbound connection
     * is closed. The outbound connection to GAE is *not* guaranteed to persist. In case that the
     * outbound connection closes but the inbound connection is still active, the listener calls
     * this function again to re-establish another outbound connection. The metadata is also reset
     * so that the inbound channel knows to relay to the new outbound channel.
     */
    private static void connectOutboundChannel(
        Bootstrap bootstrap,
        FrontendProtocol inboundProtocol,
        BackendProtocol outboundProtocol,
        NioSocketChannel inboundChannel) {
      ChannelFuture outboundChannelFuture =
          bootstrap.connect(outboundProtocol.host(), outboundProtocol.port());
      outboundChannelFuture.addListener(
          (ChannelFuture future) -> {
            if (future.isSuccess()) {
              // Outbound connection is successful, now we can set the metadata to couple these two
              // connections together.
              Channel outboundChannel = future.channel();
              // Inbound channel relays to outbound channel.
              inboundChannel.attr(RELAY_CHANNEL_KEY).set(outboundChannel);
              // Outbound channel established successfully, inbound channel can start reading.
              // This setter also calls channel.read() to request read operation.
              inboundChannel.config().setAutoRead(true);
              logger.atInfo().log(
                  "Relay established: %s <-> %s\nFRONTEND: %s\nBACKEND: %s",
                  inboundProtocol.name(), outboundProtocol.name(), inboundChannel, outboundChannel);
              // Now that we have a functional relay channel to the backend, if there's any
              // buffered requests, send them off to the relay channel. We need to obtain a copy
              // of the messages and clear the queue first, because if the relay is not successful,
              // the message will be written back to the queue, causing an infinite loop.
              Queue<Object> relayBuffer = inboundChannel.attr(RELAY_BUFFER_KEY).get();
              Object[] messages = relayBuffer.toArray();
              relayBuffer.clear();
              for (Object msg : messages) {
                logger.atInfo().log(
                    "Relay retried: %s <-> %s\nFRONTEND: %s\nBACKEND: %s\nHASH: %s",
                    inboundProtocol.name(),
                    outboundProtocol.name(),
                    inboundChannel,
                    outboundChannel,
                    msg.hashCode());
                writeToRelayChannel(inboundChannel, outboundChannel, msg, true);
              }
              // When this outbound connection is closed, try reconnecting if the inbound connection
              // is still active.
              ChannelFuture unusedChannelFuture =
                  outboundChannel
                      .closeFuture()
                      .addListener(
                          (ChannelFuture future2) -> {
                            if (inboundChannel.isActive()) {
                              logger.atInfo().log(
                                  "Relay interrupted: %s <-> %s\nFRONTEND: %s\nBACKEND: %s",
                                  inboundProtocol.name(),
                                  outboundProtocol.name(),
                                  inboundChannel,
                                  outboundChannel);
                              connectOutboundChannel(
                                  bootstrap, inboundProtocol, outboundProtocol, inboundChannel);
                            } else {
                              logger.atInfo().log(
                                  "Relay terminated: %s <-> %s\nFRONTEND: %s\nBACKEND: %s",
                                  inboundProtocol.name(),
                                  outboundProtocol.name(),
                                  inboundChannel,
                                  outboundChannel);
                            }
                          });
            } else {
              // We cannot connect to GAE for unknown reasons, no relay can be done so drop the
              // inbound connection as well.
              logger.atSevere().withCause(future.cause()).log(
                  "Cannot connect to relay channel for %s channel: %s.",
                  inboundProtocol.name(), inboundChannel);
              ChannelFuture unusedFuture = inboundChannel.close();
            }
          });
    }

    private static void addHandlers(
        ChannelPipeline channelPipeline,
        ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
      for (Provider<? extends ChannelHandler> handlerProvider : handlerProviders) {
        channelPipeline.addLast(handlerProvider.get());
      }
    }
  }

  @Override
  public void run() {
    try {
      ServerBootstrap serverBootstrap =
          new ServerBootstrap()
              .group(eventGroup)
              .channel(NioServerSocketChannel.class)
              .childHandler(new ServerChannelInitializer())
              .option(ChannelOption.SO_BACKLOG, MAX_SOCKET_BACKLOG)
              .childOption(ChannelOption.SO_KEEPALIVE, true)
              // Do not read before relay channel is established.
              .childOption(ChannelOption.AUTO_READ, false);

      // Bind to each port specified in portToHandlersMap.
      protocols.forEach(
          protocol -> {
            int port = protocol.port();
            try {
              // Wait for binding to be established for each listening port.
              ChannelFuture serverChannelFuture = serverBootstrap.bind(port).sync();
              if (serverChannelFuture.isSuccess()) {
                logger.atInfo().log(
                    "Start listening on port %s for %s protocol.", port, protocol.name());
                Channel serverChannel = serverChannelFuture.channel();
                serverChannel.attr(PROTOCOL_KEY).set(protocol);
                portToChannelMap.put(port, serverChannel);
              }
            } catch (InterruptedException e) {
              logger.atSevere().withCause(e).log(
                  "Cannot listen on port %d for %s protocol.", port, protocol.name());
            }
          });

      // Wait for all listening ports to close.
      portToChannelMap.forEach(
          (port, channel) -> {
            try {
              // Block until all server channels are closed.
              ChannelFuture unusedFuture = channel.closeFuture().sync();
              logger.atInfo().log(
                  "Stop listening on port %d for %s protocol.",
                  port, channel.attr(PROTOCOL_KEY).get().name());
            } catch (InterruptedException e) {
              logger.atSevere().withCause(e).log(
                  "Listening on port %d for %s protocol interrupted.",
                  port, channel.attr(PROTOCOL_KEY).get().name());
            }
          });
    } finally {
      logger.atInfo().log("Shutting down server...");
      Future<?> unusedFuture = eventGroup.shutdownGracefully();
    }
  }

  public static void main(String[] args) throws Exception {
    // Use JDK logger for Netty's LoggingHandler,
    // which is what Flogger uses under the hood.
    InternalLoggerFactory.setDefaultFactory(JdkLoggerFactory.INSTANCE);

    // Configure the components, this needs to run first so that the logging format is properly
    // configured for each environment.
    ProxyModule proxyModule = new ProxyModule().parse(args);
    ProxyComponent proxyComponent =
        DaggerProxyModule_ProxyComponent.builder().proxyModule(proxyModule).build();

    // Do not write metrics when running locally.
    if (proxyModule.provideEnvironment() != Environment.LOCAL) {
      MetricReporter metricReporter = proxyComponent.metricReporter();
      try {
        metricReporter.startAsync().awaitRunning(java.time.Duration.ofSeconds(10));
        logger.atInfo().log("Started up MetricReporter");
      } catch (TimeoutException timeoutException) {
        logger.atSevere().withCause(timeoutException).log(
            "Failed to initialize MetricReporter: %s", timeoutException);
      }
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      metricReporter.stopAsync().awaitTerminated(java.time.Duration.ofSeconds(10));
                      logger.atInfo().log("Shut down MetricReporter");
                    } catch (TimeoutException timeoutException) {
                      logger.atWarning().withCause(timeoutException).log(
                          "Failed to stop MetricReporter: %s", timeoutException);
                    }
                  }));
    }

    // Start the proxy.
    new ProxyServer(proxyComponent).run();
  }
}
