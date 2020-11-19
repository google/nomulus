// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.net.MediaType.CSV_UTF_8;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.Base64;
import com.google.api.client.util.StringUtils;
import com.google.common.io.ByteSource;
import google.registry.testing.AppEngineExtension;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link IcannHttpReporter}. */
class IcannHttpReporterTest {

  private static final ByteSource IIRDEA_GOOD_XML = ReportingTestData.loadBytes("iirdea_good.xml");
  private static final ByteSource IIRDEA_BAD_XML = ReportingTestData.loadBytes("iirdea_bad.xml");
  private static final byte[] FAKE_PAYLOAD = "test,csv\n1,2".getBytes(UTF_8);

  private MockLowLevelHttpRequest mockRequest;

  @RegisterExtension
  AppEngineExtension appEngineRule =
      new AppEngineExtension.Builder().withDatastoreAndCloudSql().build();

  private MockHttpTransport createMockTransport(
      int statusCode, final ByteSource iirdeaResponse) {
    return new MockHttpTransport() {
      @Override
      public LowLevelHttpRequest buildRequest(String method, String url) {
        mockRequest =
            new MockLowLevelHttpRequest() {
              @Override
              public LowLevelHttpResponse execute() throws IOException {
                MockLowLevelHttpResponse response = new MockLowLevelHttpResponse();
                response.setStatusCode(statusCode);
                response.setContentType(PLAIN_TEXT_UTF_8.toString());
                response.setContent(iirdeaResponse.read());
                return response;
              }
            };
        mockRequest.setUrl(url);
        return mockRequest;
      }
    };
  }

  private MockHttpTransport createMockTransport(final ByteSource iirdeaResponse) {
    return createMockTransport(HttpStatusCodes.STATUS_CODE_OK, iirdeaResponse);
  }

  @BeforeEach
  void beforeEach() {
    createTld("test");
    createTld("xn--abc123");
  }

  private IcannHttpReporter createReporter() {
    IcannHttpReporter reporter = new IcannHttpReporter();
    reporter.httpTransport = createMockTransport(IIRDEA_GOOD_XML);
    reporter.password = "fakePass";
    reporter.icannTransactionsUrl = "https://fake-transactions.url";
    reporter.icannActivityUrl = "https://fake-activity.url";
    return reporter;
  }

  @Test
  void testSuccess() throws Exception {
    IcannHttpReporter reporter = createReporter();
    reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv");

    assertThat(mockRequest.getUrl()).isEqualTo("https://fake-transactions.url/test/2017-06");
    Map<String, List<String>> headers = mockRequest.getHeaders();
    String userPass = "test_ry:fakePass";
    String expectedAuth =
        String.format("Basic %s", Base64.encodeBase64String(StringUtils.getBytesUtf8(userPass)));
    assertThat(headers.get("authorization")).containsExactly(expectedAuth);
    assertThat(headers.get("content-type")).containsExactly(CSV_UTF_8.toString());
  }

  @Test
  void testSuccess_internationalTld() throws Exception {
    IcannHttpReporter reporter = createReporter();
    reporter.send(FAKE_PAYLOAD, "xn--abc123-transactions-201706.csv");

    assertThat(mockRequest.getUrl()).isEqualTo("https://fake-transactions.url/xn--abc123/2017-06");
    Map<String, List<String>> headers = mockRequest.getHeaders();
    String userPass = "xn--abc123_ry:fakePass";
    String expectedAuth =
        String.format("Basic %s", Base64.encodeBase64String(StringUtils.getBytesUtf8(userPass)));
    assertThat(headers.get("authorization")).containsExactly(expectedAuth);
    assertThat(headers.get("content-type")).containsExactly(CSV_UTF_8.toString());
  }

  @Test
  void testFail_BadIirdeaResponse() throws Exception {
    IcannHttpReporter reporter = createReporter();
    reporter.httpTransport =
        createMockTransport(HttpStatusCodes.STATUS_CODE_BAD_REQUEST, IIRDEA_BAD_XML);
    assertThat(reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv")).isFalse();
  }

  @Test
  void testFail_transportException() throws Exception {
    IcannHttpReporter reporter = createReporter();
    reporter.httpTransport =
        createMockTransport(HttpStatusCodes.STATUS_CODE_FORBIDDEN, ByteSource.empty());
    assertThrows(
        HttpResponseException.class,
        () -> reporter.send(FAKE_PAYLOAD, "test-transactions-201706.csv"));
  }

  @Test
  void testFail_invalidFilename_nonSixDigitYearMonth() {
    IcannHttpReporter reporter = createReporter();
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
    IcannHttpReporter reporter = createReporter();
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
    IcannHttpReporter reporter = createReporter();
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
    IcannHttpReporter reporter = createReporter();
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> reporter.send(FAKE_PAYLOAD, "hello-activity-201706.csv"));
    assertThat(thrown).hasMessageThat().contains("TLD hello does not exist");
  }
}
