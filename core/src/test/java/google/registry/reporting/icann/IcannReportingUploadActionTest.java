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
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper;
import com.google.common.testing.TestLogHandler;
import google.registry.gcs.GcsUtils;
import google.registry.groups.GmailClient;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import jakarta.mail.internet.InternetAddress;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.icann.IcannReportingUploadAction} */
class IcannReportingUploadActionTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private static final byte[] PAYLOAD_SUCCESS = "test,csv\n13,37".getBytes(UTF_8);
  private static final byte[] PAYLOAD_FAIL = "ahah,csv\n12,34".getBytes(UTF_8);
  private final IcannHttpReporter mockReporter = mock(IcannHttpReporter.class);
  private final GmailClient gmailClient = mock(GmailClient.class);
  private final FakeResponse response = new FakeResponse();
  private final GcsUtils gcsUtils = new GcsUtils(LocalStorageHelper.getOptions());
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept =
      Logger.getLogger(IcannReportingUploadAction.class.getCanonicalName());
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private IcannReportingUploadAction createAction() throws Exception {
    IcannReportingUploadAction action = new IcannReportingUploadAction();
    action.icannReporter = mockReporter;
    action.gcsUtils = gcsUtils;
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    action.reportingBucket = "basin";
    action.gmailClient = gmailClient;
    action.recipient = new InternetAddress("recipient@example.com");
    action.response = response;
    action.clock = clock;
    action.lockHandler = new FakeLockHandler(true);
    return action;
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTlds("tld", "foo");
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2006-06/tld-transactions-200606.csv"), PAYLOAD_SUCCESS);
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2006-06/tld-activity-200606.csv"), PAYLOAD_FAIL);
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2006-06/foo-transactions-200606.csv"), PAYLOAD_SUCCESS);
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2006-06/foo-activity-200606.csv"), PAYLOAD_SUCCESS);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_FAIL, "tld-activity-200606.csv")).thenReturn(false);
    when(mockReporter.send(PAYLOAD_SUCCESS, "foo-activity-200606.csv")).thenReturn(true);
    clock.setTo(DateTime.parse("2006-07-05T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-07-01TZ"), Tld.get("tld")));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_TX, DateTime.parse("2006-07-01TZ"), Tld.get("tld")));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-07-01TZ"), Tld.get("foo")));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_TX, DateTime.parse("2006-07-01TZ"), Tld.get("foo")));
    loggerToIntercept.addHandler(logHandler);
  }

  @Test
  void testSuccess() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");

    verifyNoMoreInteractions(mockReporter);
    verify(gmailClient)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                """
                    Report Filename - Upload status:
                    foo-activity-200606.csv - SUCCESS
                    foo-transactions-200606.csv - SUCCESS
                    tld-activity-200606.csv - FAILURE
                    tld-transactions-200606.csv - SUCCESS""",
                new InternetAddress("recipient@example.com")));
  }

  @Test
  void testSuccess_january() throws Exception {
    clock.setTo(DateTime.parse("2006-01-22T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-01-01TZ"), Tld.get("tld")));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_TX, DateTime.parse("2006-01-01TZ"), Tld.get("tld")));
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2005-12/tld-transactions-200512.csv"), PAYLOAD_SUCCESS);
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2005-12/tld-activity-200512.csv"), PAYLOAD_SUCCESS);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-activity-200512.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-transactions-200512.csv")).thenReturn(true);

    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-activity-200512.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200512.csv");

    verifyNoMoreInteractions(mockReporter);
    verify(gmailClient)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 2/2 succeeded",
                """
                    Report Filename - Upload status:
                    tld-activity-200512.csv - SUCCESS
                    tld-transactions-200512.csv - SUCCESS""",
                new InternetAddress("recipient@example.com")));
  }

  @Test
  void testSuccess_advancesCursor() throws Exception {
    gcsUtils.createFromBytes(
        BlobId.of("basin", "icann/monthly/2006-06/tld-activity-200606.csv"), PAYLOAD_SUCCESS);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-activity-200606.csv")).thenReturn(true);
    IcannReportingUploadAction action = createAction();
    action.run();
    Cursor cursor =
        loadByKey(Cursor.createScopedVKey(CursorType.ICANN_UPLOAD_ACTIVITY, Tld.get("tld")));
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-08-02T10:00:00Z"));
  }

  @Test
  void testSuccess_noUploadsNeeded() throws Exception {
    clock.setTo(DateTime.parse("2006-5-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.run();
    verifyNoMoreInteractions(mockReporter);
    verifyNoMoreInteractions(gmailClient);
  }

  @Test
  void testSuccess_withRetry() throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv"))
        .thenThrow(new IOException("Expected exception."))
        .thenReturn(true);
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter, times(2)).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");
    verifyNoMoreInteractions(mockReporter);
    verify(gmailClient)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                """
                    Report Filename - Upload status:
                    foo-activity-200606.csv - SUCCESS
                    foo-transactions-200606.csv - SUCCESS
                    tld-activity-200606.csv - FAILURE
                    tld-transactions-200606.csv - SUCCESS""",
                new InternetAddress("recipient@example.com")));
  }

  @Test
  void testFailure_quicklySkipsOverNonRetryableUploadException() throws Exception {
    runTest_nonRetryableException(
        new IOException(
            "<msg>A report for that month already exists, the cut-off date already"
                + " passed.</msg>"));
  }

  @Test
  void testFailure_quicklySkipsOverIpAllowListException() throws Exception {
    runTest_nonRetryableException(
        new IOException("Your IP address 25.147.130.158 is not allowed to connect"));
  }

  @Test
  void testFailure_cursorIsNotAdvancedForward() throws Exception {
    runTest_nonRetryableException(
        new IOException("Your IP address 25.147.130.158 is not allowed to connect"));
    Cursor cursor =
        loadByKey(Cursor.createScopedVKey(CursorType.ICANN_UPLOAD_ACTIVITY, Tld.get("tld")));
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
  }

  @Test
  void testNotRunIfCursorDateIsAfterToday() throws Exception {
    clock.setTo(DateTime.parse("2006-05-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.run();
    Cursor cursor =
        loadByKey(Cursor.createScopedVKey(CursorType.ICANN_UPLOAD_ACTIVITY, Tld.get("foo")));
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
    verifyNoMoreInteractions(mockReporter);
  }

  private void runTest_nonRetryableException(Exception nonRetryableException) throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_FAIL, "tld-activity-200606.csv"))
        .thenThrow(nonRetryableException)
        .thenThrow(
            new AssertionError(
                "This should never be thrown because the previous exception isn't retryable"));
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter, times(1)).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");
    verifyNoMoreInteractions(mockReporter);
    verify(gmailClient)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                """
                    Report Filename - Upload status:
                    foo-activity-200606.csv - SUCCESS
                    foo-transactions-200606.csv - SUCCESS
                    tld-activity-200606.csv - FAILURE
                    tld-transactions-200606.csv - SUCCESS""",
                new InternetAddress("recipient@example.com")));
  }

  @Test
  void testFail_fileNotFound() throws Exception {
    clock.setTo(DateTime.parse("2006-01-22T00:30:00Z"));
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-01-01TZ"), Tld.get("tld")));
    IcannReportingUploadAction action = createAction();
    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE,
            "Could not upload ICANN_UPLOAD_ACTIVITY report for tld because file "
                + "tld-activity-200512.csv (object icann/monthly/2005-12/tld-activity-200512.csv in"
                + " bucket basin) did not exist.");
  }

  @Test
  void testWarning_fileNotStagedYet() throws Exception {
    persistResource(
        Cursor.createScoped(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-08-01TZ"), Tld.get("foo")));
    clock.setTo(DateTime.parse("2006-08-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Could not upload ICANN_UPLOAD_ACTIVITY report for foo because file "
                + "foo-activity-200607.csv (object icann/monthly/2006-07/foo-activity-200607.csv in"
                + " bucket basin) did not exist. This report may not have been staged yet.");
  }

  @Test
  void testFailure_lockIsntAvailable() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.lockHandler = new FakeLockHandler(false);
    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .contains("Lock for IcannReportingUploadAction already in use");
  }

  @Test
  void testSuccess_nullCursorsInitiatedToSecondOfNextMonth() throws Exception {
    createTlds("new");

    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");
    verifyNoMoreInteractions(mockReporter);

    verify(gmailClient)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                """
                    Report Filename - Upload status:
                    foo-activity-200606.csv - SUCCESS
                    foo-transactions-200606.csv - SUCCESS
                    tld-activity-200606.csv - FAILURE
                    tld-transactions-200606.csv - SUCCESS""",
                new InternetAddress("recipient@example.com")));

    Cursor newActivityCursor =
        loadByKey(Cursor.createScopedVKey(CursorType.ICANN_UPLOAD_ACTIVITY, Tld.get("new")));
    assertThat(newActivityCursor.getCursorTime()).isEqualTo(DateTime.parse("2006-08-02T10:00:00Z"));
    Cursor newTransactionCursor =
        loadByKey(Cursor.createScopedVKey(CursorType.ICANN_UPLOAD_TX, Tld.get("new")));
    assertThat(newTransactionCursor.getCursorTime())
        .isEqualTo(DateTime.parse("2006-08-02T10:00:00Z"));
  }
}
