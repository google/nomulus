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
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Templates;
import com.google.api.services.dataflow.Dataflow.Projects.Templates.Launch;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchTemplateParameters;
import com.google.api.services.dataflow.model.LaunchTemplateResponse;
import com.google.api.services.dataflow.model.RuntimeEnvironment;
import com.google.common.collect.ImmutableMap;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.billing.GenerateInvoicesAction}. */
class GenerateInvoicesActionTest {

  @RegisterExtension
  final AppEngineRule appEngine = AppEngineRule.builder().withTaskQueue().build();

  private Dataflow dataflow;
  private Projects projects;
  private Templates templates;
  private Launch launch;
  private FakeResponse response;
  private BillingEmailUtils emailUtils;
  private GenerateInvoicesAction action;

  @BeforeEach
  void beforeEach() throws IOException {
    dataflow = mock(Dataflow.class);
    projects = mock(Projects.class);
    templates = mock(Templates.class);
    launch = mock(Launch.class);
    emailUtils = mock(BillingEmailUtils.class);
    when(dataflow.projects()).thenReturn(projects);
    when(projects.templates()).thenReturn(templates);
    when(templates.launch(any(String.class), any(LaunchTemplateParameters.class)))
        .thenReturn(launch);
    when(launch.setGcsPath(any(String.class))).thenReturn(launch);

    response = new FakeResponse();
    Job job = new Job();
    job.setId("12345");
    when(launch.execute()).thenReturn(new LaunchTemplateResponse().setJob(job));
  }

  @Test
  void testLaunchTemplateJob_withPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "gs://test-project-beam",
            "gs://test-project-beam/templates/invoicing",
            "us-east1-c",
            true,
            new YearMonth(2017, 10),
            dataflow,
            response,
            emailUtils);
    action.run();
    LaunchTemplateParameters expectedParams =
        new LaunchTemplateParameters()
            .setJobName("invoicing-2017-10")
            .setEnvironment(
                new RuntimeEnvironment()
                    .setZone("us-east1-c")
                    .setTempLocation("gs://test-project-beam/temporary"))
            .setParameters(ImmutableMap.of("yearMonth", "2017-10"));
    verify(templates).launch("test-project", expectedParams);
    verify(launch).setGcsPath("gs://test-project-beam/templates/invoicing");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo("Launched dataflow template.");

    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/publishInvoices")
            .method("POST")
            .param("jobId", "12345")
            .param("yearMonth", "2017-10");
    assertTasksEnqueued("beam-reporting", matcher);
  }

  @Test
  void testLaunchTemplateJob_withoutPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "gs://test-project-beam",
            "gs://test-project-beam/templates/invoicing",
            "us-east1-c",
            false,
            new YearMonth(2017, 10),
            dataflow,
            response,
            emailUtils);
    action.run();
    LaunchTemplateParameters expectedParams =
        new LaunchTemplateParameters()
            .setJobName("invoicing-2017-10")
            .setEnvironment(
                new RuntimeEnvironment()
                    .setZone("us-east1-c")
                    .setTempLocation("gs://test-project-beam/temporary"))
            .setParameters(ImmutableMap.of("yearMonth", "2017-10"));
    verify(templates).launch("test-project", expectedParams);
    verify(launch).setGcsPath("gs://test-project-beam/templates/invoicing");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getPayload()).isEqualTo("Launched dataflow template.");
    assertNoTasksEnqueued();
  }

  @Test
  void testCaughtIOException() throws IOException {
    when(launch.execute()).thenThrow(new IOException("expected"));
    action =
        new GenerateInvoicesAction(
            "test-project",
            "gs://test-project-beam",
            "gs://test-project-beam/templates/invoicing",
            "us-east1-c",
            true,
            new YearMonth(2017, 10),
            dataflow,
            response,
            emailUtils);
    action.run();
    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getPayload()).isEqualTo("Template launch failed: expected");
    verify(emailUtils).sendAlertEmail("Template Launch failed due to expected");
  }
}
