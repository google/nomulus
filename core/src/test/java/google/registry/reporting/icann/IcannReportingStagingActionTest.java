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
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.bigquery.BigqueryJobFailureException;
import google.registry.reporting.icann.IcannReportingModule.ReportType;
import google.registry.request.HttpException.BadRequestException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.EmailMessage;
import google.registry.util.Retrier;
import google.registry.util.SendEmailService;
import java.util.Optional;
import javax.mail.internet.InternetAddress;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.icann.IcannReportingStagingAction}. */
class IcannReportingStagingActionTest {

  private FakeResponse response = new FakeResponse();
  private IcannReportingStager stager = mock(IcannReportingStager.class);
  private YearMonth yearMonth = new YearMonth(2017, 6);
  private String subdir = "default/dir";
  private IcannReportingStagingAction action;

  @RegisterExtension
  final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withLocalModules().withTaskQueue().build();

  @BeforeEach
  void beforeEach() throws Exception {
    action = new IcannReportingStagingAction();
    action.yearMonth = yearMonth;
    action.overrideSubdir = Optional.of(subdir);
    action.reportTypes = ImmutableSet.of(ReportType.ACTIVITY, ReportType.TRANSACTIONS);
    action.response = response;
    action.stager = stager;
    action.retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);
    action.sender = new InternetAddress("sender@example.com");
    action.recipient = new InternetAddress("recipient@example.com");
    action.emailService = mock(SendEmailService.class);

    when(stager.stageReports(yearMonth, subdir, ReportType.ACTIVITY))
        .thenReturn(ImmutableList.of("a", "b"));
    when(stager.stageReports(yearMonth, subdir, ReportType.TRANSACTIONS))
        .thenReturn(ImmutableList.of("c", "d"));
  }

  private static void assertUploadTaskEnqueued() {
    TaskMatcher matcher = new TaskMatcher().url("/_dr/task/icannReportingUpload").method("POST");
    assertTasksEnqueued("retryable-cron-tasks", matcher);
  }

  @Test
  void testActivityReportingMode_onlyStagesActivityReports() throws Exception {
    action.reportTypes = ImmutableSet.of(ReportType.ACTIVITY);
    action.run();
    verify(stager).stageReports(yearMonth, subdir, ReportType.ACTIVITY);
    verify(stager).createAndUploadManifest(subdir, ImmutableList.of("a", "b"));
    verify(action.emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report staging summary [SUCCESS]",
                "Completed staging the following 2 ICANN reports:\na\nb",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
    assertUploadTaskEnqueued();
  }

  @Test
  void testAbsentReportingMode_stagesBothReports() throws Exception {
    action.run();
    verify(stager).stageReports(yearMonth, subdir, ReportType.ACTIVITY);
    verify(stager).stageReports(yearMonth, subdir, ReportType.TRANSACTIONS);
    verify(stager).createAndUploadManifest(subdir, ImmutableList.of("a", "b", "c", "d"));
    verify(action.emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report staging summary [SUCCESS]",
                "Completed staging the following 4 ICANN reports:\na\nb\nc\nd",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
    assertUploadTaskEnqueued();
  }

  @Test
  void testRetryOnBigqueryException() throws Exception {
    when(stager.stageReports(yearMonth, subdir, ReportType.TRANSACTIONS))
        .thenThrow(new BigqueryJobFailureException("Expected failure", null, null, null))
        .thenReturn(ImmutableList.of("c", "d"));
    action.run();
    verify(stager, times(2)).stageReports(yearMonth, subdir, ReportType.ACTIVITY);
    verify(stager, times(2)).stageReports(yearMonth, subdir, ReportType.TRANSACTIONS);
    verify(stager).createAndUploadManifest(subdir, ImmutableList.of("a", "b", "c", "d"));
    verify(action.emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report staging summary [SUCCESS]",
                "Completed staging the following 4 ICANN reports:\na\nb\nc\nd",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
    assertUploadTaskEnqueued();
  }

  @Test
  void testEmailEng_onMoreThanRetriableFailure() throws Exception {
    action.reportTypes = ImmutableSet.of(ReportType.ACTIVITY);
    when(stager.stageReports(yearMonth, subdir, ReportType.ACTIVITY))
        .thenThrow(new BigqueryJobFailureException("Expected failure", null, null, null));
    RuntimeException thrown = assertThrows(RuntimeException.class, action::run);
    assertThat(thrown).hasCauseThat().isInstanceOf(BigqueryJobFailureException.class);
    assertThat(thrown).hasMessageThat().isEqualTo("Staging action failed.");
    assertThat(thrown)
        .hasCauseThat()
        .hasMessageThat()
        .isEqualTo("BigqueryJobFailureException: Expected failure");
    verify(stager, times(3)).stageReports(yearMonth, subdir, ReportType.ACTIVITY);
    verify(action.emailService)
        .sendEmail(
            EmailMessage.create(
                "ICANN Monthly report staging summary [FAILURE]",
                "Staging failed due to google.registry.bigquery.BigqueryJobFailureException: "
                    + "BigqueryJobFailureException: Expected failure, check logs for more details.",
                new InternetAddress("recipient@example.com"),
                new InternetAddress("sender@example.com")));
    // Assert no upload task enqueued
    assertNoTasksEnqueued("retryable-cron-tasks");
  }

  @Test
  void testEmptySubDir_returnsDefaultSubdir() {
    action.overrideSubdir = Optional.empty();
    assertThat(action.getSubdir(new YearMonth(2017, 6))).isEqualTo("icann/monthly/2017-06");
  }

  @Test
  void testGivenSubdir_returnsManualSubdir() {
    action.overrideSubdir = Optional.of("manual/dir");
    assertThat(action.getSubdir(new YearMonth(2017, 6))).isEqualTo("manual/dir");
  }

  @Test
  void testInvalidSubdir_throwsException() {
    action.overrideSubdir = Optional.of("/whoops");
    BadRequestException thrown =
        assertThrows(BadRequestException.class, () -> action.getSubdir(new YearMonth(2017, 6)));
    assertThat(thrown)
        .hasMessageThat()
        .contains("subdir must not start or end with a \"/\", got /whoops instead.");
  }
}
