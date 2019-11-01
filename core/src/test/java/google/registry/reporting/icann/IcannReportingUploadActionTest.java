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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.appengine.tools.cloudstorage.GcsService;
import com.google.appengine.tools.cloudstorage.GcsServiceFactory;
import google.registry.gcs.GcsUtils;
import google.registry.model.common.Cursor;
import google.registry.model.common.Cursor.CursorType;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
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
      "tld-test-transactions-201706.csv\ntld-a-activity-201706.csv\nfoo-test-transactions-201706.csv\n"
          .getBytes(UTF_8);
  private final IcannHttpReporter mockReporter = mock(IcannHttpReporter.class);
  private final SendEmailService emailService = mock(SendEmailService.class);
  private final FakeResponse response = new FakeResponse();
  private final GcsService gcsService = GcsServiceFactory.createGcsService();
  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));

  private IcannReportingUploadAction createAction() throws Exception {
    IcannReportingUploadAction action = new IcannReportingUploadAction();
    action.icannReporter = mockReporter;
    action.gcsUtils = new GcsUtils(gcsService, 1024);
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    action.subdir = "icann/monthly/2017-06";
    action.reportingBucket = "basin";
    action.emailService = emailService;
    action.sender = new InternetAddress("sender@example.com");
    action.recipient = new InternetAddress("recipient@example.com");
    action.response = response;
    action.tld = "tld";
    action.clock = clock;
    return action;
  }

  @Before
  public void before() throws Exception {
    createTlds("tld", "foo");
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "tld-test-transactions-201706.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "tld-a-activity-201706.csv"),
        PAYLOAD_FAIL);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "foo-test-transactions-201706.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "MANIFEST.txt"),
        MANIFEST_PAYLOAD);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_SUCCESS, "foo-test-transactions-201706.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_FAIL, "tld-a-activity-201706.csv")).thenReturn(false);
    when(mockReporter.send(MANIFEST_PAYLOAD, "MANIFEST.txt")).thenReturn(true);
    clock.setTo(DateTime.parse("2006-06-06T00:30:00Z"));
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD, DateTime.parse("2006-06-06TZ"), Registry.get("tld")));
    persistResource(
        Cursor.createGlobal(CursorType.ICANN_UPLOAD_MANIFEST, DateTime.parse("2006-07-06TZ")));
  }

  @Test
  public void testSuccess() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "tld-test-transactions-201706.csv - SUCCESS\n"
                    + "tld-a-activity-201706.csv - FAILURE",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testSuccess_AdvancesCursor() throws Exception {
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "tld-a-activity-201706.csv"),
        PAYLOAD_SUCCESS);
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-a-activity-201706.csv")).thenReturn(true);
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.ICANN_UPLOAD, Registry.get("tld"))).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
  }

  @Test
  public void testSuccess_UploadManifest() throws Exception {
    persistResource(
        Cursor.createGlobal(CursorType.ICANN_UPLOAD_MANIFEST, DateTime.parse("2006-06-06TZ")));
    IcannReportingUploadAction action = createAction();
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy().load().key(Cursor.createGlobalKey(CursorType.ICANN_UPLOAD_MANIFEST)).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-07-01TZ"));
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-a-activity-201706.csv");
    verify(mockReporter).send(MANIFEST_PAYLOAD, "MANIFEST.txt");
    verifyNoMoreInteractions(mockReporter);
  }

  @Test
  public void testSuccess_WithRetry() throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv"))
        .thenThrow(new IOException("Expected exception."))
        .thenReturn(true);
    action.run();
    verify(mockReporter, times(2)).send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "tld-test-transactions-201706.csv - SUCCESS\n"
                    + "tld-a-activity-201706.csv - FAILURE",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testOnlyUpdateReportsForCorrectTld() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.tld = "foo";
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "foo-test-transactions-201706.csv");
    verifyNoMoreInteractions(mockReporter);
  }

  @Test
  public void testFailure_firstUnrecoverable_stillAttemptsUploadingBoth() throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv"))
        .thenThrow(new IOException("Expected exception"));
    action.run();
    verify(mockReporter, times(3)).send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "tld-a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 0/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "tld-test-transactions-201706.csv - FAILURE\n"
                    + "tld-a-activity-201706.csv - FAILURE",
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
  public void testFailure_CursorIsNotAdvancedForward() throws Exception {
    runTest_nonRetryableException(
        new IOException("Your IP address 25.147.130.158 is not allowed to connect"));
    ofy().clearSessionCache();
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.ICANN_UPLOAD, Registry.get("tld"))).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2006-06-06TZ"));
  }

  @Test
  public void testNotRunIfCursorDateIsAfterToday() throws Exception {
    persistResource(
        Cursor.create(
            CursorType.ICANN_UPLOAD, DateTime.parse("2008-06-06TZ"), Registry.get("tld")));
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv"))
        .thenThrow(new IOException("Expected exception"));
    action.run();
    ofy().clearSessionCache();
    Cursor cursor =
        ofy().load().key(Cursor.createKey(CursorType.ICANN_UPLOAD, Registry.get("tld"))).now();
    assertThat(cursor.getCursorTime()).isEqualTo(DateTime.parse("2008-06-06TZ"));
    verifyNoMoreInteractions(mockReporter);
  }

  private void runTest_nonRetryableException(Exception nonRetryableException) throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_FAIL, "tld-a-activity-201706.csv"))
        .thenThrow(nonRetryableException)
        .thenThrow(
            new AssertionError(
                "This should never be thrown because the previous exception isn't retryable"));
    action.run();
    verify(mockReporter, times(1)).send(PAYLOAD_FAIL, "tld-a-activity-201706.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "tld-test-transactions-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "tld-test-transactions-201706.csv - SUCCESS\n"
                    + "tld-a-activity-201706.csv - FAILURE",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testFail_FileNotFound() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.subdir = "somewhere/else";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Object MANIFEST.txt in bucket basin/somewhere/else not found");
  }
}

