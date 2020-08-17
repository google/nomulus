// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.reporting.spec11;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.getMatchA;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.getMatchB;
import static google.registry.reporting.spec11.Spec11RegistrarThreatMatchesParserTest.sampleThreatMatches;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Jobs;
import com.google.api.services.dataflow.Dataflow.Projects.Jobs.Get;
import com.google.api.services.dataflow.model.Job;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.MediaType;
import google.registry.beam.spec11.ThreatMatch;
import google.registry.reporting.spec11.soy.Spec11EmailSoyInfo;
import google.registry.testing.FakeResponse;
import java.io.IOException;
import java.util.Optional;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PublishSpec11ReportAction}. */
class PublishSpec11ReportActionTest {

  private final LocalDate date = new LocalDate(2018, 6, 5);

  private Dataflow dataflow;
  private Projects projects;
  private Jobs jobs;
  private Get get;
  private Spec11EmailUtils emailUtils;
  private Spec11RegistrarThreatMatchesParser parser;

  private Job expectedJob;
  private FakeResponse response;
  private PublishSpec11ReportAction publishAction;

  @BeforeEach
  void beforeEach() throws Exception {
    dataflow = mock(Dataflow.class);
    projects = mock(Projects.class);
    jobs = mock(Jobs.class);
    get = mock(Get.class);
    when(dataflow.projects()).thenReturn(projects);
    when(projects.jobs()).thenReturn(jobs);
    when(jobs.get("test-project", "12345")).thenReturn(get);
    expectedJob = new Job();
    when(get.execute()).thenReturn(expectedJob);
    emailUtils = mock(Spec11EmailUtils.class);
    parser = mock(Spec11RegistrarThreatMatchesParser.class);
    response = new FakeResponse();
    parser = mock(Spec11RegistrarThreatMatchesParser.class);
    publishAction =
        new PublishSpec11ReportAction(
            "test-project",
            "Super Cool Registry",
            "12345",
            emailUtils,
            parser,
            dataflow,
            response,
            date);
  }

  @Test
  void testJobDone_emailsOnlyMonthlyResultsOnSecondOfMonth() throws Exception {
    LocalDate secondOfMonth = date.withDayOfMonth(2);
    when(parser.getRegistrarThreatMatches(secondOfMonth)).thenReturn(sampleThreatMatches());
    expectedJob.setCurrentState("JOB_STATE_DONE");
    publishAction =
        new PublishSpec11ReportAction(
            "test-project",
            "Super Cool Registry",
            "12345",
            emailUtils,
            parser,
            dataflow,
            response,
            secondOfMonth);
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(emailUtils)
        .emailSpec11Reports(
            secondOfMonth,
            Spec11EmailSoyInfo.MONTHLY_SPEC_11_EMAIL,
            "Super Cool Registry Monthly Threat Detector [2018-06-02]",
            sampleThreatMatches());
    verifyNoMoreInteractions(emailUtils);
  }

  @Test
  void testJobFailed_returnsNonRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_FAILED");
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    verify(emailUtils)
        .sendAlertEmail(
            "Spec11 Dataflow Pipeline Failure 2018-06-05",
            "Spec11 2018-06-05 job 12345 ended in status failure.");
  }

  @Test
  void testJobIndeterminate_returnsRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_RUNNING");
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_MODIFIED);
    verifyNoMoreInteractions(emailUtils);
  }

  @Test
  void testIOException_returnsFailureMessage() throws IOException {
    when(get.execute()).thenThrow(new IOException("expected"));
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Template launch failed: expected");
    verify(emailUtils)
        .sendAlertEmail(
            "Spec11 Publish Failure 2018-06-05",
            "Spec11 2018-06-05 publish action failed due to expected");
  }

  @Test
  void testJobDone_onlyDailyResults() throws Exception {
    LocalDate yesterday = date.minusDays(1);
    when(parser.getPreviousDateWithMatches(date)).thenReturn(Optional.of(yesterday));
    when(parser.getRegistrarThreatMatches(date)).thenReturn(sampleThreatMatches());
    when(parser.getRegistrarThreatMatches(yesterday)).thenReturn(ImmutableSet.of());
    expectedJob.setCurrentState("JOB_STATE_DONE");
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(emailUtils)
        .emailSpec11Reports(
            date,
            Spec11EmailSoyInfo.DAILY_SPEC_11_EMAIL,
            "Super Cool Registry Daily Threat Detector [2018-06-05]",
            sampleThreatMatches());
    verifyNoMoreInteractions(emailUtils);
  }

  @Test
  void testJobDone_multipleEntriesWithSameEmail() throws Exception {
    LocalDate yesterday = date.minusDays(1);
    when(parser.getPreviousDateWithMatches(date)).thenReturn(Optional.of(yesterday));
    when(parser.getRegistrarThreatMatches(yesterday)).thenReturn(ImmutableSet.of());

    // if the input is [{email: a, matches: list1}, {email: a, matches: list2}] then we should
    // concatenate list1 and list2 in the resulting grouping
    RegistrarThreatMatches firstMatches = getMatchA();
    ImmutableList<ThreatMatch> secondMatchList = getMatchB().threatMatches();
    RegistrarThreatMatches secondMatches =
        RegistrarThreatMatches.create("TheRegistrar", secondMatchList);
    when(parser.getRegistrarThreatMatches(date))
        .thenReturn(ImmutableSet.of(firstMatches, secondMatches));
    expectedJob.setCurrentState("JOB_STATE_DONE");
    publishAction.run();
    ImmutableSet<RegistrarThreatMatches> expectedMatchSet =
        ImmutableSet.of(
            RegistrarThreatMatches.create(
                "TheRegistrar",
                ImmutableList.<ThreatMatch>builder()
                    .addAll(firstMatches.threatMatches())
                    .addAll(secondMatchList)
                    .build()));
    verify(emailUtils)
        .emailSpec11Reports(
            date,
            Spec11EmailSoyInfo.DAILY_SPEC_11_EMAIL,
            "Super Cool Registry Daily Threat Detector [2018-06-05]",
            expectedMatchSet);
    verifyNoMoreInteractions(emailUtils);
  }

  @Test
  void testJobDone_noDifferentResults() throws Exception {
    LocalDate yesterday = date.minusDays(1);
    when(parser.getPreviousDateWithMatches(date)).thenReturn(Optional.of(yesterday));
    when(parser.getRegistrarThreatMatches(date)).thenReturn(sampleThreatMatches());
    when(parser.getRegistrarThreatMatches(yesterday)).thenReturn(sampleThreatMatches());
    expectedJob.setCurrentState("JOB_STATE_DONE");
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(emailUtils)
        .emailSpec11Reports(
            date,
            Spec11EmailSoyInfo.DAILY_SPEC_11_EMAIL,
            "Super Cool Registry Daily Threat Detector [2018-06-05]",
            ImmutableSet.of());
    verifyNoMoreInteractions(emailUtils);
  }

  @Test
  void testJobDone_failsDueToNoPreviousResults() {
    when(parser.getPreviousDateWithMatches(date)).thenReturn(Optional.empty());
    expectedJob.setCurrentState("JOB_STATE_DONE");
    publishAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    verify(emailUtils)
        .sendAlertEmail(
            String.format("Spec11 Diff Error %s", date),
            String.format("Could not find a previous file within the past month of %s", date));
  }
}
