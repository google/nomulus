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
 * execution. Connection state transitions strictly through {@link EppConnectionState} on the
 * handler's single-threaded {@link ChannelHandlerContext#executor()}.
 */
public class EppServiceHandler extends SimpleChannelInboundHandler<ByteBuf> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final AttributeKey<String> CLIENT_CERTIFICATE_HASH_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_HASH_KEY");

  public static final AttributeKey<String> REMOTE_ADDRESS_KEY =
      AttributeKey.valueOf("REMOTE_ADDRESS_KEY");

  /** Explicit connection lifecycle state machine for an integrated EPP TCP connection. */
  @VisibleForTesting
  sealed interface EppConnectionState permits AwaitingTls, ActiveSession, Closing {}

  /** TLS handshake has not completed yet; no EPP frames may be processed. */
  @VisibleForTesting
  record AwaitingTls() implements EppConnectionState {}

  /** Active connection state after TLS handshake completed and IP quota was acquired. */
  @VisibleForTesting
  sealed interface ActiveSession extends EppConnectionState
      permits ReadyUnauthenticated, Authenticated {
    String clientAddress();

    String sslClientCertificateHash();

    @Nullable
    String sessionCookie();
  }

  /** TLS handshake succeeded and IP connection quota is held; waiting for EPP login. */
  @VisibleForTesting
  record ReadyUnauthenticated(
      String clientAddress,
      String sslClientCertificateHash,
      @Nullable String sessionCookie,
      @Nullable ScheduledFuture<?> preLoginTimeoutTask)
      implements ActiveSession {
    ReadyUnauthenticated {
      checkNotNull(clientAddress, "clientAddress cannot be null");
      checkNotNull(sslClientCertificateHash, "sslClientCertificateHash cannot be null");
    }
  }

  /** Registrar login succeeded and both IP and registrar connection quotas are held. */
  @VisibleForTesting
  record Authenticated(
      String clientAddress,
      String sslClientCertificateHash,
      String authenticatedRegistrarId,
      @Nullable String sessionCookie)
      implements ActiveSession {
    Authenticated {
      checkNotNull(clientAddress, "clientAddress cannot be null");
      checkNotNull(sslClientCertificateHash, "sslClientCertificateHash cannot be null");
      checkNotNull(authenticatedRegistrarId, "authenticatedRegistrarId cannot be null");
    }
  }

  /** Terminal state once closure is initiated or the channel becomes inactive. */
  @VisibleForTesting
  record Closing(
      @Nullable String acquiredIpAddress,
      @Nullable String acquiredRegistrarId,
      boolean resourcesReleased)
      implements EppConnectionState {}

  private final byte[] helloBytes;
  private final FrontendMetrics metrics;
  private final LocalConnectionLimiter localConnectionLimiter;
  private final EppServerQuotaManager commandQuotaManager;
  private final Supplier<String> idTokenSupplier;
  private final String projectId;
  private final int preLoginReadTimeoutSeconds;

  private EppConnectionState state = new AwaitingTls();

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
  EppConnectionState getState() {
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
    switch (state) {
      case AwaitingTls ignored -> {}
      case ReadyUnauthenticated ignored -> {
        return;
      }
      case Authenticated ignored -> {
        return;
      }
      case Closing ignored -> {
        return;
      }
    }

    if (!promise.isSuccess()) {
      logger.atWarning().withCause(promise.cause()).log("SSL handshake failed");
      transitionToClosing(ctx);
      return;
    }

    if (!ctx.channel().isActive()) {
      state = new Closing(null, null, true);
      return;
    }

    String clientAddress = resolveClientAddress(ctx);
    if (clientAddress == null || clientAddress.isEmpty()) {
      logger.atSevere().log("Failed to resolve client IP address, closing connection");
      transitionToClosing(ctx);
      return;
    }

    String sslClientCertificateHash = getCertificateHash(promise.getNow());
    ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).set(sslClientCertificateHash);

    // 1. Connection throttling (IP only pre-login)
    if (!localConnectionLimiter.acquireIp(clientAddress)) {
      metrics.registerQuotaRejection("epp_connection_ip", clientAddress);
      transitionToClosing(ctx);
      return;
    }

    // Schedule login timeout
    ScheduledFuture<?> preLoginTimeoutTask =
        ctx.executor()
            .schedule(() -> onPreLoginTimeout(ctx), preLoginReadTimeoutSeconds, TimeUnit.SECONDS);

    ReadyUnauthenticated readyState =
        new ReadyUnauthenticated(
            clientAddress, sslClientCertificateHash, null, preLoginTimeoutTask);
    state = readyState;

    metrics.registerActiveConnection("epp", sslClientCertificateHash, ctx.channel());

    // 2. Trigger initial EPP <greeting>
    handleEppFrame(ctx, readyState, Unpooled.wrappedBuffer(helloBytes));
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
    switch (state) {
      case ReadyUnauthenticated ready -> {
        logger.atWarning().log(
            "EPP login timeout expired for channel %s, closing connection", ctx.channel());
        metrics.registerQuotaRejection("epp_login_timeout", ready.clientAddress());
        transitionToClosing(ctx);
      }
      case AwaitingTls ignored -> {}
      case Authenticated ignored -> {}
      case Closing ignored -> {}
    }
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
    switch (state) {
      case AwaitingTls ignored -> {
        logger.atWarning().log(
            "Received EPP frame before TLS handshake completed on channel %s, closing connection",
            ctx.channel());
        transitionToClosing(ctx);
      }
      case ReadyUnauthenticated ready -> handleEppFrame(ctx, ready, frame);
      case Authenticated authenticated -> handleEppFrame(ctx, authenticated, frame);
      case Closing ignored ->
          logger.atFine().log("Ignoring EPP frame on closing channel %s", ctx.channel());
    }
  }

  private void handleEppFrame(
      ChannelHandlerContext ctx, ActiveSession activeSession, ByteBuf frame) {
    String xml = frame.toString(UTF_8);

    String throttleId =
        switch (activeSession) {
          case ReadyUnauthenticated ready -> ready.sslClientCertificateHash();
          case Authenticated authenticated -> authenticated.authenticatedRegistrarId();
        };
    if (!acquireCommandQuota(ctx, throttleId)) {
      return;
    }

    FakeHttpServletRequest req = buildServletRequest(activeSession, xml);
    FakeHttpServletResponse rsp = new FakeHttpServletResponse();
    String traceId =
        String.format(
            "projects/%s/traces/%s", projectId, UUID.randomUUID().toString().replace("-", ""));
    setCurrentTraceId(traceId);
    setCurrentRequest("POST", "/_dr/epp", "Netty-EPP", "EPP/1.0");
    try {
      requestHandler.handleRequest(req, rsp);
      processServletResponse(ctx, activeSession, rsp);
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

  private FakeHttpServletRequest buildServletRequest(ActiveSession activeSession, String xml) {
    FakeHttpServletRequest req = new FakeHttpServletRequest();
    req.setRequestUri("/_dr/epp");
    req.setBody(xml.getBytes(UTF_8));
    req.setHeader(ProxyHttpHeaders.CERTIFICATE_HASH, activeSession.sslClientCertificateHash());
    req.setHeader(ProxyHttpHeaders.IP_ADDRESS, activeSession.clientAddress());
    if (activeSession instanceof Authenticated authenticated) {
      req.setHeader(ProxyHttpHeaders.REGISTRAR_ID, authenticated.authenticatedRegistrarId());
    }
    if (activeSession.sessionCookie() != null) {
      req.setHeader("Cookie", activeSession.sessionCookie());
    }
    req.setHeader("Authorization", "Bearer " + idTokenSupplier.get());
    return req;
  }

  private void processServletResponse(
      ChannelHandlerContext ctx, ActiveSession activeSession, FakeHttpServletResponse rsp) {
    String setCookie = rsp.getHeader("Set-Cookie");
    String authRegistrarId = rsp.getHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR);
    boolean shouldClose = "close".equals(rsp.getHeader(ProxyHttpHeaders.EPP_SESSION));

    switch (activeSession) {
      case ReadyUnauthenticated ready -> {
        String updatedCookie = (setCookie != null) ? setCookie : ready.sessionCookie();
        if (shouldClose) {
          cancelPreLoginTimeout(ready.preLoginTimeoutTask());
          state = new Closing(ready.clientAddress(), null, false);
        } else if (authRegistrarId != null) {
          logger.atInfo().log("Registrar %s successfully authenticated", authRegistrarId);
          if (!localConnectionLimiter.acquireRegistrar(authRegistrarId)) {
            logger.atWarning().log(
                "Registrar %s exceeded concurrent connection limit, closing connection",
                authRegistrarId);
            metrics.registerQuotaRejection("epp_connection_registrar", authRegistrarId);
            transitionToClosing(ctx);
            return;
          }
          cancelPreLoginTimeout(ready.preLoginTimeoutTask());
          state =
              new Authenticated(
                  ready.clientAddress(),
                  ready.sslClientCertificateHash(),
                  authRegistrarId,
                  updatedCookie);
        } else {
          state =
              new ReadyUnauthenticated(
                  ready.clientAddress(),
                  ready.sslClientCertificateHash(),
                  updatedCookie,
                  ready.preLoginTimeoutTask());
        }
      }
      case Authenticated authenticated -> {
        String updatedCookie = (setCookie != null) ? setCookie : authenticated.sessionCookie();
        if (shouldClose) {
          state =
              new Closing(
                  authenticated.clientAddress(), authenticated.authenticatedRegistrarId(), false);
        } else {
          state =
              new Authenticated(
                  authenticated.clientAddress(),
                  authenticated.sslClientCertificateHash(),
                  authenticated.authenticatedRegistrarId(),
                  updatedCookie);
        }
      }
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
    switch (state) {
      case AwaitingTls ignored -> state = new Closing(null, null, false);
      case ReadyUnauthenticated ready -> {
        cancelPreLoginTimeout(ready.preLoginTimeoutTask());
        state = new Closing(ready.clientAddress(), null, false);
      }
      case Authenticated authenticated ->
          state =
              new Closing(
                  authenticated.clientAddress(), authenticated.authenticatedRegistrarId(), false);
      case Closing ignored -> {
        return;
      }
    }
    @SuppressWarnings("unused")
    Future<?> unusedFuture = ctx.close();
  }

  private static void cancelPreLoginTimeout(@Nullable ScheduledFuture<?> preLoginTimeoutTask) {
    if (preLoginTimeoutTask != null) {
      preLoginTimeoutTask.cancel(false);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    switch (state) {
      case AwaitingTls ignored -> state = new Closing(null, null, true);
      case ReadyUnauthenticated ready -> {
        cancelPreLoginTimeout(ready.preLoginTimeoutTask());
        localConnectionLimiter.releaseIp(ready.clientAddress());
        state = new Closing(ready.clientAddress(), null, true);
      }
      case Authenticated authenticated -> {
        localConnectionLimiter.releaseRegistrar(authenticated.authenticatedRegistrarId());
        localConnectionLimiter.releaseIp(authenticated.clientAddress());
        state =
            new Closing(
                authenticated.clientAddress(), authenticated.authenticatedRegistrarId(), true);
      }
      case Closing closing -> {
        if (!closing.resourcesReleased()) {
          if (closing.acquiredRegistrarId() != null) {
            localConnectionLimiter.releaseRegistrar(closing.acquiredRegistrarId());
          }
          if (closing.acquiredIpAddress() != null) {
            localConnectionLimiter.releaseIp(closing.acquiredIpAddress());
          }
          state = new Closing(closing.acquiredIpAddress(), closing.acquiredRegistrarId(), true);
        }
      }
    }
    super.channelInactive(ctx);
  }
}
