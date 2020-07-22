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
import com.google.common.net.MediaType;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.joda.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.spec11.GenerateSpec11ReportAction}. */
class GenerateSpec11ReportActionTest {

  @RegisterExtension
  final AppEngineRule appEngine = AppEngineRule.builder().withTaskQueue().build();

  private FakeResponse response;
  private Dataflow dataflow;
  private Projects dataflowProjects;
  private Templates dataflowTemplates;
  private Launch dataflowLaunch;

  private GenerateSpec11ReportAction action;

  @BeforeEach
  void beforeEach() throws IOException {
    response = new FakeResponse();
    dataflow = mock(Dataflow.class);

    // Establish the Dataflow API call chain
    dataflow = mock(Dataflow.class);
    dataflowProjects = mock(Dataflow.Projects.class);
    dataflowTemplates = mock(Templates.class);
    dataflowLaunch = mock(Launch.class);
    LaunchTemplateResponse launchTemplateResponse = new LaunchTemplateResponse();
    // Ultimately we get back this job response with a given id.
    launchTemplateResponse.setJob(new Job().setId("jobid"));
    when(dataflow.projects()).thenReturn(dataflowProjects);
    when(dataflowProjects.templates()).thenReturn(dataflowTemplates);
    when(dataflowTemplates.launch(any(String.class), any(LaunchTemplateParameters.class)))
        .thenReturn(dataflowLaunch);
    when(dataflowLaunch.setGcsPath(any(String.class))).thenReturn(dataflowLaunch);
    when(dataflowLaunch.execute()).thenReturn(launchTemplateResponse);
  }

  @Test
  void testLaunch_success() throws IOException {
    action =
        new GenerateSpec11ReportAction(
            "test",
            "gs://my-bucket-beam",
            "gs://template",
            "us-east1-c",
            "api_key/a",
            new LocalDate(2018, 6, 11),
            response,
            dataflow);
    action.run();

    LaunchTemplateParameters expectedLaunchTemplateParameters =
        new LaunchTemplateParameters()
            .setJobName("spec11_2018-06-11")
            .setEnvironment(
                new RuntimeEnvironment()
                    .setZone("us-east1-c")
                    .setTempLocation("gs://my-bucket-beam/temporary"))
            .setParameters(
                ImmutableMap.of("safeBrowsingApiKey", "api_key/a", "date", "2018-06-11"));
    verify(dataflowTemplates).launch("test", expectedLaunchTemplateParameters);
    verify(dataflowLaunch).setGcsPath("gs://template");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).isEqualTo("Launched Spec11 dataflow template.");

    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/publishSpec11")
            .method("POST")
            .param("jobId", "jobid")
            .param("date", "2018-06-11");
    assertTasksEnqueued("beam-reporting", matcher);
  }
}
