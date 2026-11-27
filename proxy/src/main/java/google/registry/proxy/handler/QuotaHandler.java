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

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.proxy.Protocol.PROTOCOL_KEY;
import static google.registry.proxy.handler.EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY;

import com.google.common.annotations.VisibleForTesting;
import google.registry.proxy.EppProtocolModule.EppProtocol;
import google.registry.proxy.metric.FrontendMetrics;
import google.registry.proxy.quota.QuotaManager;
import google.registry.proxy.quota.QuotaManager.QuotaRebate;
import google.registry.proxy.quota.QuotaManager.QuotaRequest;
import google.registry.proxy.quota.QuotaManager.QuotaResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import jakarta.inject.Inject;
import javax.annotation.Nullable;

/**
 * Handler that checks quota fulfillment and terminates connection if necessary.
 *
 * <p>This handler attempts to acquire quota during the first {@link #channelRead} operation, not
 * when connection is established. The reason is that the {@code userId} used for acquiring quota is
 * not always available when the connection is just open.
 */
public abstract class QuotaHandler extends ChannelInboundHandlerAdapter {

  /** Explicit quota lease lifecycle state for a connection. */
  @VisibleForTesting
  enum LeaseState {
    /** Quota has not yet been requested on this connection. */
    UNCHECKED,
    /** Quota was granted and is currently leased for this connection. */
    LEASED,
    /** Quota was denied; all subsequent pipelined frames must be dropped. */
    REJECTED,
    /** Connection became inactive and any leased quota has been returned. */
    RELEASED
  }

  protected final QuotaManager quotaManager;
  protected final FrontendMetrics metrics;
  protected LeaseState leaseState = LeaseState.UNCHECKED;
  @Nullable protected QuotaResponse quotaResponse;

  protected QuotaHandler(QuotaManager quotaManager, FrontendMetrics metrics) {
    this.quotaManager = quotaManager;
    this.metrics = metrics;
  }

  abstract String getUserId(ChannelHandlerContext ctx);

  /** Whether the user id is PII ans should not be logged. IP addresses are considered PII. */
  abstract boolean isUserIdPii();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    switch (leaseState) {
      case UNCHECKED -> {
        String userId = getUserId(ctx);
        checkNotNull(userId, "Cannot obtain User ID");
        QuotaResponse response = quotaManager.acquireQuota(new QuotaRequest(userId));
        if (!response.success()) {
          leaseState = LeaseState.REJECTED;
          ReferenceCountUtil.release(msg);
          String protocolName = ctx.channel().attr(PROTOCOL_KEY).get().name();
          metrics.registerQuotaRejection(protocolName, isUserIdPii() ? "none" : userId);
          throw new OverQuotaException(protocolName, isUserIdPii() ? "none" : userId);
        }
        quotaResponse = response;
        leaseState = LeaseState.LEASED;
        ctx.fireChannelRead(msg);
      }
      case LEASED -> ctx.fireChannelRead(msg);
      case REJECTED -> ReferenceCountUtil.release(msg);
      case RELEASED -> ReferenceCountUtil.release(msg);
    }
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
    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      // If no reads occurred before the connection is inactive (for example when the handshake
      // is not successful), or if quota was rejected or already released, no return is needed.
      if (leaseState == LeaseState.LEASED) {
        quotaManager.releaseQuota(QuotaRebate.create(checkNotNull(quotaResponse)));
        quotaResponse = null;
      }
      leaseState = LeaseState.RELEASED;
      ctx.fireChannelInactive();
    }
  }
}
