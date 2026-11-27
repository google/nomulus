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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.eppoutput.Result.Code.AUTHENTICATION_ERROR;
import static google.registry.model.eppoutput.Result.Code.AUTHENTICATION_ERROR_CLOSING_CONNECTION;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_AND_CLOSE;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.testing.FakeResponse;
import google.registry.util.ProxyHttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link EppRequestHandler}. */
class EppRequestHandlerTest {

  private final EppController eppController = mock(EppController.class);
  private final SessionMetadata sessionMetadata = mock(SessionMetadata.class);
  private final TransportCredentials credentials = new PasswordOnlyTransportCredentials();
  private final FakeResponse response = new FakeResponse();
  private final EppRequestHandler handler = new EppRequestHandler();

  @BeforeEach
  void beforeEach() {
    handler.eppController = eppController;
    handler.response = response;
  }

  private static EppOutput createResponseOutput(Result.Code code) {
    return EppOutput.create(
        new EppResponse.Builder()
            .setResult(Result.create(code))
            .setTrid(Trid.create("client-trid", "server-trid"))
            .build());
  }

  @Test
  void testExecuteEpp_successAndClose1500_setsCloseHeader() {
    when(sessionMetadata.getRegistrarId()).thenThrow(new IllegalStateException("invalidated"));
    when(eppController.handleEppCommand(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(createResponseOutput(SUCCESS_AND_CLOSE));

    handler.executeEpp(
        sessionMetadata, credentials, EppRequestSource.TLS, false, false, "<epp/>".getBytes(UTF_8));

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getHeaders()).containsEntry(ProxyHttpHeaders.EPP_SESSION, "close");
    assertThat(response.getHeaders()).doesNotContainKey(ProxyHttpHeaders.LOGGED_IN_REGISTRAR);
    verify(sessionMetadata).save(response);
  }

  @Test
  void testExecuteEpp_authErrorClosingConnection2501_setsCloseHeader() {
    when(sessionMetadata.getRegistrarId()).thenReturn(null);
    when(eppController.handleEppCommand(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(createResponseOutput(AUTHENTICATION_ERROR_CLOSING_CONNECTION));

    handler.executeEpp(
        sessionMetadata, credentials, EppRequestSource.TLS, false, false, "<epp/>".getBytes(UTF_8));

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getHeaders()).containsEntry(ProxyHttpHeaders.EPP_SESSION, "close");
    assertThat(response.getHeaders()).doesNotContainKey(ProxyHttpHeaders.LOGGED_IN_REGISTRAR);
    verify(sessionMetadata).save(response);
  }

  @Test
  void testExecuteEpp_regularAuthError2200_doesNotSetCloseHeader() {
    when(sessionMetadata.getRegistrarId()).thenReturn(null);
    when(eppController.handleEppCommand(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(createResponseOutput(AUTHENTICATION_ERROR));

    handler.executeEpp(
        sessionMetadata, credentials, EppRequestSource.TLS, false, false, "<epp/>".getBytes(UTF_8));

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getHeaders()).doesNotContainKey(ProxyHttpHeaders.EPP_SESSION);
    verify(sessionMetadata).save(response);
  }

  @Test
  void testExecuteEpp_success1000_setsLoggedInRegistrarAndDoesNotClose() {
    when(sessionMetadata.getRegistrarId()).thenReturn("TheRegistrar");
    when(eppController.handleEppCommand(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenReturn(createResponseOutput(SUCCESS));

    handler.executeEpp(
        sessionMetadata, credentials, EppRequestSource.TLS, false, false, "<epp/>".getBytes(UTF_8));

    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getHeaders())
        .containsEntry(ProxyHttpHeaders.LOGGED_IN_REGISTRAR, "TheRegistrar");
    assertThat(response.getHeaders()).doesNotContainKey(ProxyHttpHeaders.EPP_SESSION);
    verify(sessionMetadata).save(response);
  }

  @Test
  void testExecuteEpp_controllerException_returns400AndSavesSession() {
    when(eppController.handleEppCommand(any(), any(), any(), anyBoolean(), anyBoolean(), any()))
        .thenThrow(new IllegalStateException("boom"));

    handler.executeEpp(
        sessionMetadata, credentials, EppRequestSource.TLS, false, false, "<epp/>".getBytes(UTF_8));

    assertThat(response.getStatus()).isEqualTo(SC_BAD_REQUEST);
    verify(sessionMetadata).save(response);
  }
}
