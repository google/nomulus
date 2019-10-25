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
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.io.IOException;
import javax.mail.internet.InternetAddress;
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
      "test-transactions-201706.csv\na-activity-201706.csv\n".getBytes(UTF_8);
  private final IcannHttpReporter mockReporter = mock(IcannHttpReporter.class);
  private final SendEmailService emailService = mock(SendEmailService.class);
  private final FakeResponse response = new FakeResponse();
  private final GcsService gcsService = GcsServiceFactory.createGcsService();

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
    return action;
  }

  @Before
  public void before() throws Exception {
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "test-transactions-201706.csv"),
        PAYLOAD_SUCCESS);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "a-activity-201706.csv"),
        PAYLOAD_FAIL);
    writeGcsFile(
        gcsService,
        new GcsFilename("basin/icann/monthly/2017-06", "MANIFEST.txt"),
        MANIFEST_PAYLOAD);
    when(mockReporter.send(PAYLOAD_SUCCESS, "test-transactions-201706.csv")).thenReturn(true);
    when(mockReporter.send(PAYLOAD_FAIL, "a-activity-201706.csv")).thenReturn(false);
  }

  @Test
  public void testSuccess() throws Exception {
    IcannReportingUploadAction action = createAction();
    action.run();
    verify(mockReporter).send(PAYLOAD_SUCCESS, "test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "test-transactions-201706.csv - SUCCESS\n"
                    + "a-activity-201706.csv - FAILURE",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testSuccess_WithRetry() throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "test-transactions-201706.csv"))
        .thenThrow(new IOException("Expected exception."))
        .thenReturn(true);
    action.run();
    verify(mockReporter, times(2)).send(PAYLOAD_SUCCESS, "test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "test-transactions-201706.csv - SUCCESS\n"
                    + "a-activity-201706.csv - FAILURE",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
  }

  @Test
  public void testFailure_firstUnrecoverable_stillAttemptsUploadingBoth() throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_SUCCESS, "test-transactions-201706.csv"))
        .thenThrow(new IOException("Expected exception"));
    action.run();
    verify(mockReporter, times(3)).send(PAYLOAD_SUCCESS, "test-transactions-201706.csv");
    verify(mockReporter).send(PAYLOAD_FAIL, "a-activity-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 0/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "test-transactions-201706.csv - FAILURE\n"
                    + "a-activity-201706.csv - FAILURE",
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

  private void runTest_nonRetryableException(Exception nonRetryableException) throws Exception {
    IcannReportingUploadAction action = createAction();
    when(mockReporter.send(PAYLOAD_FAIL, "a-activity-201706.csv"))
        .thenThrow(nonRetryableException)
        .thenThrow(
            new AssertionError(
                "This should never be thrown because the previous exception isn't retryable"));
    action.run();
    verify(mockReporter, times(1)).send(PAYLOAD_FAIL, "a-activity-201706.csv");
    verify(mockReporter).send(PAYLOAD_SUCCESS, "test-transactions-201706.csv");
    verifyNoMoreInteractions(mockReporter);
    assertThat(((FakeResponse) action.response).getPayload())
        .isEqualTo("OK, attempted uploading 2 reports");
    verify(emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report upload summary: 1/2 succeeded",
                "Report Filename - Upload status:\n"
                    + "test-transactions-201706.csv - SUCCESS\n"
                    + "a-activity-201706.csv - FAILURE",
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

