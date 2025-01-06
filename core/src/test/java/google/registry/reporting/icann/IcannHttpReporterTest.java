// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.icann;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.util.StringUtils;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteSource;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeUrlConnectionService;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link IcannHttpReporter}. */
class IcannHttpReporterTest {

  private static final ByteSource IIRDEA_GOOD_XML = ReportingTestData.loadBytes("iirdea_good.xml");
  private static final ByteSource IIRDEA_BAD_XML = ReportingTestData.loadBytes("iirdea_bad.xml");
  private static final byte[] FAKE_PAYLOAD = "test,csv\n1,2".getBytes(UTF_8);
  private static final IcannHttpReporter reporter = new IcannHttpReporter();

  private final HttpURLConnection connection = mock(HttpURLConnection.class);
  private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
  private final FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(connection);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("test");
    createTld("xn--abc123");
    when(connection.getOutputStream()).thenReturn(outputStream);
    when(connection.getResponseCode()).thenReturn(SC_OK);
    when(connection.getInputStream()).thenReturn(IIRDEA_GOOD_XML.openBufferedStream());
    reporter.urlConnectionService = urlConnectionService;
    reporter.password = "fakePass";
    reporter.icannTransactionsUrl = "https://fake-transactions.url";
    reporter.icannActivityUrl = "https://fake-activity.url";
  }

  @Test
  void testSuccess() throws Exception {
    assertThat(reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv")).isTrue();

    assertThat(urlConnectionService.getConnectedUrls())
        .containsExactly(new URL("https://fake-transactions.url/test/2017-06"));
    String userPass = "test_ry:fakePass";
    String expectedAuth =
        String.format("Basic %s", BaseEncoding.base64().encode(StringUtils.getBytesUtf8(userPass)));
    verify(connection).setRequestProperty("Authorization", expectedAuth);
    verify(connection).setRequestProperty("Content-Type", "text/csv; charset=utf-8");
    assertThat(outputStream.toByteArray()).isEqualTo(FAKE_PAYLOAD);
  }

  @Test
  void testSuccess_internationalTld() throws Exception {
    assertThat(reporter.send(FAKE_PAYLOAD, "xn--abc123-transactions-201706.csv")).isTrue();

    assertThat(urlConnectionService.getConnectedUrls())
        .containsExactly(new URL("https://fake-transactions.url/xn--abc123/2017-06"));
    String userPass = "xn--abc123_ry:fakePass";
    String expectedAuth =
        String.format("Basic %s", BaseEncoding.base64().encode(StringUtils.getBytesUtf8(userPass)));
    verify(connection).setRequestProperty("Authorization", expectedAuth);
    verify(connection).setRequestProperty("Content-Type", "text/csv; charset=utf-8");
    assertThat(outputStream.toByteArray()).isEqualTo(FAKE_PAYLOAD);
  }

  @Test
  void testFail_BadIirdeaResponse() throws Exception {
    when(connection.getResponseCode()).thenReturn(SC_BAD_REQUEST);
    when(connection.getErrorStream()).thenReturn(IIRDEA_BAD_XML.openBufferedStream());
    assertThat(reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv")).isFalse();
    verify(connection).getErrorStream();
  }

  @Test
  void testFail_OtherBadHttpResponse() throws Exception {
    when(connection.getResponseCode()).thenReturn(SC_INTERNAL_SERVER_ERROR);
    assertThat(reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv")).isFalse();
    verify(connection, times(0)).getInputStream();
  }

  @Test
  void testFail_invalidFilename_nonSixDigitYearMonth() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> reporter.send(FAKE_PAYLOAD, "test-transactions-20176.csv"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Expected file format: tld-reportType-yyyyMM.csv, "
                + "got test-transactions-20176.csv instead");
  }

  @Test
  void testFail_invalidFilename_notActivityOrTransactions() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> reporter.send(FAKE_PAYLOAD, "test-invalid-201706.csv"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Expected file format: tld-reportType-yyyyMM.csv, got test-invalid-201706.csv instead");
  }

  @Test
  void testFail_invalidFilename_invalidTldName() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> reporter.send(FAKE_PAYLOAD, "n!-n-activity-201706.csv"));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Expected file format: tld-reportType-yyyyMM.csv, "
                + "got n!-n-activity-201706.csv instead");
  }

  @Test
  void testFail_invalidFilename_tldDoesntExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> reporter.send(FAKE_PAYLOAD, "hello-activity-201706.csv"));
    assertThat(thrown).hasMessageThat().contains("TLD hello does not exist");
  }
}
