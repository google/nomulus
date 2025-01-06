// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import javax.annotation.Nullable;
import javax.inject.Provider;

/** Value class that encapsulates parameters of a specific connection. */
public interface Protocol {

  /** Key used to retrieve the {@link Protocol} from a {@link Channel}'s {@link Attribute}. */
  AttributeKey<Protocol> PROTOCOL_KEY = AttributeKey.valueOf("PROTOCOL_KEY");

  /** Protocol name. */
  String name();

  /**
   * Port to bind to (for {@link FrontendProtocol}) or to connect to (for {@link BackendProtocol}).
   */
  int port();

  /** The {@link ChannelHandler} providers to use for the protocol, in order. */
  ImmutableList<Provider<? extends ChannelHandler>> handlerProviders();

  /** A builder for {@link FrontendProtocol}, by default there is a backend associated with it. */
  static FrontendProtocol.Builder frontendBuilder() {
    return new AutoValue_Protocol_FrontendProtocol.Builder().hasBackend(true);
  }

  /** A builder for {@link FrontendProtocol}, by default it connects to a remote host. */
  static BackendProtocol.Builder backendBuilder() {
    return new AutoValue_Protocol_BackendProtocol.Builder().isLocal(false);
  }

  /**
   * Generic builder enabling chaining for concrete implementations.
   *
   * @param <B> builder of the concrete subtype of {@link Protocol}.
   * @param <P> type of the concrete subtype of {@link Protocol}.
   */
  abstract class Builder<B extends Builder<B, P>, P extends Protocol> {

    public abstract B name(String value);

    public abstract B port(int port);

    public abstract B handlerProviders(ImmutableList<Provider<? extends ChannelHandler>> value);

    public abstract P build();
  }

  /**
   * Connection parameters for a connection from the client to the proxy.
   *
   * <p>This protocol is associated to a {@link NioSocketChannel} established by remote peer
   * connecting to the given {@code port} that the proxy is listening on.
   */
  @AutoValue
  abstract class FrontendProtocol implements Protocol {

    /**
     * The {@link BackendProtocol} used to establish a relay channel and relay the traffic to. Not
     * required for health check protocol or HTTP(S) redirect.
     */
    @Nullable
    public abstract BackendProtocol relayProtocol();

    /**
     * Whether this {@code FrontendProtocol} relays to a {@code BackendProtocol}. All proxied
     * traffic must be represented by a protocol that has a backend.
     */
    public abstract boolean hasBackend();

    @AutoValue.Builder
    public abstract static class Builder extends Protocol.Builder<Builder, FrontendProtocol> {
      public abstract Builder relayProtocol(BackendProtocol value);

      public abstract Builder hasBackend(boolean value);

      abstract FrontendProtocol autoBuild();

      @Override
      public FrontendProtocol build() {
        FrontendProtocol frontendProtocol = autoBuild();
        Preconditions.checkState(
            !frontendProtocol.hasBackend() || frontendProtocol.relayProtocol() != null,
            "Frontend protocol %s must define a relay protocol.",
            frontendProtocol.name());
        return frontendProtocol;
      }
    }
  }

  /**
   * Connection parameters for a connection from the proxy to the GAE app.
   *
   * <p>This protocol is associated to a {@link NioSocketChannel} established by the proxy
   * connecting to a remote peer.
   */
  @AutoValue
  abstract class BackendProtocol implements Protocol {
    /** The hostname that the proxy connects to. */
    public abstract String host();

    /** Whether the protocol is expected to connect to localhost. */
    public abstract boolean isLocal();

    /** Builder of {@link BackendProtocol}. */
    @AutoValue.Builder
    public abstract static class Builder extends Protocol.Builder<Builder, BackendProtocol> {
      public abstract Builder host(String value);

      public abstract Builder isLocal(boolean value);

      abstract BackendProtocol autoBuild();

      @Override
      public BackendProtocol build() {
        BackendProtocol protocol = autoBuild();
        Preconditions.checkState(
            !protocol.isLocal() || protocol.host().equals("localhost"),
            "Local backend protocol must connect to localhost");
        return autoBuild();
      }
    }
  }
}
