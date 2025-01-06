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

package google.registry.proxy.handler;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;
import static google.registry.proxy.handler.ProxyProtocolHandler.REMOTE_ADDRESS_KEY;

import google.registry.proxy.EppProtocolModule.EppProtocol;
import google.registry.proxy.WhoisProtocolModule.WhoisProtocol;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.proxy.quota.QuotaManager;
import google.registry.proxy.quota.QuotaManager.QuotaRebate;
import google.registry.proxy.quota.QuotaManager.QuotaRequest;
import google.registry.proxy.quota.QuotaManager.QuotaResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.util.concurrent.Future;
import javax.inject.Inject;

/**
 * Handler that checks quota fulfillment and terminates connection if necessary.
 *
 * <p>This handler attempts to acquire quota during the first {@link #channelRead} operation, not
 * when connection is established. The reason is that the {@code userId} used for acquiring quota is
 * not always available when the connection is just open.
 */
public abstract class QuotaHandler extends ChannelInboundHandlerAdapter {

  protected final QuotaManager quotaManager;
  protected QuotaResponse quotaResponse;
  protected final FrontendMetrics metrics;

  protected QuotaHandler(QuotaManager quotaManager, FrontendMetrics metrics) {
    this.quotaManager = quotaManager;
    this.metrics = metrics;
  }

  abstract String getUserId(ChannelHandlerContext ctx);

  /** Whether the user id is PII ans should not be logged. IP addresses are considered PII. */
  abstract boolean isUserIdPii();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (quotaResponse == null) {
      String userId = getUserId(ctx);
      checkNotNull(userId, "Cannot obtain User ID");
      quotaResponse = quotaManager.acquireQuota(new QuotaRequest(userId));
      if (!quotaResponse.success()) {
        String protocolName = ctx.channel().attr(PROTOCOL_KEY).get().name();
        metrics.registerQuotaRejection(protocolName, isUserIdPii() ? "none" : userId);
        throw new OverQuotaException(protocolName, isUserIdPii() ? "none" : userId);
      }
    }
    ctx.fireChannelRead(msg);
  }

  /**
   * Actions to take when the connection terminates.
   *
   * <p>Depending on the quota type, the handler either returns the tokens, or does nothing.
   */
  @Override
  public abstract void channelInactive(ChannelHandlerContext ctx);

  static class OverQuotaException extends Exception {
    OverQuotaException(String protocol, String userId) {
      super(String.format("Quota exceeded for: PROTOCOL: %s, USER ID: %s", protocol, userId));
    }
  }

  /** Quota Handler for WHOIS protocol. */
  public static class WhoisQuotaHandler extends QuotaHandler {

    @Inject
    WhoisQuotaHandler(@WhoisProtocol QuotaManager quotaManager, FrontendMetrics metrics) {
      super(quotaManager, metrics);
    }

    /**
     * Reads user ID from channel attribute {@code REMOTE_ADDRESS_KEY}.
     *
     * <p>This attribute is set by {@link ProxyProtocolHandler} when the first frame of message is
     * read.
     */
    @Override
    String getUserId(ChannelHandlerContext ctx) {
      return ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
    }

    @Override
    boolean isUserIdPii() {
      return true;
    }

    /**
     * Do nothing when connection terminates.
     *
     * <p>WHOIS protocol is configured with a QPS type quota, there is no need to return the tokens
     * back to the quota store because the quota store will auto-refill tokens based on the QPS.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      ctx.fireChannelInactive();
    }
  }

  /** Quota Handler for EPP protocol. */
  public static class EppQuotaHandler extends QuotaHandler {

    @Inject
    EppQuotaHandler(@EppProtocol QuotaManager quotaManager, FrontendMetrics metrics) {
      super(quotaManager, metrics);
    }

    /**
     * Reads user ID from channel attribute {@code CLIENT_CERTIFICATE_HASH_KEY}.
     *
     * <p>This attribute is set by {@link EppServiceHandler} when SSH handshake completes
     * successfully. That handler subsequently simulates reading of an EPP HELLO request, in order
     * to solicit an EPP GREETING response from the server. The {@link #channelRead} method of this
     * handler is called afterward because it is the next handler in the channel pipeline,
     * guaranteeing that the {@code CLIENT_CERTIFICATE_HASH_KEY} is always non-null.
     */
    @Override
    String getUserId(ChannelHandlerContext ctx) {
      return ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).get();
    }

    @Override
    boolean isUserIdPii() {
      return false;
    }

    /**
     * Returns the leased token (if available) back to the token store upon connection termination.
     *
     * <p>A connection with concurrent quota needs to do this in order to maintain its quota number
     * invariance.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      // If no reads occurred before the connection is inactive (for example when the handshake
      // is not successful), no quota is leased and therefore no return is needed.
      // Note that the quota response can be a failure, in which case no token was leased to us from
      // the token store. Consequently no return is necessary.
      if (quotaResponse != null && quotaResponse.success()) {
        Future<?> unusedFuture = quotaManager.releaseQuota(QuotaRebate.create(quotaResponse));
      }
      ctx.fireChannelInactive();
    }
  }
}
