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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.eppserver.handler.EppServiceHandler.REMOTE_ADDRESS_KEY;
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import google.registry.eppserver.handler.EppServiceHandler.ConnectionState;
import google.registry.eppserver.metric.FrontendMetrics;
import google.registry.eppserver.quota.EppServerQuotaManager;
import google.registry.eppserver.quota.LocalConnectionLimiter;
import google.registry.networking.handler.SslServerInitializer;
import google.registry.networking.module.CertificateSupplierModule.TlsCredentials;
import google.registry.request.RequestHandler;
import google.registry.testing.FakeClock;
import google.registry.util.FakeHttpServletRequest;
import google.registry.util.FakeHttpServletResponse;
import google.registry.util.ProxyHttpHeaders;
import google.registry.util.SelfSignedCaCertificate;
import google.registry.util.X509Utils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.Attribute;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EppServiceHandlerTest {

  @Mock private FrontendMetrics metrics;
  @Mock private LocalConnectionLimiter localConnectionLimiter;
  @Mock private EppServerQuotaManager commandQuotaManager;
  @Mock private Supplier<String> idTokenSupplier;
  @Mock private ChannelHandlerContext ctx;
  @Mock private Channel channel;
  @Mock private EventExecutor executor;
  @Mock private ScheduledFuture<?> scheduledFuture;
  @Mock private RequestHandler<?> requestHandler;

  @Mock private Attribute<Promise<X509Certificate>> certPromiseAttr;
  @Mock private Attribute<String> remoteAddressAttr;
  @Mock private Attribute<String> certHashAttr;
  @Mock private X509Certificate certificate;

  private EppServiceHandler handler;
  private DefaultPromise<X509Certificate> certPromise;

  @BeforeEach
  void setUp() {
    handler =
        new EppServiceHandler(
            new byte[] {'h', 'e', 'l', 'l', 'o'},
            metrics,
            localConnectionLimiter,
            commandQuotaManager,
            idTokenSupplier,
            "test-project",
            10); // preLoginReadTimeoutSeconds

    handler.requestHandler = requestHandler;

    lenient().when(ctx.channel()).thenReturn(channel);
    lenient().when(ctx.executor()).thenReturn(executor);
    lenient().when(channel.isActive()).thenReturn(true);

    lenient()
        .doAnswer(
            invocation -> {
              Runnable runnable = invocation.getArgument(0);
              runnable.run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));

    lenient()
        .doReturn(scheduledFuture)
        .when(executor)
        .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

    lenient().when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);
  }

  private void setUpSuccessfulHandshake() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);

    when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
    when(remoteAddressAttr.get()).thenReturn("192.168.1.1");
    when(channel.attr(EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY)).thenReturn(certHashAttr);
    when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});

    when(localConnectionLimiter.acquireIp(any(String.class))).thenReturn(true);

    certPromise.setSuccess(certificate);
  }

  @Test
  void testChannelActive_success() throws Exception {
    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.getWriter().write("<epp><greeting/></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);
    when(idTokenSupplier.get()).thenReturn("fake_id_token");

    setUpSuccessfulHandshake();

    verify(metrics).registerActiveConnection(eq("epp"), any(String.class), eq(channel));
    // Verify the initial <greeting> frame is sent back to the client
    verify(ctx)
        .writeAndFlush(
            argThat(
                (ByteBuf buf) -> {
                  String xml = buf.toString(UTF_8);
                  return xml.equals("<epp><greeting/></epp>");
                }));
  }

  @Test
  void testChannelActive_ipQuotaRejected() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);

    when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
    when(remoteAddressAttr.get()).thenReturn("192.168.1.1");
    when(channel.attr(EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY)).thenReturn(certHashAttr);
    when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});

    when(localConnectionLimiter.acquireIp(any(String.class))).thenReturn(false);

    certPromise.setSuccess(certificate);

    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(metrics).registerQuotaRejection(eq("epp_connection_ip"), eq("192.168.1.1"));
    verify(ctx).close();
    handler.channelInactive(ctx);
    verify(localConnectionLimiter, never()).releaseIp(any());
  }

  @Test
  void testChannelRead0_registrarQuotaRejected() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);

    String eppLoginXml = "<epp><command><login><clID>RegistrarA</clID></login></command></epp>";
    ByteBuf inFrame = Unpooled.wrappedBuffer(eppLoginXml.getBytes(UTF_8));

    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "RegistrarA");
              rsp.getWriter().write("<epp><response>success</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    when(localConnectionLimiter.acquireRegistrar("RegistrarA")).thenReturn(false);

    handler.channelRead0(ctx, inFrame);

    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(metrics).registerQuotaRejection(eq("epp_connection_registrar"), eq("RegistrarA"));
    verify(ctx).close();
    // Cleanup is idempotent across transitionToClosing() and channelInactive().
    handler.channelInactive(ctx);
    verify(localConnectionLimiter, times(1)).releaseIp("192.168.1.1");
    verify(localConnectionLimiter, never()).releaseRegistrar(any());
  }

  @Test
  void testChannelRead0_extractsClidAndForwardsRequest() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);

    String eppLoginXml = "<epp><command><login><clID>RegistrarA</clID></login></command></epp>";
    ByteBuf inFrame = Unpooled.wrappedBuffer(eppLoginXml.getBytes(UTF_8));

    // Simulate FakeHttpServletResponse modifying headers and writing payload
    doAnswer(
            invocation -> {
              FakeHttpServletRequest req = invocation.getArgument(0);
              FakeHttpServletResponse rsp = invocation.getArgument(1);

              rsp.setHeader("Set-Cookie", "SESSION_INFO=Y2xpZW50SWQ9UmVnaXN0cmFyQQ==");
              rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "RegistrarA");
              rsp.getWriter().write("<epp><response>success</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    // Mock successful registrar connection acquisition
    when(localConnectionLimiter.acquireRegistrar("RegistrarA")).thenReturn(true);

    handler.channelRead0(ctx, inFrame);

    // Verify command quota was requested for the cert hash pre-login
    String certHash = X509Utils.getCertificateHash(certificate);
    verify(commandQuotaManager, times(2)).acquireQuota(eq(certHash));
    verify(localConnectionLimiter).acquireRegistrar("RegistrarA");
    verify(scheduledFuture).cancel(eq(false));

    // Verify the response from the servlet was written back to the channel
    verify(ctx)
        .writeAndFlush(
            argThat(
                (ByteBuf buf) -> {
                  String xml = buf.toString(UTF_8);
                  return xml.equals("<epp><response>success</response></epp>");
                }));

    // On the next request, the cookie should be injected
    String eppCommandXml = "<epp><command><check></check></command></epp>";
    ByteBuf inFrame2 = Unpooled.wrappedBuffer(eppCommandXml.getBytes(UTF_8));

    doAnswer(
            invocation -> {
              FakeHttpServletRequest req = invocation.getArgument(0);
              // Verify the cookie was properly propagated
              if (!"SESSION_INFO=Y2xpZW50SWQ9UmVnaXN0cmFyQQ==".equals(req.getHeader("Cookie"))) {
                throw new AssertionError("Missing or incorrect cookie");
              }
              // Verify the registrar ID was properly propagated
              if (!"RegistrarA".equals(req.getHeader(ProxyHttpHeaders.REGISTRAR_ID))) {
                throw new AssertionError("Missing or incorrect registrar ID");
              }
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(ctx, inFrame2);

    // Verify command quota was requested for the authenticated registrar post-login
    verify(commandQuotaManager).acquireQuota(eq("RegistrarA"));
  }

  @Test
  void testChannelRead0_commandQuotaRejected() throws Exception {
    setUpSuccessfulHandshake();

    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(false);

    String eppXml = "<epp><command><check></check></command></epp>";
    ByteBuf inFrame = Unpooled.wrappedBuffer(eppXml.getBytes(UTF_8));

    handler.channelRead0(ctx, inFrame);

    verify(metrics).registerQuotaRejection(eq("epp_command"), any(String.class));
    verify(ctx).close();
  }

  @Test
  void testChannelRead0_closeSessionHeaderClosesChannel() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);

    String eppLogoutXml = "<epp><command><logout/></command></epp>";
    ByteBuf inFrame = Unpooled.wrappedBuffer(eppLogoutXml.getBytes(UTF_8));

    ChannelFuture mockFuture = mock(ChannelFuture.class);
    when(ctx.writeAndFlush(any(ByteBuf.class))).thenReturn(mockFuture);

    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.setHeader(ProxyHttpHeaders.EPP_SESSION, "close");
              rsp.getWriter().write("<epp><response>logout success</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(ctx, inFrame);

    // Verify the handler added the CLOSE listener to the flush future
    verify(mockFuture).addListener(ChannelFutureListener.CLOSE);
  }

  @Test
  void testChannelInactive_releasesIp() throws Exception {
    setUpSuccessfulHandshake();

    handler.channelInactive(ctx);

    // Verify the in-memory limiter releases IP
    verify(localConnectionLimiter).releaseIp(eq("192.168.1.1"));
  }

  @Test
  void testChannelInactive_postLogin_releasesIpAndRegistrar() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(String.class))).thenReturn(true);
    when(localConnectionLimiter.acquireRegistrar("RegistrarA")).thenReturn(true);

    String eppLoginXml = "<epp><command><login><clID>RegistrarA</clID></login></command></epp>";
    ByteBuf inFrame = Unpooled.wrappedBuffer(eppLoginXml.getBytes(UTF_8));

    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.setHeader("Set-Cookie", "SESSION_INFO=Y2xpZW50SWQ9UmVnaXN0cmFyQQ==");
              rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "RegistrarA");
              rsp.getWriter().write("<epp><response>success</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(ctx, inFrame);

    handler.channelInactive(ctx);

    // Verify the in-memory limiter releases both IP and Registrar
    verify(localConnectionLimiter).releaseIp(eq("192.168.1.1"));
    verify(localConnectionLimiter).releaseRegistrar(eq("RegistrarA"));
  }

  @Test
  void testChannelActive_loginTimeoutTriggered() throws Exception {
    ArgumentCaptor<Runnable> timeoutTaskCaptor = ArgumentCaptor.forClass(Runnable.class);
    when(executor.schedule(timeoutTaskCaptor.capture(), eq(10L), eq(TimeUnit.SECONDS)))
        .thenReturn(null);

    setUpSuccessfulHandshake();

    Runnable timeoutTask = timeoutTaskCaptor.getValue();
    assertThat(timeoutTask).isNotNull();

    ChannelFuture closeFuture = mock(ChannelFuture.class);
    when(ctx.close()).thenReturn(closeFuture);

    timeoutTask.run();

    verify(metrics).registerQuotaRejection("epp_login_timeout", "192.168.1.1");
    verify(ctx).close();
  }

  @Test
  void testFallbackToSocketIpAddress_whenRemoteAddressKeyIsNull() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);

    // Mock REMOTE_ADDRESS_KEY to return null
    when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
    when(remoteAddressAttr.get()).thenReturn(null);

    // Mock channel.remoteAddress() to return a socket address
    InetSocketAddress socketAddress = new InetSocketAddress("10.0.0.1", 12345);
    when(channel.remoteAddress()).thenReturn(socketAddress);

    when(channel.attr(EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY)).thenReturn(certHashAttr);
    when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});

    when(localConnectionLimiter.acquireIp("10.0.0.1")).thenReturn(true);
    when(idTokenSupplier.get()).thenReturn("fake_id_token");

    // Stub request handler to capture the request
    ArgumentCaptor<FakeHttpServletRequest> requestCaptor =
        ArgumentCaptor.forClass(FakeHttpServletRequest.class);
    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.getWriter().write("<epp><greeting/></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(requestCaptor.capture(), any(FakeHttpServletResponse.class));

    certPromise.setSuccess(certificate);

    // Verify it resolved IP to 10.0.0.1
    verify(localConnectionLimiter).acquireIp("10.0.0.1");
    FakeHttpServletRequest capturedRequest = requestCaptor.getValue();
    assertThat(capturedRequest).isNotNull();
    assertThat(capturedRequest.getHeader(ProxyHttpHeaders.IP_ADDRESS)).isEqualTo("10.0.0.1");
  }

  @Test
  void testFallbackToSocketIpAddress_fails_closesConnection() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);

    // Mock REMOTE_ADDRESS_KEY to return null
    when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
    when(remoteAddressAttr.get()).thenReturn(null);

    // Mock channel.remoteAddress() to return a non-InetSocketAddress (e.g. mock SocketAddress)
    SocketAddress mockSocketAddress = mock(SocketAddress.class);
    when(channel.remoteAddress()).thenReturn(mockSocketAddress);

    certPromise.setSuccess(certificate);

    // Verify it logged error and closed connection
    verify(ctx).close();
  }

  @Test
  void testChannelActive_missingCertPromise_closesImmediately() throws Exception {
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(null);

    handler.channelActive(ctx);

    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(ctx).close();
    verify(requestHandler, never()).handleRequest(any(), any());
  }

  @Test
  void testChannelActive_failedCertPromise_closesConnection() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);
    certPromise.setFailure(new RuntimeException("TLS handshake failed"));

    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(ctx).close();
    verify(localConnectionLimiter, never()).acquireIp(any());
    verify(requestHandler, never()).handleRequest(any(), any());
  }

  @Test
  void testChannelRead0_beforeTlsHandshakeCompletes_rejectsAndCloses() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);
    assertThat(handler.getState()).isEqualTo(ConnectionState.AWAITING_TLS);

    // Frame arrives before TLS handshake completes: must not bypass quota or invoke requestHandler.
    ByteBuf prematureFrame = Unpooled.wrappedBuffer("<epp><command/></epp>".getBytes(UTF_8));
    handler.channelRead0(ctx, prematureFrame);

    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(ctx).close();
    // Late TLS completion after transitioning to CLOSING must be ignored.
    certPromise.setSuccess(certificate);
    verify(localConnectionLimiter, never()).acquireIp(any());
    verify(requestHandler, never()).handleRequest(any(), any());
    verify(commandQuotaManager, never()).acquireQuota(any());
  }

  @Test
  void testChannelRead0_failedLoginDoesNotLatchClid() throws Exception {
    setUpSuccessfulHandshake();
    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(localConnectionLimiter.acquireRegistrar("RealRegistrar")).thenReturn(true);

    AtomicInteger callCount = new AtomicInteger();
    doAnswer(
            invocation -> {
              FakeHttpServletRequest req = invocation.getArgument(0);
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              int call = callCount.incrementAndGet();
              if (call == 1) {
                // Failed login with spoofed clID must not receive REGISTRAR_ID header.
                assertThat(req.getHeader(ProxyHttpHeaders.REGISTRAR_ID)).isNull();
                rsp.getWriter().write("<epp><response><result code=\"2200\"/></response></epp>");
              } else if (call == 2) {
                // Second login attempt must still not carry the failed spoofed clID.
                assertThat(req.getHeader(ProxyHttpHeaders.REGISTRAR_ID)).isNull();
                rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "RealRegistrar");
                rsp.setHeader("Set-Cookie", "SESSION_INFO=cmVhbA==");
                rsp.getWriter().write("<epp><response><result code=\"1000\"/></response></epp>");
              } else {
                // Authenticated command carries verified registrar ID.
                assertThat(req.getHeader(ProxyHttpHeaders.REGISTRAR_ID)).isEqualTo("RealRegistrar");
                rsp.getWriter().write("<epp><response><result code=\"1000\"/></response></epp>");
              }
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(
        ctx,
        Unpooled.wrappedBuffer(
            "<epp><command><login><clID>SpoofedRegistrar</clID></login></command></epp>"
                .getBytes(UTF_8)));
    assertThat(handler.getState()).isEqualTo(ConnectionState.READY_UNAUTHENTICATED);

    handler.channelRead0(
        ctx,
        Unpooled.wrappedBuffer(
            "<epp><command><login><clID>RealRegistrar</clID></login></command></epp>"
                .getBytes(UTF_8)));
    assertThat(handler.getState()).isEqualTo(ConnectionState.AUTHENTICATED);

    handler.channelRead0(
        ctx, Unpooled.wrappedBuffer("<epp><command><check/></command></epp>".getBytes(UTF_8)));

    verify(commandQuotaManager).acquireQuota("RealRegistrar");
    verify(commandQuotaManager, never()).acquireQuota("SpoofedRegistrar");
  }

  @Test
  void testChannelRead0_pipelinedFrameAfterLogout_droppedAndCleanupIdempotent() throws Exception {
    setUpSuccessfulHandshake();
    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(localConnectionLimiter.acquireRegistrar("RegistrarA")).thenReturn(true);

    ChannelFuture mockFuture = mock(ChannelFuture.class);
    when(ctx.writeAndFlush(any(ByteBuf.class))).thenReturn(mockFuture);

    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "RegistrarA");
              rsp.getWriter().write("<epp><response>login ok</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(
        ctx, Unpooled.wrappedBuffer("<epp><command><login/></command></epp>".getBytes(UTF_8)));
    assertThat(handler.getState()).isEqualTo(ConnectionState.AUTHENTICATED);

    doAnswer(
            invocation -> {
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              rsp.setHeader(ProxyHttpHeaders.EPP_SESSION, "close");
              rsp.getWriter().write("<epp><response>logout ok</response></epp>");
              return null;
            })
        .when(requestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

    handler.channelRead0(
        ctx, Unpooled.wrappedBuffer("<epp><command><logout/></command></epp>".getBytes(UTF_8)));
    assertThat(handler.getState()).isEqualTo(ConnectionState.CLOSING);
    verify(mockFuture).addListener(ChannelFutureListener.CLOSE);

    // Pipelined frame after logout must be dropped without invoking requestHandler again.
    handler.channelRead0(
        ctx, Unpooled.wrappedBuffer("<epp><command><check/></command></epp>".getBytes(UTF_8)));
    // 1 greeting + 1 login + 1 logout = 3 invocations total (not 4).
    verify(requestHandler, times(3)).handleRequest(any(), any());

    // Duplicate channelInactive calls must release IP and registrar exactly once.
    handler.channelInactive(ctx);
    handler.channelInactive(ctx);
    verify(localConnectionLimiter, times(1)).releaseIp("192.168.1.1");
    verify(localConnectionLimiter, times(1)).releaseRegistrar("RegistrarA");
  }

  @Test
  void testEmbeddedChannel_alreadyCompletedCertPromise_handlesHandshakeBeforePipelinedRead()
      throws Exception {
    SelfSignedCaCertificate clientSsc = SelfSignedCaCertificate.create(new FakeClock());
    String expectedCertHash = X509Utils.getCertificateHash(clientSsc.cert());
    LocalConnectionLimiter realLimiter = new LocalConnectionLimiter(5, 5);
    EppServiceHandler embeddedHandler =
        new EppServiceHandler(
            "<epp><hello/></epp>".getBytes(UTF_8),
            metrics,
            realLimiter,
            commandQuotaManager,
            () -> "fake_id_token",
            "test-project",
            10);

    BlockingQueue<String> observedBodies = new LinkedBlockingQueue<>();
    RequestHandler<?> embeddedRequestHandler = mock(RequestHandler.class);
    doAnswer(
            invocation -> {
              FakeHttpServletRequest req = invocation.getArgument(0);
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              assertThat(req.getHeader(ProxyHttpHeaders.CERTIFICATE_HASH))
                  .isEqualTo(expectedCertHash);
              assertThat(req.getHeader(ProxyHttpHeaders.IP_ADDRESS)).isEqualTo("192.0.2.10");
              String body = new String(req.getInputStream().readAllBytes(), UTF_8);
              observedBodies.add(body);
              rsp.getWriter().write("<epp><response>" + body + "</response></epp>");
              return null;
            })
        .when(embeddedRequestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));
    embeddedHandler.requestHandler = embeddedRequestHandler;

    // Complete certPromise BEFORE channelActive runs (simulates the businessGroup race where
    // eventGroup finished TLS and queued a read before channelActive ran on businessGroup).
    DefaultPromise<X509Certificate> completedPromise =
        new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    completedPromise.setSuccess(clientSsc.cert());

    EmbeddedChannel embeddedChannel =
        new EmbeddedChannel(
            new ChannelInitializer<EmbeddedChannel>() {
              @Override
              protected void initChannel(EmbeddedChannel ch) {
                ch.attr(CLIENT_CERTIFICATE_PROMISE_KEY).set(completedPromise);
                ch.attr(REMOTE_ADDRESS_KEY).set("192.0.2.10");
                ch.pipeline().addLast(embeddedHandler);
              }
            });

    assertThat(embeddedHandler.getState()).isEqualTo(ConnectionState.READY_UNAUTHENTICATED);
    embeddedChannel.writeInbound(Unpooled.wrappedBuffer("<epp><command/></epp>".getBytes(UTF_8)));

    assertThat(observedBodies.poll()).isEqualTo("<epp><hello/></epp>");
    assertThat(observedBodies.poll()).isEqualTo("<epp><command/></epp>");
    verify(commandQuotaManager, times(2)).acquireQuota(expectedCertHash);
    embeddedChannel.finishAndReleaseAll();
  }

  @Test
  void testChannelActive_crossExecutorCertPromise_dispatchesToHandlerExecutor() throws Exception {
    EventExecutorGroup businessGroup = new DefaultEventExecutorGroup(1);
    EventExecutor businessExecutor = businessGroup.next();
    try {
      certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
      when(ctx.executor()).thenReturn(businessExecutor);
      when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
      when(certPromiseAttr.get()).thenReturn(certPromise);
      when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
      when(remoteAddressAttr.get()).thenReturn("192.168.1.1");
      when(channel.attr(EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY)).thenReturn(certHashAttr);
      when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});
      when(localConnectionLimiter.acquireIp("192.168.1.1")).thenReturn(true);
      when(idTokenSupplier.get()).thenReturn("fake_id_token");

      CountDownLatch greetingDispatched = new CountDownLatch(1);
      AtomicBoolean ranOnBusinessExecutor = new AtomicBoolean(false);
      doAnswer(
              invocation -> {
                ranOnBusinessExecutor.set(businessExecutor.inEventLoop());
                FakeHttpServletResponse rsp = invocation.getArgument(1);
                rsp.getWriter().write("<epp><greeting/></epp>");
                greetingDispatched.countDown();
                return null;
              })
          .when(requestHandler)
          .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));

      businessExecutor
          .submit(
              () -> {
                handler.channelActive(ctx);
                return null;
              })
          .sync();
      assertThat(businessExecutor.inEventLoop()).isFalse();

      // Complete the TLS promise off businessExecutor (simulating Netty's I/O event loop).
      certPromise.setSuccess(certificate);
      assertThat(greetingDispatched.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(ranOnBusinessExecutor.get()).isTrue();
      assertThat(businessExecutor.submit(handler::getState).get(5, TimeUnit.SECONDS))
          .isEqualTo(ConnectionState.READY_UNAUTHENTICATED);

      businessExecutor
          .submit(
              () -> {
                handler.channelInactive(ctx);
                return null;
              })
          .sync();
      verify(localConnectionLimiter).releaseIp("192.168.1.1");
    } finally {
      businessGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }

  @Test
  void testLocalChannel_fullPipeline_tlsLoginCommandLogout() throws Exception {
    Instant certNotBefore = Instant.parse("2000-01-01T00:00:00Z");
    Instant certNotAfter = Instant.parse("2099-01-01T00:00:00Z");
    SelfSignedCaCertificate serverSsc =
        SelfSignedCaCertificate.create("www.example.tld", certNotBefore, certNotAfter);
    SelfSignedCaCertificate clientSsc =
        SelfSignedCaCertificate.create("client.example.tld", certNotBefore, certNotAfter);
    String expectedCertHash = X509Utils.getCertificateHash(clientSsc.cert());

    EventLoopGroup eventGroup = new DefaultEventLoopGroup(1);
    LocalConnectionLimiter realLimiter = new LocalConnectionLimiter(1, 1);
    BlockingQueue<String> clientReceived = new LinkedBlockingQueue<>();
    CountDownLatch serverClosed = new CountDownLatch(1);
    CountDownLatch serverInactive = new CountDownLatch(1);

    EppServiceHandler localHandler =
        new EppServiceHandler(
            "<epp><hello/></epp>".getBytes(UTF_8),
            metrics,
            realLimiter,
            commandQuotaManager,
            () -> "fake_id_token",
            "test-project",
            30);

    RequestHandler<?> localRequestHandler = mock(RequestHandler.class);
    doAnswer(
            invocation -> {
              FakeHttpServletRequest req = invocation.getArgument(0);
              FakeHttpServletResponse rsp = invocation.getArgument(1);
              assertThat(req.getHeader(ProxyHttpHeaders.CERTIFICATE_HASH))
                  .isEqualTo(expectedCertHash);
              assertThat(req.getHeader(ProxyHttpHeaders.IP_ADDRESS)).isEqualTo("203.0.113.5");
              String body = new String(req.getInputStream().readAllBytes(), UTF_8);
              if (body.contains("<hello/>")) {
                rsp.getWriter().write("<epp><greeting/></epp>");
              } else if (body.contains("<login>")) {
                rsp.setHeader(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "TheRegistrar");
                rsp.setHeader("Set-Cookie", "SESSION_INFO=dGhlcmVn");
                rsp.getWriter().write("<epp><response>login-ok</response></epp>");
              } else if (body.contains("<logout/>")) {
                assertThat(req.getHeader(ProxyHttpHeaders.REGISTRAR_ID)).isEqualTo("TheRegistrar");
                assertThat(req.getHeader("Cookie")).isEqualTo("SESSION_INFO=dGhlcmVn");
                rsp.setHeader(ProxyHttpHeaders.EPP_SESSION, "close");
                rsp.getWriter().write("<epp><response>logout-ok</response></epp>");
              }
              return null;
            })
        .when(localRequestHandler)
        .handleRequest(any(FakeHttpServletRequest.class), any(FakeHttpServletResponse.class));
    localHandler.requestHandler = localRequestHandler;

    SslServerInitializer<LocalChannel> sslServerInitializer =
        new SslServerInitializer<>(
            true,
            false,
            SslProvider.JDK,
            Suppliers.ofInstance(
                new TlsCredentials(serverSsc.key(), ImmutableList.of(serverSsc.cert()))));

    LocalAddress address = new LocalAddress("EPP_SERVICE_HANDLER_FULL_PIPELINE");
    Channel serverChannel = null;
    try {
      serverChannel =
          new ServerBootstrap()
              .group(eventGroup)
              .channel(LocalServerChannel.class)
              .childHandler(
                  new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel ch) {
                      ch.attr(REMOTE_ADDRESS_KEY).set("203.0.113.5");
                      ch.pipeline()
                          .addLast(sslServerInitializer)
                          .addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, -4, 4))
                          .addLast(new LengthFieldPrepender(4, true))
                          .addLast("EppServiceHandler", localHandler)
                          .addLast(
                              new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                  serverInactive.countDown();
                                  ctx.fireChannelInactive();
                                }
                              });
                      ch.closeFuture().addListener(f -> serverClosed.countDown());
                    }
                  })
              .bind(address)
              .sync()
              .channel();

      Channel clientChannel =
          new Bootstrap()
              .group(eventGroup)
              .channel(LocalChannel.class)
              .handler(
                  new ChannelInitializer<LocalChannel>() {
                    @Override
                    protected void initChannel(LocalChannel ch) throws Exception {
                      ch.pipeline()
                          .addLast(
                              SslContextBuilder.forClient()
                                  .trustManager(serverSsc.cert())
                                  .keyManager(clientSsc.key(), clientSsc.cert())
                                  .sslProvider(SslProvider.JDK)
                                  .build()
                                  .newHandler(ch.alloc(), "www.example.tld", 700))
                          .addLast(new LengthFieldBasedFrameDecoder(65536, 0, 4, -4, 4))
                          .addLast(new LengthFieldPrepender(4, true))
                          .addLast(
                              new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                  ByteBuf buf = (ByteBuf) msg;
                                  try {
                                    clientReceived.add(buf.toString(UTF_8));
                                  } finally {
                                    buf.release();
                                  }
                                }
                              });
                    }
                  })
              .connect(address)
              .sync()
              .channel();

      // 1. Server automatically sends <greeting> after mTLS completes and holds IP slot.
      assertThat(clientReceived.poll(5, TimeUnit.SECONDS)).isEqualTo("<epp><greeting/></epp>");
      assertThat(realLimiter.acquireIp("203.0.113.5")).isFalse();

      // 2. Client sends <login> and server holds registrar slot.
      clientChannel
          .writeAndFlush(
              Unpooled.wrappedBuffer(
                  "<epp><command><login><clID>TheRegistrar</clID></login></command></epp>"
                      .getBytes(UTF_8)))
          .sync();
      assertThat(clientReceived.poll(5, TimeUnit.SECONDS))
          .isEqualTo("<epp><response>login-ok</response></epp>");
      assertThat(realLimiter.acquireRegistrar("TheRegistrar")).isFalse();

      // 3. Client sends <logout> and server closes connection and releases both slots in
      // channelInactive before forwarding the event to the tail latch handler.
      clientChannel
          .writeAndFlush(
              Unpooled.wrappedBuffer("<epp><command><logout/></command></epp>".getBytes(UTF_8)))
          .sync();
      assertThat(clientReceived.poll(5, TimeUnit.SECONDS))
          .isEqualTo("<epp><response>logout-ok</response></epp>");
      assertThat(serverClosed.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(serverInactive.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(localHandler.getState()).isEqualTo(ConnectionState.CLOSING);
      assertThat(realLimiter.acquireIp("203.0.113.5")).isTrue();
      assertThat(realLimiter.acquireRegistrar("TheRegistrar")).isTrue();
    } finally {
      if (serverChannel != null) {
        serverChannel.close().syncUninterruptibly();
      }
      eventGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
    }
  }
}
