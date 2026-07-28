// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver;

import static google.registry.eppserver.Protocol.PROTOCOL_KEY;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.eppserver.EppServerModule.EppServerComponent;
import google.registry.eppserver.Protocol.FrontendProtocol;
import google.registry.eppserver.handler.EppServiceHandler;
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
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.Future;
import jakarta.inject.Provider;
import java.util.HashMap;

/** An integrated EPP server that listens for EPP traffic and processes it in-process. */
public class EppServer {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Maximum length of the queue of incoming connections. */
  private static final int MAX_SOCKET_BACKLOG = 128;

  private final ImmutableSet<FrontendProtocol> protocols;
  private final HashMap<Integer, Channel> portToChannelMap = new HashMap<>();
  private final EventLoopGroup eventGroup = new NioEventLoopGroup();
  private final EventExecutorGroup businessGroup =
      new DefaultEventExecutorGroup(Math.max(4, Runtime.getRuntime().availableProcessors() * 4));

  public EppServer(EppServerComponent eppServerComponent) {
    this.protocols = ImmutableSet.copyOf(eppServerComponent.protocols());
  }

  private class ServerChannelInitializer extends ChannelInitializer<NioSocketChannel> {
    @Override
    protected void initChannel(NioSocketChannel inboundChannel) {
      FrontendProtocol inboundProtocol =
          (FrontendProtocol) inboundChannel.parent().attr(PROTOCOL_KEY).get();
      inboundChannel.attr(PROTOCOL_KEY).set(inboundProtocol);

      addHandlers(inboundChannel.pipeline(), inboundProtocol.handlerProviders());

      // Start reading immediately since we don't have a backend relay.
      inboundChannel.config().setAutoRead(true);

      logger.atInfo().log("Connection established: %s %s", inboundProtocol.name(), inboundChannel);
    }

    @SuppressWarnings("deprecation")
    private void addHandlers(
        ChannelPipeline channelPipeline,
        ImmutableList<Provider<? extends ChannelHandler>> handlerProviders) {
      for (Provider<? extends ChannelHandler> handlerProvider : handlerProviders) {
        ChannelHandler handler = handlerProvider.get();
        String handlerName = handler.getClass().getSimpleName();
        if (handler.getClass() == EppServiceHandler.class) {
          channelPipeline.addLast(businessGroup, handlerName, handler);
        } else {
          channelPipeline.addLast(handlerName, handler);
        }
      }
    }
  }

  public void start() {
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(eventGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ServerChannelInitializer())
            .option(ChannelOption.SO_BACKLOG, MAX_SOCKET_BACKLOG)
            .childOption(ChannelOption.SO_KEEPALIVE, true);

    protocols.forEach(
        protocol -> {
          int port = protocol.port();
          try {
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
  }

  public void stop() {
    logger.atInfo().log("Shutting down EPP server...");
    portToChannelMap
        .values()
        .forEach(
            channel -> {
              Future<?> unusedFuture = channel.close();
            });
    Future<?> unusedFuture = eventGroup.shutdownGracefully();
    Future<?> unusedFutureBusiness = businessGroup.shutdownGracefully();
  }
}
