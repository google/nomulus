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

import static google.registry.eppserver.handler.EppServiceHandler.REMOTE_ADDRESS_KEY;
import static google.registry.networking.handler.SslServerInitializer.CLIENT_CERTIFICATE_PROMISE_KEY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.eppserver.metric.FrontendMetrics;
import google.registry.eppserver.quota.LocalConnectionLimiter;
import google.registry.eppserver.quota.QuotaManager;
import google.registry.eppserver.quota.QuotaManager.QuotaRequest;
import google.registry.eppserver.quota.QuotaManager.QuotaResponse;
import google.registry.request.RequestHandler;
import google.registry.util.FakeHttpServletRequest;
import google.registry.util.FakeHttpServletResponse;
import google.registry.util.ProxyHttpHeaders;
import google.registry.util.X509Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
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
  @Mock private QuotaManager commandQuotaManager;
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

    when(ctx.channel()).thenReturn(channel);
    when(ctx.executor()).thenReturn(executor);
    lenient().when(channel.isActive()).thenReturn(true);

    doAnswer(
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

    lenient()
        .when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));
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

    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));
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

    verify(metrics).registerQuotaRejection(eq("epp_connection_ip"), eq("192.168.1.1"));
    verify(ctx).close();
  }

  @Test
  void testChannelRead0_registrarQuotaRejected() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));

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

    verify(metrics).registerQuotaRejection(eq("epp_connection_registrar"), eq("RegistrarA"));
    verify(ctx).close();
  }

  @Test
  void testChannelRead0_extractsClidAndForwardsRequest() throws Exception {
    setUpSuccessfulHandshake();

    when(idTokenSupplier.get()).thenReturn("fake_id_token");
    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));

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
    verify(commandQuotaManager, times(2)).acquireQuota(eq(new QuotaRequest(certHash)));
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
    verify(commandQuotaManager).acquireQuota(eq(new QuotaRequest("RegistrarA")));
  }

  @Test
  void testChannelRead0_commandQuotaRejected() throws Exception {
    setUpSuccessfulHandshake();

    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(false));

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
    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));

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
    when(commandQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(true));
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
    assertNotNull(timeoutTask);

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
    assertNotNull(capturedRequest);
    assertEquals("10.0.0.1", capturedRequest.getHeader(ProxyHttpHeaders.IP_ADDRESS));
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
}
