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

import static google.registry.eppserver.handler.EppProxyProtocolHandler.REMOTE_ADDRESS_KEY;
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
import google.registry.eppserver.quota.LocalConnectionLimiter;
import google.registry.eppserver.quota.QuotaManager;
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
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unified processor for EPP protocol traffic.
 *
 * <p>Consolidates throttling, session management, and in-process execution. Extracts registrar ID
 * (clID) directly from EPP login XML for accurate throttling.
 */
public class EppServiceHandler extends SimpleChannelInboundHandler<ByteBuf> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // Fast regex to extract clID from EPP login command without full XML parsing.
  private static final Pattern CLID_PATTERN =
      Pattern.compile("<clID>([^<]+)</clID>", Pattern.CASE_INSENSITIVE);

  public static final AttributeKey<String> CLIENT_CERTIFICATE_HASH_KEY =
      AttributeKey.valueOf("CLIENT_CERTIFICATE_HASH_KEY");

  private final byte[] helloBytes;
  private final FrontendMetrics metrics;
  private final LocalConnectionLimiter localConnectionLimiter;
  private final QuotaManager commandQuotaManager;
  private final Supplier<String> idTokenSupplier;
  private final String projectId;
  private final int preLoginReadTimeoutSeconds;

  private String sslClientCertificateHash;
  private String clientAddress;
  private String registrarId; // The clID extracted from login
  private String authenticatedRegistrarId; // The verified registrar ID after successful login
  private String sessionCookie;

  private boolean ipAcquired = false;
  private boolean registrarAcquired = false;
  private ScheduledFuture<?> preLoginTimeoutTask;

  @VisibleForTesting RequestHandler<?> requestHandler = RegistryServlet.component.requestHandler();

  @Inject
  public EppServiceHandler(
      @Named("hello") byte[] helloBytes,
      FrontendMetrics metrics,
      LocalConnectionLimiter localConnectionLimiter,
      @CommandQuota QuotaManager commandQuotaManager,
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

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    Promise<X509Certificate> certPromise = ctx.channel().attr(CLIENT_CERTIFICATE_PROMISE_KEY).get();
    if (certPromise != null) {
      certPromise.addListener(
          (Promise<X509Certificate> promise) -> {
            if (promise.isSuccess()) {
              ctx.executor().execute(() -> onSslHandshakeComplete(ctx, promise.getNow()));
            } else {
              logger.atWarning().withCause(promise.cause()).log("SSL handshake failed");
              closeConnection(ctx);
            }
          });
    }
    super.channelActive(ctx);
  }

  private void onSslHandshakeComplete(ChannelHandlerContext ctx, X509Certificate cert) {
    if (!ctx.channel().isActive()) {
      return;
    }
    sslClientCertificateHash = getCertificateHash(cert);
    clientAddress = ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
    ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).set(sslClientCertificateHash);

    // 1. Connection throttling (IP only pre-login)
    if (!localConnectionLimiter.acquireIp(clientAddress)) {
      metrics.registerQuotaRejection("epp_connection_ip", clientAddress);
      closeConnection(ctx);
      return;
    }
    ipAcquired = true;

    // Schedule login timeout
    preLoginTimeoutTask =
        ctx.executor()
            .schedule(
                () -> {
                  if (!registrarAcquired) {
                    logger.atWarning().log(
                        "EPP login timeout expired for channel %s, closing connection",
                        ctx.channel());
                    metrics.registerQuotaRejection("epp_login_timeout", clientAddress);
                    closeConnection(ctx);
                  }
                },
                preLoginReadTimeoutSeconds,
                TimeUnit.SECONDS);

    metrics.registerActiveConnection("epp", sslClientCertificateHash, ctx.channel());

    // 2. Trigger initial EPP <greeting>
    handleEppFrame(ctx, Unpooled.wrappedBuffer(helloBytes));
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
    handleEppFrame(ctx, frame);
  }

  private void handleEppFrame(ChannelHandlerContext ctx, ByteBuf frame) {
    String xml = frame.toString(UTF_8);

    extractRegistrarId(xml);

    if (!acquireCommandQuota(ctx)) {
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
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Internal EPP processing error");
      closeConnection(ctx);
    } finally {
      setCurrentTraceId(null);
      unsetCurrentRequest();
    }
  }

  private void extractRegistrarId(String xml) {
    if (registrarId == null) {
      Matcher matcher = CLID_PATTERN.matcher(xml);
      if (matcher.find()) {
        registrarId = matcher.group(1).trim();
        logger.atInfo().log("Identified registrar: %s", registrarId);
      }
    }
  }

  private boolean acquireCommandQuota(ChannelHandlerContext ctx) {
    String throttleId =
        (authenticatedRegistrarId != null) ? authenticatedRegistrarId : sslClientCertificateHash;
    if (throttleId != null) {
      if (!commandQuotaManager.acquireQuota(new QuotaManager.QuotaRequest(throttleId)).success()) {
        metrics.registerQuotaRejection("epp_command", throttleId);
        closeConnection(ctx);
        return false;
      }
    }
    return true;
  }

  private FakeHttpServletRequest buildServletRequest(String xml) {
    FakeHttpServletRequest req = new FakeHttpServletRequest();
    req.setRequestUri("/_dr/epp");
    req.setBody(xml.getBytes(UTF_8));
    req.setHeader(ProxyHttpHeaders.CERTIFICATE_HASH, sslClientCertificateHash);
    req.setHeader(ProxyHttpHeaders.IP_ADDRESS, clientAddress);
    if (registrarId != null) {
      req.setHeader(ProxyHttpHeaders.REGISTRAR_ID, registrarId);
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

    String authRegistrarId = rsp.getHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR);
    if (authRegistrarId != null && !registrarAcquired) {
      logger.atInfo().log("Registrar %s successfully authenticated", authRegistrarId);
      if (!localConnectionLimiter.acquireRegistrar(authRegistrarId)) {
        logger.atWarning().log(
            "Registrar %s exceeded concurrent connection limit, closing connection",
            authRegistrarId);
        metrics.registerQuotaRejection("epp_connection_registrar", authRegistrarId);
        closeConnection(ctx);
        return;
      }
      registrarAcquired = true;
      authenticatedRegistrarId = authRegistrarId;
      registrarId = authRegistrarId;

      // Cancel pre-login timeout task
      if (preLoginTimeoutTask != null) {
        preLoginTimeoutTask.cancel(false);
        preLoginTimeoutTask = null;
      }
    }

    ByteBuf out = Unpooled.wrappedBuffer(rsp.getPayload());
    if ("close".equals(rsp.getHeader(ProxyHttpHeaders.EPP_SESSION))) {
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
    } else {
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.writeAndFlush(out);
    }
  }

  private void closeConnection(ChannelHandlerContext ctx) {
    @SuppressWarnings("unused")
    Future<?> unusedFuture = ctx.close();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (preLoginTimeoutTask != null) {
      preLoginTimeoutTask.cancel(false);
      preLoginTimeoutTask = null;
    }
    if (registrarAcquired) {
      localConnectionLimiter.releaseRegistrar(authenticatedRegistrarId);
    }
    if (ipAcquired) {
      localConnectionLimiter.releaseIp(clientAddress);
    }
    super.channelInactive(ctx);
  }
}
