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

package google.registry.rde;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.Cursor.CursorType.RDE_REPORT;
import static google.registry.model.common.Cursor.CursorType.RDE_UPLOAD;
import static google.registry.model.rde.RdeMode.FULL;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.io.ByteSource;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.rde.RdeRevision;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.HttpException.InternalServerErrorException;
import google.registry.request.HttpException.NoContentException;
import google.registry.testing.BouncyCastleProviderExtension;
import google.registry.testing.FakeKeyringModule;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeUrlConnectionService;
import google.registry.util.UrlConnectionException;
import google.registry.xjc.XjcXmlTransformer;
import google.registry.xjc.rdereport.XjcRdeReportReport;
import google.registry.xml.XmlException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link RdeReportAction}. */
@SuppressWarnings("DirectInvocationOnMock") // FakeUrlConnectionService mocks the getURL() method.
public class RdeReportActionTest {

  private static final ByteSource REPORT_XML = RdeTestData.loadBytes("report.xml");
  private static final ByteSource IIRDEA_BAD_XML = RdeTestData.loadBytes("iirdea_bad.xml");
  private static final ByteSource IIRDEA_GOOD_XML = RdeTestData.loadBytes("iirdea_good.xml");

  @RegisterExtension
  public final BouncyCastleProviderExtension bouncy = new BouncyCastleProviderExtension();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final FakeResponse response = new FakeResponse();
  private final EscrowTaskRunner runner = mock(EscrowTaskRunner.class);
  private final HttpURLConnection httpUrlConnection = mock(HttpURLConnection.class);
  private final FakeUrlConnectionService urlConnectionService =
      new FakeUrlConnectionService(httpUrlConnection);
  private final ByteArrayOutputStream connectionOutputStream = new ByteArrayOutputStream();

  private final PGPPublicKey encryptKey =
      new FakeKeyringModule().get().getRdeStagingEncryptionKey();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final BlobId reportFile =
      BlobId.of("tub", "job-name/test_2006-06-06_full_S1_R0-report.xml.ghostryde");
  private Tld registry;

  private RdeReportAction createAction() {
    RdeReporter reporter = new RdeReporter();
    reporter.reportUrlPrefix = "https://rde-report.example";
    reporter.urlConnectionService = urlConnectionService;
    reporter.password = "foo";
    RdeReportAction action = new RdeReportAction();
    action.gcsUtils = gcsUtils;
    action.response = response;
    action.bucket = "tub";
    action.tld = "test";
    action.interval = standardDays(1);
    action.reporter = reporter;
    action.timeout = standardSeconds(30);
    action.stagingDecryptionKey = new FakeKeyringModule().get().getRdeStagingDecryptionKey();
    action.runner = runner;
    action.prefix = Optional.of("job-name/");
    return action;
  }

  @BeforeEach
  void beforeEach() throws Exception {
    registry = createTld("test");
    persistResource(Cursor.createScoped(RDE_REPORT, DateTime.parse("2006-06-06TZ"), registry));
    persistResource(Cursor.createScoped(RDE_UPLOAD, DateTime.parse("2006-06-07TZ"), registry));
    gcsUtils.createFromBytes(reportFile, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm().transact(() -> RdeRevision.saveRevision("test", DateTime.parse("2006-06-06TZ"), FULL, 0));
    when(httpUrlConnection.getOutputStream()).thenReturn(connectionOutputStream);
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_OK);
    when(httpUrlConnection.getInputStream()).thenReturn(IIRDEA_GOOD_XML.openBufferedStream());
  }

  @Test
  void testRun() {
    createTld("lol");
    RdeReportAction action = createAction();
    action.tld = "lol";
    action.run();
    verify(runner)
        .lockRunAndRollForward(
            action, Tld.get("lol"), standardSeconds(30), RDE_REPORT, standardDays(1));
    verifyNoMoreInteractions(runner);
  }

  @Test
  void testRunWithLock() throws Exception {
    createAction().runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");

    // Verify the HTTP request was correct.
    verify(httpUrlConnection).setRequestMethod("PUT");
    assertThat(httpUrlConnection.getURL().getProtocol()).isEqualTo("https");
    assertThat(httpUrlConnection.getURL().getPath()).endsWith("/test/20101017001");
    verify(httpUrlConnection).setRequestProperty("Content-Type", "text/xml; charset=utf-8");
    verify(httpUrlConnection).setRequestProperty("Authorization", "Basic dGVzdF9yeTpmb28=");

    // Verify the payload XML was the same as what's in testdata/report.xml.
    XjcRdeReportReport report = parseReport(connectionOutputStream.toByteArray());
    assertThat(report.getId()).isEqualTo("20101017001");
    assertThat(report.getCrDate()).isEqualTo(DateTime.parse("2010-10-17T00:15:00.0Z"));
    assertThat(report.getWatermark()).isEqualTo(DateTime.parse("2010-10-17T00:00:00Z"));
  }

  @Test
  void testRunWithLock_withPrefix() throws Exception {
    RdeReportAction action = createAction();
    action.runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");

    // Verify the HTTP request was correct.
    verify(httpUrlConnection).setRequestMethod("PUT");
    assertThat(httpUrlConnection.getURL().getProtocol()).isEqualTo("https");
    assertThat(httpUrlConnection.getURL().getPath()).endsWith("/test/20101017001");
    verify(httpUrlConnection).setRequestProperty("Content-Type", "text/xml; charset=utf-8");
    verify(httpUrlConnection).setRequestProperty("Authorization", "Basic dGVzdF9yeTpmb28=");

    // Verify the payload XML was the same as what's in testdata/report.xml.
    XjcRdeReportReport report = parseReport(connectionOutputStream.toByteArray());
    assertThat(report.getId()).isEqualTo("20101017001");
    assertThat(report.getCrDate()).isEqualTo(DateTime.parse("2010-10-17T00:15:00.0Z"));
    assertThat(report.getWatermark()).isEqualTo(DateTime.parse("2010-10-17T00:00:00Z"));
  }

