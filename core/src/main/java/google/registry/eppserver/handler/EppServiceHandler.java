// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.eppserver.handler;

import static com.google.common.base.Preconditions.checkNotNull;
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static google.registry.util.GcpJsonFormatter.setCurrentRequest;
import static google.registry.util.GcpJsonFormatter.setCurrentTraceId;
import static google.registry.util.GcpJsonFormatter.unsetCurrentRequest;
import static google.registry.util.X509Utils.getCertificateHash;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig.Config;
import google.registry.eppserver.EppProtocolModule.CommandQuota;
import google.registry.eppserver.metric.FrontendMetrics;
import google.registry.eppserver.quota.EppServerQuotaManager;
import google.registry.eppserver.quota.LocalConnectionLimiter;
import google.registry.module.RegistryServlet;
import google.registry.request.RequestHandler;
import google.registry.util.FakeHttpServletRequest;
import google.registry.util.FakeHttpServletResponse;
import google.registry.util.ProxyHttpHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Unified processor for EPP protocol traffic.
 *
 * <p>Consolidates connection lifecycle state, throttling, session management, and in-process
 * execution. Connection state transitions strictly through {@link ConnectionState} on the handler's
 * single-threaded {@link ChannelHandlerContext#executor()}.
 */
public class EppServiceHandler extends SimpleChannelInboundHandler<ByteBuf> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final AttributeKey<String> CLIENT_CERTIFICATE_HASH_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_HASH_KEY");

  public static final AttributeKey<String> REMOTE_ADDRESS_KEY =
      AttributeKey.valueOf("REMOTE_ADDRESS_KEY");

  /** Explicit connection lifecycle state for an integrated EPP TCP connection. */
  @VisibleForTesting
  enum ConnectionState {
    /** TLS handshake has not completed yet; no EPP frames may be processed. */
    AWAITING_TLS,
    /** TLS handshake succeeded and IP connection quota is held; waiting for EPP login. */
    READY_UNAUTHENTICATED,
    /** Registrar login succeeded and both IP and registrar connection quotas are held. */
    AUTHENTICATED,
    /** Closure has been initiated or the channel became inactive; frames are dropped. */
    CLOSING
  }

  private final byte[] helloBytes;
  private final FrontendMetrics metrics;
  private final LocalConnectionLimiter localConnectionLimiter;
  private final EppServerQuotaManager commandQuotaManager;
  private final Supplier<String> idTokenSupplier;
  private final String projectId;
  private final int preLoginReadTimeoutSeconds;

  private ConnectionState state = ConnectionState.AWAITING_TLS;
  // Non-null iff IP connection quota is currently held.
  @Nullable private String clientAddress;
  @Nullable private String sslClientCertificateHash;
  // Non-null iff registrar connection quota is currently held.
  @Nullable private String authenticatedRegistrarId;
  @Nullable private String sessionCookie;
  @Nullable private ScheduledFuture<?> preLoginTimeoutTask;

  @VisibleForTesting RequestHandler<?> requestHandler = RegistryServlet.component.requestHandler();

  @Inject
  public EppServiceHandler(
      @Named("hello") byte[] helloBytes,
      FrontendMetrics metrics,
      LocalConnectionLimiter localConnectionLimiter,
      @CommandQuota EppServerQuotaManager commandQuotaManager,
      @Named("idToken") Supplier<String> idTokenSupplier,
      @Config("projectId") String projectId,
      @Config("eppServerPreLoginReadTimeoutSeconds") int preLoginReadTimeoutSeconds) {
    this.helloBytes = helloBytes.clone();
    this.metrics = metrics;
    this.localConnectionLimiter = localConnectionLimiter;
    this.commandQuotaManager = commandQuotaManager;
    this.idTokenSupplier = idTokenSupplier;
    this.projectId = projectId;
    this.preLoginReadTimeoutSeconds = preLoginReadTimeoutSeconds;
  }

  @VisibleForTesting
  ConnectionState getState() {
    return state;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    Promise<X509Certificate> certPromise = ctx.channel().attr(CLIENT_CERTIFICATE_PROMISE_KEY).get();
    if (certPromise == null) {
      logger.atSevere().log("Missing client certificate promise, closing connection");
      transitionToClosing(ctx);
      return;
    }
    if (certPromise.isDone()) {
      // Run synchronously inside channelActive so a completed TLS handshake cannot be re-queued
      // on businessGroup behind an already-pipelined channelRead0 event.
      handleCertPromiseCompletion(ctx, certPromise);
    } else {
      certPromise.addListener(
          (Promise<X509Certificate> promise) -> {
            if (ctx.executor().inEventLoop()) {
              handleCertPromiseCompletion(ctx, promise);
            } else {
              ctx.executor().execute(() -> handleCertPromiseCompletion(ctx, promise));
            }
          });
    }
    super.channelActive(ctx);
  }

  private void handleCertPromiseCompletion(
      ChannelHandlerContext ctx, Promise<X509Certificate> promise) {
    if (state != ConnectionState.AWAITING_TLS) {
      return;
    }

    if (!promise.isSuccess()) {
      logger.atWarning().withCause(promise.cause()).log("SSL handshake failed");
      transitionToClosing(ctx);
      return;
    }

    if (!ctx.channel().isActive()) {
      state = ConnectionState.CLOSING;
      return;
    }

    String resolvedAddress = resolveClientAddress(ctx);
    if (resolvedAddress == null || resolvedAddress.isEmpty()) {
      logger.atSevere().log("Failed to resolve client IP address, closing connection");
      transitionToClosing(ctx);
      return;
    }

    String certHash = getCertificateHash(promise.getNow());
    ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).set(certHash);

    // 1. Connection throttling (IP only pre-login)
    if (!localConnectionLimiter.acquireIp(resolvedAddress)) {
      metrics.registerQuotaRejection("epp_connection_ip", resolvedAddress);
      transitionToClosing(ctx);
      return;
    }

    clientAddress = resolvedAddress;
    sslClientCertificateHash = certHash;
    preLoginTimeoutTask =
        ctx.executor()
            .schedule(() -> onPreLoginTimeout(ctx), preLoginReadTimeoutSeconds, TimeUnit.SECONDS);
    state = ConnectionState.READY_UNAUTHENTICATED;

    metrics.registerActiveConnection("epp", sslClientCertificateHash, ctx.channel());

    // 2. Trigger initial EPP <greeting>
    handleEppFrame(ctx, Unpooled.wrappedBuffer(helloBytes));
  }

  @Nullable
  private static String resolveClientAddress(ChannelHandlerContext ctx) {
    String clientAddress = ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
    if (clientAddress == null) {
      SocketAddress remoteAddress = ctx.channel().remoteAddress();
      if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
        clientAddress = inetSocketAddress.getAddress().getHostAddress();
      }
    }
    return clientAddress;
  }

  private void onPreLoginTimeout(ChannelHandlerContext ctx) {
    if (state != ConnectionState.READY_UNAUTHENTICATED) {
      return;
    }
    logger.atWarning().log(
        "EPP login timeout expired for channel %s, closing connection", ctx.channel());
    metrics.registerQuotaRejection("epp_login_timeout", checkNotNull(clientAddress));
    transitionToClosing(ctx);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
    switch (state) {
      case AWAITING_TLS -> {
        logger.atWarning().log(
            "Received EPP frame before TLS handshake completed on channel %s, closing connection",
            ctx.channel());
        transitionToClosing(ctx);
      }
      case READY_UNAUTHENTICATED -> handleEppFrame(ctx, frame);
      case AUTHENTICATED -> handleEppFrame(ctx, frame);
      case CLOSING ->
          logger.atFine().log("Ignoring EPP frame on closing channel %s", ctx.channel());
    }
  }

  private void handleEppFrame(ChannelHandlerContext ctx, ByteBuf frame) {
    String xml = frame.toString(UTF_8);

    String throttleId =
        (state == ConnectionState.AUTHENTICATED)
            ? checkNotNull(authenticatedRegistrarId)
            : checkNotNull(sslClientCertificateHash);
    if (!acquireCommandQuota(ctx, throttleId)) {
      return;
    }

    FakeHttpServletRequest req = buildServletRequest(xml);
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();
    String traceId =
        String.format(
            "projects/%s/traces/%s", projectId, UUID.randomUUID().toString().replace("-", ""));
    setCurrentTraceId(traceId);
    setCurrentRequest("POST", "/_dr/epp", "Netty-EPP", "EPP/1.0");
    try {
      requestHandler.handleRequest(req, rsp);
      processServletResponse(ctx, rsp);
    } catch (IOException | RuntimeException e) {
      logger.atSevere().withCause(e).log("Internal EPP processing error");
      transitionToClosing(ctx);
    } finally {
      setCurrentTraceId(null);
      unsetCurrentRequest();
    }
  }

  private boolean acquireCommandQuota(ChannelHandlerContext ctx, String throttleId) {
    if (!commandQuotaManager.acquireQuota(throttleId)) {
      metrics.registerQuotaRejection("epp_command", throttleId);
      transitionToClosing(ctx);
      return false;
    }
    return true;
  }

  private FakeHttpServletRequest buildServletRequest(String xml) {
    FakeHttpServletRequest req = new FakeHttpServletRequest();
    req.setRequestUri("/_dr/epp");
    req.setBody(xml.getBytes(UTF_8));
    req.setHeader(ProxyHttpHeaders.CERTIFICATE_HASH, checkNotNull(sslClientCertificateHash));
    req.setHeader(ProxyHttpHeaders.IP_ADDRESS, checkNotNull(clientAddress));
    if (state == ConnectionState.AUTHENTICATED) {
      req.setHeader(ProxyHttpHeaders.REGISTRAR_ID, checkNotNull(authenticatedRegistrarId));
    }
    if (sessionCookie != null) {
      req.setHeader("Cookie", sessionCookie);
    }
    req.setHeader("Authorization", "Bearer " + idTokenSupplier.get());
    return req;
  }

  private void processServletResponse(ChannelHandlerContext ctx, FakeHttpServletResponse rsp) {
    String setCookie = rsp.getHeader("Set-Cookie");
    if (setCookie != null) {
      sessionCookie = setCookie;
    }

    boolean shouldClose = "close".equals(rsp.getHeader(ProxyHttpHeaders.EPP_SESSION));
    String authRegistrarId = rsp.getHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR);
    if (shouldClose) {
      state = ConnectionState.CLOSING;
      cancelPreLoginTimeout();
    } else if (state == ConnectionState.READY_UNAUTHENTICATED && authRegistrarId != null) {
      logger.atInfo().log("Registrar %s successfully authenticated", authRegistrarId);
      if (!localConnectionLimiter.acquireRegistrar(authRegistrarId)) {
        logger.atWarning().log(
            "Registrar %s exceeded concurrent connection limit, closing connection",
            authRegistrarId);
        metrics.registerQuotaRejection("epp_connection_registrar", authRegistrarId);
        transitionToClosing(ctx);
        return;
      }
      authenticatedRegistrarId = authRegistrarId;
      cancelPreLoginTimeout();
      state = ConnectionState.AUTHENTICATED;
    }

    ByteBuf out = Unpooled.wrappedBuffer(rsp.getPayload());
    if (shouldClose) {
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    } else {
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.writeAndFlush(out);
    }
  }

  private void transitionToClosing(ChannelHandlerContext ctx) {
    if (state == ConnectionState.CLOSING) {
      return;
    }
    state = ConnectionState.CLOSING;
    releaseConnectionLimits();
    @SuppressWarnings("unused")
    Future<?> unusedFuture = ctx.close();
  }

  private void cancelPreLoginTimeout() {
    if (preLoginTimeoutTask != null) {
      preLoginTimeoutTask.cancel(false);
      preLoginTimeoutTask = null;
    }
  }

  private void releaseConnectionLimits() {
    cancelPreLoginTimeout();
    if (authenticatedRegistrarId != null) {
      localConnectionLimiter.releaseRegistrar(authenticatedRegistrarId);
      authenticatedRegistrarId = null;
    }
    if (clientAddress != null) {
      localConnectionLimiter.releaseIp(clientAddress);
      clientAddress = null;
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    state = ConnectionState.CLOSING;
    releaseConnectionLimits();
    super.channelInactive(ctx);
  }
}
