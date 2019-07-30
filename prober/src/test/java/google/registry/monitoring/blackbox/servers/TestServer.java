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

package google.registry.monitoring.blackbox.servers;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.monitoring.blackbox.connection.ProbingAction.REMOTE_ADDRESS_KEY;
import static google.registry.monitoring.blackbox.connection.Protocol.PROTOCOL_KEY;

import com.google.common.collect.ImmutableList;
import google.registry.monitoring.blackbox.connection.Protocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.rules.ExternalResource;

/**
 * Mock Server Superclass whose subclasses implement specific behaviors we expect blackbox server to perform.
 */
public abstract class TestServer extends ExternalResource {

  protected EventLoopGroup eventLoopGroup;
  protected Channel channel;

  protected TestServer() {
    eventLoopGroup = new NioEventLoopGroup(1);
  }

  protected TestServer(EventLoopGroup eventLoopGroup) {
    this.eventLoopGroup = eventLoopGroup;
  }

  protected void setupServer(LocalAddress address, ImmutableList<? extends ChannelHandler> handlers) {

    //Creates ChannelInitializer with handlers specified
    ChannelInitializer<LocalChannel> serverInitializer = new ChannelInitializer<LocalChannel>() {
      @Override
      protected void initChannel(LocalChannel ch) {
        for (ChannelHandler handler : handlers) {
          ch.pipeline().addLast(handler);
        }
      }
    };
    //Sets up serverBootstrap with specified initializer, eventLoopGroup, and using LocalServerChannel class
    ServerBootstrap serverBootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(LocalServerChannel.class)
            .childHandler(serverInitializer);

    try {

      ChannelFuture future = serverBootstrap.bind(address).sync();

    } catch (InterruptedException e) {
      throw new ExceptionInInitializerError(e);

    }
  }

  /** Sets up a client channel connecting to the give local address. */
  void setUpClient(
      LocalAddress localAddress,
      Protocol protocol,
      String host,
      ImmutableList<ChannelHandler> handlers) {
    ChannelInitializer<LocalChannel> clientInitializer =
        new ChannelInitializer<LocalChannel>() {
          @Override
          protected void initChannel(LocalChannel ch) throws Exception {
            // Add the given handler
            for (ChannelHandler handler: handlers)
              ch.pipeline().addLast(handler);
          }
        };
    Bootstrap b =
        new Bootstrap()
            .group(eventLoopGroup)
            .channel(LocalChannel.class)
            .handler(clientInitializer)
            .attr(PROTOCOL_KEY, protocol)
            .attr(REMOTE_ADDRESS_KEY, host);

    channel = b.connect(localAddress).syncUninterruptibly().channel();
  }

  public Channel getChannel() {
    checkReady();
    return channel;
  }

  protected void checkReady() {
    checkState(channel != null, "Must call setUpClient to finish TestServer setup");
  }








}
