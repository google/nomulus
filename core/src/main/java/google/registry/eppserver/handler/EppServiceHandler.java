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
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.security.cert.X509Certificate;
import java.util.UUID;
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

  private String sslClientCertificateHash;
  private String clientAddress;
  private String registrarId; // The clID extracted from login
  private String sessionCookie;

  private boolean ipAcquired = false;
  private boolean certAcquired = false;

  @VisibleForTesting RequestHandler<?> requestHandler = RegistryServlet.component.requestHandler();

  @Inject
  public EppServiceHandler(
      @Named("hello") byte[] helloBytes,
      FrontendMetrics metrics,
      LocalConnectionLimiter localConnectionLimiter,
      @CommandQuota QuotaManager commandQuotaManager,
      @Named("idToken") Supplier<String> idTokenSupplier,
      @Config("projectId") String projectId) {
    this.helloBytes = helloBytes.clone();
    this.metrics = metrics;
    this.localConnectionLimiter = localConnectionLimiter;
    this.commandQuotaManager = commandQuotaManager;
    this.idTokenSupplier = idTokenSupplier;
    this.projectId = projectId;
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
              @SuppressWarnings("unused")
              Future<?> unusedFuture = ctx.close();
            }
          });
    }
    super.channelActive(ctx);
  }

  private void onSslHandshakeComplete(ChannelHandlerContext ctx, X509Certificate cert) {
    sslClientCertificateHash = getCertificateHash(cert);
    clientAddress = ctx.channel().attr(REMOTE_ADDRESS_KEY).get();
    ctx.channel().attr(CLIENT_CERTIFICATE_HASH_KEY).set(sslClientCertificateHash);

    // 1. Connection throttling (IP and Certificate)
    if (!localConnectionLimiter.acquireIp(clientAddress)) {
      metrics.registerQuotaRejection("epp_connection_ip", clientAddress);
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.close();
      return;
    }
    ipAcquired = true;

    if (!localConnectionLimiter.acquireCert(sslClientCertificateHash)) {
      metrics.registerQuotaRejection("epp_connection", sslClientCertificateHash);
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.close();
      return;
    }
    certAcquired = true;

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

    // 1. Maturing Identity: If we don't have clID yet, try to extract it from a login command.
    if (registrarId == null) {
      Matcher matcher = CLID_PATTERN.matcher(xml);
      if (matcher.find()) {
        registrarId = matcher.group(1).trim();
        logger.atInfo().log("Identified registrar: %s", registrarId);
      }
    }

    // 2. Command-level rate limiting
    // Use clID if identified, otherwise fallback to cert hash (for the login command itself).
    String throttleId = (registrarId != null) ? registrarId : sslClientCertificateHash;
    if (throttleId != null) {
      if (!commandQuotaManager.acquireQuota(new QuotaManager.QuotaRequest(throttleId)).success()) {
        metrics.registerQuotaRejection("epp_command", throttleId);
        @SuppressWarnings("unused")
        Future<?> unusedFuture = ctx.close();
        return;
      }
    }

    // 3. Execute command in-process
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

    FakeHttpServletResponse rsp = new FakeHttpServletResponse();
    String traceId =
        String.format(
            "projects/%s/traces/%s", projectId, UUID.randomUUID().toString().replace("-", ""));
    setCurrentTraceId(traceId);
    setCurrentRequest("POST", "/_dr/epp", "Netty-EPP", "EPP/1.0");
    try {
      requestHandler.handleRequest(req, rsp);
      String setCookie = rsp.getHeader("Set-Cookie");
      if (setCookie != null) {
        sessionCookie = setCookie;
      }

      ByteBuf out = Unpooled.wrappedBuffer(rsp.getPayload());
      if ("close".equals(rsp.getHeader(ProxyHttpHeaders.EPP_SESSION))) {
        @SuppressWarnings("unused")
        Future<?> unusedFuture = ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
      } else {
        @SuppressWarnings("unused")
        Future<?> unusedFuture = ctx.writeAndFlush(out);
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Internal EPP processing error");
      @SuppressWarnings("unused")
      Future<?> unusedFuture = ctx.close();
    } finally {
      setCurrentTraceId(null);
      unsetCurrentRequest();
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (certAcquired) {
      localConnectionLimiter.releaseCert(sslClientCertificateHash);
    }
    if (ipAcquired) {
      localConnectionLimiter.releaseIp(clientAddress);
    }
    super.channelInactive(ctx);
  }
}
