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

package google.registry.bsa.api;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_GATEWAY;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_REQUEST_TIMEOUT;
import static jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.testing.TestLogHandler;
import google.registry.request.UrlConnectionService;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link BsaReportSender} and HTTP retry classification. */
@ExtendWith(MockitoExtension.class)
class BsaReportSenderTest {

  @Mock private UrlConnectionService urlConnectionService;
  @Mock private BsaCredential credential;
  @Mock private HttpsURLConnection connection;

  private BsaReportSender sender;

  @BeforeEach
  void setup() {
    sender =
        new BsaReportSender(
            urlConnectionService,
            credential,
            "https://bsa.example/orders",
            "https://bsa.example/add",
            "https://bsa.example/remove",
            new Retrier(new FakeSleeper(new FakeClock()), 3));
  }

  private void setupConnection() throws Exception {
    when(urlConnectionService.createConnection(any(URL.class))).thenReturn(connection);
    when(credential.getAuthToken()).thenReturn("token");
    when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
  }

  @ParameterizedTest
  @ValueSource(ints = {SC_OK, SC_ACCEPTED})
  void sendOrderStatusReport_200And202_succeed(int statusCode) throws Exception {
    setupConnection();
    when(connection.getResponseCode()).thenReturn(statusCode);
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("{\"ok\":true}".getBytes(UTF_8)));

    sender.sendOrderStatusReport("[]");

    verify(urlConnectionService, times(1))
        .createConnection(URI.create("https://bsa.example/orders").toURL());
    verify(connection, times(1)).disconnect();
  }

  @Test
  void sendOrderStatusReport_responseWithLineBreaks_sanitizedInLog() throws Exception {
    TestLogHandler logHandler = new TestLogHandler();
    Logger senderLogger = Logger.getLogger(BsaReportSender.class.getCanonicalName());
    senderLogger.addHandler(logHandler);
    try {
      setupConnection();
      when(connection.getResponseCode()).thenReturn(SC_OK);
      when(connection.getInputStream())
          .thenReturn(new ByteArrayInputStream("ok\r\nFAKE_LOG_ENTRY\nnext\rline".getBytes(UTF_8)));

      sender.sendOrderStatusReport("[]");

      assertAboutLogs()
          .that(logHandler)
          .hasLogAtLevelWithMessage(
              Level.INFO, "Received response: [ok__FAKE_LOG_ENTRY_next_line]");
    } finally {
      senderLogger.removeHandler(logHandler);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {SC_BAD_REQUEST, SC_UNAUTHORIZED, SC_FORBIDDEN, SC_NOT_FOUND, 422})
  void sendOrderStatusReport_permanent4xx_notRetried(int statusCode) throws Exception {
    setupConnection();
    when(connection.getResponseCode()).thenReturn(statusCode);
    when(connection.getResponseMessage()).thenReturn("Client Error");
    when(connection.getErrorStream())
        .thenReturn(new ByteArrayInputStream("invalid payload".getBytes(UTF_8)));

    BsaException thrown =
        assertThrows(BsaException.class, () -> sender.sendOrderStatusReport("[]"));

    assertThat(thrown.isRetriable()).isFalse();
    assertThat(thrown).hasMessageThat().contains("Status code: [" + statusCode + "]");
    verify(urlConnectionService, times(1)).createConnection(any(URL.class));
    verify(connection, times(1)).disconnect();
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        SC_REQUEST_TIMEOUT,
        429,
        SC_INTERNAL_SERVER_ERROR,
        SC_BAD_GATEWAY,
        SC_SERVICE_UNAVAILABLE
      })
  void addUnblockableDomainsUpdates_transientStatus_retriedAndRecovers(int statusCode)
      throws Exception {
    setupConnection();
    when(connection.getResponseCode()).thenReturn(statusCode, SC_OK);
    when(connection.getResponseMessage()).thenReturn("Transient Error");
    when(connection.getErrorStream())
        .thenReturn(new ByteArrayInputStream("try again".getBytes(UTF_8)));
    when(connection.getInputStream())
        .thenReturn(new ByteArrayInputStream("accepted".getBytes(UTF_8)));

    sender.addUnblockableDomainsUpdates("{}");

    verify(urlConnectionService, times(2))
        .createConnection(URI.create("https://bsa.example/add").toURL());
    verify(connection, times(2)).disconnect();
  }

  @Test
  void removeUnblockableDomainsUpdates_persistent503_exhaustsRetries() throws Exception {
    setupConnection();
    when(connection.getResponseCode()).thenReturn(SC_SERVICE_UNAVAILABLE);
    when(connection.getResponseMessage()).thenReturn("Service Unavailable");
    when(connection.getErrorStream()).thenReturn(null);

    BsaException thrown =
        assertThrows(BsaException.class, () -> sender.removeUnblockableDomainsUpdates("[]"));

    assertThat(thrown.isRetriable()).isTrue();
    verify(urlConnectionService, times(3))
        .createConnection(URI.create("https://bsa.example/remove").toURL());
    verify(connection, times(3)).disconnect();
  }

  @Test
  void sendOrderStatusReport_ioException_retried() throws Exception {
    setupConnection();
    when(connection.getResponseCode()).thenThrow(new IOException("reset"));

    BsaException thrown =
        assertThrows(BsaException.class, () -> sender.sendOrderStatusReport("[]"));

    assertThat(thrown.isRetriable()).isTrue();
    verify(urlConnectionService, times(3)).createConnection(any(URL.class));
  }

  @Test
  void sendOrderStatusReport_securityException_notRetried() throws Exception {
    when(urlConnectionService.createConnection(any(URL.class)))
        .thenThrow(new GeneralSecurityException("tls"));

    BsaException thrown =
        assertThrows(BsaException.class, () -> sender.sendOrderStatusReport("[]"));

    assertThat(thrown.isRetriable()).isFalse();
    verify(urlConnectionService, times(1)).createConnection(any(URL.class));
  }
}
