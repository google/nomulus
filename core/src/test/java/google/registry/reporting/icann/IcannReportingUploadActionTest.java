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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.GcsTestingUtils.writeGcsFile;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import com.google.common.testing.TestLogHandler;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.mail.internet.InternetAddress;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link google.registry.reporting.icann.IcannReportingUploadAction} */
@RunWith(JUnit4.class)
public class IcannReportingUploadActionTest {

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  private static final byte[] PAYLOAD_SUCCESS = "test,csv\n13,37".getBytes(UTF_8);
  private static final byte[] PAYLOAD_FAIL = "ahah,csv\n12,34".getBytes(UTF_8);
  private static final byte[] MANIFEST_PAYLOAD =
      "tld-transactions-200606.csv\ntld-activity-200606.csv\nfoo-transactions-200606.csv\nfoo-activity-200606.csv\n"
          .getBytes(UTF_8);
  private final IcannHttpReporter mockReporter = mock(IcannHttpReporter.class);
  private final SendEmailService emailService = mock(SendEmailService.class);
  private final FakeResponse response = new FakeResponse();
  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger loggerToIntercept =
      Logger.getLogger(IcannReportingUploadAction.class.getCanonicalName());
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private IcannReportingUploadAction createAction() throws Exception {
    IcannReportingUploadAction action = new IcannReportingUploadAction();
    action.icannReporter = mockReporter;
    action.gcsUtils = new GcsUtils(gcsService, 1024);
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    action.subdir = "icann/monthly/2006-06";
    action.reportingBucket = "basin";
    action.emailService = emailService;
    action.sender = new InternetAddress("sender@example.com");
    action.recipient = new InternetAddress("recipient@example.com");
    action.response = response;
    action.clock = clock;
    action.lockHandler = new FakeLockHandler(true);
    return action;
  }

  @Before
  public void before() throws Exception {
    createTlds("tld", "foo");
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "tld-transactions-200606.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "tld-activity-200606.csv"),
        PAYLOAD_FAIL);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "foo-transactions-200606.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "foo-activity-200606.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "MANIFEST.txt"),
        MANIFEST_PAYLOAD);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_FAIL, "tld-activity-200606.csv")).thenReturn(false);
    when(mockReporter.send(PAYLOAD_SUCCESS, "foo-activity-200606.csv")).thenReturn(true);
    when(mockReporter.send(MANIFEST_PAYLOAD, "MANIFEST.txt")).thenReturn(true);
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-06-06TZ"), Registry.get("tld")));
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD_TX, DateTime.parse("2006-06-06TZ"), Registry.get("tld")));
    persistResource(
        Cursor.createGlobal(CursorType.ICANN_UPLOAD_MANIFEST, DateTime.parse("2006-07-06TZ")));
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-06-06TZ"), Registry.get("foo")));
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD_TX, DateTime.parse("2006-06-06TZ"), Registry.get("foo")));
    loggerToIntercept.addHandler(logHandler);
  }

  @Test
  public void testSuccess() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");

    verifyNoMoreInteractions(mockReporter);
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                "Report Filename - Upload status:\n"
                    + "foo-activity-200606.csv - SUCCESS\n"
                    + "tld-activity-200606.csv - FAILURE\n"
                    + "foo-transactions-200606.csv - SUCCESS\n"
                    + "tld-transactions-200606.csv - SUCCESS",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testSuccess_advancesCursor() throws Exception {
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2006-06", "tld-activity-200606.csv"),
        PAYLOAD_SUCCESS);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-activity-200606.csv")).thenReturn(true);
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy()
            .load()
            .key(Cursor.createKey(CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
  }

  @Test
  public void testSuccess_noUploadsNeeded() throws Exception {
    clock.setTo(DateTime.parse("2006-5-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    verifyNoMoreInteractions(mockReporter);
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 0/0 succeeded",
                "Report Filename - Upload status:\n",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testSuccess_uploadManifest() throws Exception {
    persistResource(
        Cursor.createGlobal(CursorType.ICANN_UPLOAD_MANIFEST, DateTime.parse("2006-06-06TZ")));
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy().load().key(Cursor.createGlobalKey(CursorType.ICANN_UPLOAD_MANIFEST)).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-activity-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-transactions-200606.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-transactions-200606.csv");
    verify(mockReporter).send(MANIFEST_PAYLOAD, "MANIFEST.txt");
    verifyNoMoreInteractions(mockReporter);
  }

  @Test
  public void testSuccess_withRetry() throws Exception {
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
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                "Report Filename - Upload status:\n"
                    + "foo-activity-200606.csv - SUCCESS\n"
                    + "tld-activity-200606.csv - FAILURE\n"
                    + "foo-transactions-200606.csv - SUCCESS\n"
                    + "tld-transactions-200606.csv - SUCCESS",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testFailure_quicklySkipsOverNonRetryableUploadException() throws Exception {
    runTest_nonRetryableException(
        new IOException(
            "<msg>A report for that month already exists, the cut-off date already"
                + " passed.</msg>"));
  }

  @Test
  public void testFailure_quicklySkipsOverIpWhitelistException() throws Exception {
    runTest_nonRetryableException(
        new IOException("Your IP address 25.147.130.158 is not allowed to connect"));
  }

  @Test
  public void testFailure_cursorIsNotAdvancedForward() throws Exception {
    runTest_nonRetryableException(
        new IOException("Your IP address 25.147.130.158 is not allowed to connect"));
    ofy().clearSessionCache();
    Cursor cursor =
        ofy()
            .load()
            .key(Cursor.createKey(CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("tld")))
            .now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-06-06TZ"));
  }

  @Test
  public void testNotRunIfCursorDateIsAfterToday() throws Exception {
    clock.setTo(DateTime.parse("2006-05-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy()
            .load()
            .key(Cursor.createKey(CursorType.ICANN_UPLOAD_ACTIVITY, Registry.get("foo")))
            .now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-06-06TZ"));
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
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 3/4 succeeded",
                "Report Filename - Upload status:\n"
                    + "foo-activity-200606.csv - SUCCESS\n"
                    + "tld-activity-200606.csv - FAILURE\n"
                    + "foo-transactions-200606.csv - SUCCESS\n"
                    + "tld-transactions-200606.csv - SUCCESS",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testFail_fileNotFound() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.subdir = "somewhere/else";
    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.SEVERE,
            "Could not upload ICANN_UPLOAD_ACTIVITY report for foo because file"
                + " foo-activity-200606.csv did not exist.");
  }

  @Test
  public void testWarning_fileNotStagedYet() throws Exception {
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD_ACTIVITY, DateTime.parse("2006-07-01TZ"), Registry.get("foo")));
    clock.setTo(DateTime.parse("2006-07-01T00:30:00Z"));
    IcannReportingUploadAction action = createAction();
    action.subdir = "icann/monthly/2006-07";
    action.run();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(
            Level.INFO,
            "Could not upload ICANN_UPLOAD_ACTIVITY report for foo because file"
                + " foo-activity-200607.csv did not exist. This report may not have been staged"
                + " yet.");
  }

  @Test
  public void testFailure_lockIsntAvailable() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.lockHandler = new FakeLockHandler(false);
    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, () -> action.run());
    assertThat(thrown)
        .hasMessageThat()
        .contains("Lock for IcannReportingUploadAction already in use.");
  }
}