  @Test
  void testRunWithLock_withoutPrefix_noPrefixFound() throws Exception {
    RdeReportAction action = createAction();
    action.prefix = Optional.empty();
    assertThrows(NoContentException.class, () -> action.runWithLock(loadRdeReportCursor()));
  }

  @Test
  void testRunWithLock_withoutPrefix() throws Exception {
    RdeReportAction action = createAction();
    action.prefix = Optional.empty();
    gcsUtils.delete(reportFile);
    BlobId otherReportFile1 =
        BlobId.of(
            "tub", "rde-2006-06-06t00-00-00z-1/test_2006-06-06_full_S1_R0-report.xml.ghostryde");
    BlobId otherReportFile2 =
        BlobId.of(
            "tub", "rde-2006-06-06t00-00-00z-2/test_2006-06-06_full_S1_R1-report.xml.ghostryde");
    // This file's content is not correct, if it is read, the action should throw.
    gcsUtils.createFromBytes(
        otherReportFile1,
        Ghostryde.encode(
            ByteSource.wrap("BAD DATA".getBytes(StandardCharsets.UTF_8)).read(), encryptKey));
    gcsUtils.createFromBytes(otherReportFile2, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm().transact(() -> RdeRevision.saveRevision("test", DateTime.parse("2006-06-06TZ"), FULL, 1));
    action.runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("OK test 2006-06-06T00:00:00.000Z\n");

    // Verify the HTTP request was correct.
    verify(httpUrlConnection).setRequestMethod("PUT");
    assertThat(httpUrlConnection.getURL().getProtocol()).isEqualTo("https");
    assertThat(httpUrlConnection.getURL().getPath()).endsWith("/test/20101017001");
    verify(httpUrlConnection).setRequestProperty("Content-Type", "text/xml; charset=utf-8");
    verify(httpUrlConnection).setRequestProperty("Authorization", "Basic dGVzdF9yeTpmb28=");

    // Verify the payload XML was the same as what's in testdata/report.xml.
    XjcRdeReportReport report = parseReport(connectionOutputStream.toByteArray());
    assertThat(report.getId()).isEqualTo("20101017001");
    assertThat(report.getCrDate()).isEqualTo(DateTime.parse("2010-10-17T00:15:00.0Z"));
    assertThat(report.getWatermark()).isEqualTo(DateTime.parse("2010-10-17T00:00:00Z"));
  }

  @Test
  void testRunWithLock_regeneratedReport() throws Exception {
    gcsUtils.delete(reportFile);
    BlobId newReport = BlobId.of("tub", "job-name/test_2006-06-06_full_S1_R1-report.xml.ghostryde");
    PGPPublicKey encryptKey = new FakeKeyringModule().get().getRdeStagingEncryptionKey();
    gcsUtils.createFromBytes(newReport, Ghostryde.encode(REPORT_XML.read(), encryptKey));
    tm().transact(() -> RdeRevision.saveRevision("test", DateTime.parse("2006-06-06TZ"), FULL, 1));
    createAction().runWithLock(loadRdeReportCursor());
    assertThat(response.getStatus()).isEqualTo(200);
  }

  void testRunWithLock_nonexistentCursor_throws204() {
    tm().transact(() -> tm().delete(Cursor.createScopedVKey(RDE_UPLOAD, Tld.get("test"))));
    NoContentException thrown =
        assertThrows(
            NoContentException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeUploadAction for TLD test to send 2006-06-06T00:00:00.000Z report; last"
                + " upload completion was at 1970-01-01T00:00:00.000Z");
  }

  @Test
  void testRunWithLock_uploadNotFinished_throws204() {
    persistResource(
        Cursor.createScoped(RDE_UPLOAD, DateTime.parse("2006-06-06TZ"), Tld.get("test")));
    NoContentException thrown =
        assertThrows(
            NoContentException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Waiting on RdeUploadAction for TLD test to send 2006-06-06T00:00:00.000Z report; "
                + "last upload completion was at 2006-06-06T00:00:00.000Z");
  }

  @Test
  void testRunWithLock_badRequest_throws500WithErrorInfo() throws Exception {
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_BAD_REQUEST);
    when(httpUrlConnection.getErrorStream()).thenReturn(IIRDEA_BAD_XML.openBufferedStream());
    InternalServerErrorException thrown =
        assertThrows(
            InternalServerErrorException.class,
            () -> createAction().runWithLock(loadRdeReportCursor()));
    assertThat(thrown).hasMessageThat().contains("The structure of the report is invalid.");
  }

  @Test
  void testRunWithLock_notAuthorized() throws Exception {
    when(httpUrlConnection.getResponseCode()).thenReturn(SC_UNAUTHORIZED);
    UrlConnectionException thrown =
        assertThrows(
            UrlConnectionException.class, () -> createAction().runWithLock(loadRdeReportCursor()));
    verify(httpUrlConnection, times(0)).getInputStream();
    assertThat(thrown).hasMessageThat().contains("PUT failed");
  }

  private DateTime loadRdeReportCursor() {
    return loadByKey(Cursor.createScopedVKey(RDE_REPORT, registry)).getCursorTime();
  }

  private static XjcRdeReportReport parseReport(byte[] data) {
    try {
      return XjcXmlTransformer.unmarshal(XjcRdeReportReport.class, new ByteArrayInputStream(data));
    } catch (XmlException e) {
      throw new RuntimeException(e);
    }
  }
}
