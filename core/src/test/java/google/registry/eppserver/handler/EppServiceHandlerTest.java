// Copyright 2024 The Nomulus Authors. All Rights Reserved.
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.eppserver.metric.FrontendMetrics;
import google.registry.eppserver.quota.QuotaManager;
import google.registry.eppserver.quota.QuotaManager.QuotaRequest;
import google.registry.eppserver.quota.QuotaManager.QuotaResponse;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Attribute;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import io.netty.util.concurrent.Promise;
import java.security.cert.X509Certificate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EppServiceHandlerTest {

  @Mock private FrontendMetrics metrics;
  @Mock private QuotaManager connectionQuotaManager;
  @Mock private QuotaManager commandQuotaManager;
  @Mock private QuotaManager ipQuotaManager;
  @Mock private Supplier<String> idTokenSupplier;
  @Mock private ChannelHandlerContext ctx;
  @Mock private Channel channel;

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
            connectionQuotaManager,
            commandQuotaManager,
            ipQuotaManager,
            idTokenSupplier,
            "test-project");

    when(ctx.channel()).thenReturn(channel);
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

    // Reject IP quota
    when(ipQuotaManager.acquireQuota(any(QuotaRequest.class))).thenReturn(new QuotaResponse(false));

    certPromise.setSuccess(certificate);

    verify(metrics).registerQuotaRejection(eq("epp_connection_ip"), eq("192.168.1.1"));
    verify(ctx).close();
  }

  @Test
  void testChannelActive_connectionQuotaRejected() throws Exception {
    certPromise = new DefaultPromise<>(ImmediateEventExecutor.INSTANCE);
    when(channel.attr(CLIENT_CERTIFICATE_PROMISE_KEY)).thenReturn(certPromiseAttr);
    when(certPromiseAttr.get()).thenReturn(certPromise);

    handler.channelActive(ctx);

    when(channel.attr(REMOTE_ADDRESS_KEY)).thenReturn(remoteAddressAttr);
    when(remoteAddressAttr.get()).thenReturn("192.168.1.1");
    when(channel.attr(EppServiceHandler.CLIENT_CERTIFICATE_HASH_KEY)).thenReturn(certHashAttr);
    when(certificate.getEncoded()).thenReturn(new byte[] {1, 2, 3});

    // Accept IP quota but reject connection quota
    when(ipQuotaManager.acquireQuota(any(QuotaRequest.class))).thenReturn(new QuotaResponse(true));
    when(connectionQuotaManager.acquireQuota(any(QuotaRequest.class)))
        .thenReturn(new QuotaResponse(false));

    certPromise.setSuccess(certificate);

    verify(metrics).registerQuotaRejection(eq("epp_connection"), any(String.class));
    verify(ctx).close();
  }
}
