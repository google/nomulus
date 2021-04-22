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

package google.registry.reporting.billing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_MODIFIED;
import static javax.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Locations;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.Jobs;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.Jobs.Get;
import com.google.api.services.dataflow.model.Job;
import com.google.common.net.MediaType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PublishInvoicesAction}. */
class PublishInvoicesActionTest {

  private final Dataflow dataflow = mock(Dataflow.class);
  private final Projects projects = mock(Projects.class);
  private final Locations locations = mock(Locations.class);
  private final Jobs jobs = mock(Jobs.class);
  private final Get get = mock(Get.class);
  private final Job expectedJob = new Job();
  private final BillingEmailUtils emailUtils = mock(BillingEmailUtils.class);
  private final FakeResponse response = new FakeResponse();
  private PublishInvoicesAction uploadAction;

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  @BeforeEach
  void beforeEach() throws IOException {
    when(dataflow.projects()).thenReturn(projects);
    when(projects.locations()).thenReturn(locations);
    when(locations.jobs()).thenReturn(jobs);
    when(jobs.get("test-project", "test-region", "12345")).thenReturn(get);
    when(get.execute()).thenReturn(expectedJob);

    uploadAction =
        new PublishInvoicesAction(
            "test-project",
            "test-region",
            "12345",
            emailUtils,
            dataflow,
            response,
            new YearMonth(2017, 10));
  }

  @Test
  void testJobDone_enqueuesCopyAction_emailsResults() {
    expectedJob.setCurrentState("JOB_STATE_DONE");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    verify(emailUtils).emailOverallInvoice();
    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/copyDetailReports")
            .method("POST")
            .param("yearMonth", "2017-10");
    assertTasksEnqueued("retryable-cron-tasks", matcher);
  }

  @Test
  void testJobFailed_returnsNonRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_FAILED");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NO_CONTENT);
    verify(emailUtils).sendAlertEmail("Dataflow job 12345 ended in status failure.");
  }

  @Test
  void testJobIndeterminate_returnsRetriableResponse() {
    expectedJob.setCurrentState("JOB_STATE_RUNNING");
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_NOT_MODIFIED);
  }

  @Test
  void testIOException_returnsFailureMessage() throws IOException {
    when(get.execute()).thenThrow(new IOException("expected"));
    uploadAction.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Template launch failed: expected");
    verify(emailUtils).sendAlertEmail("Publish action failed due to expected");
  }
}
