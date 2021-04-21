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
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.dataflow.Dataflow;
import com.google.api.services.dataflow.Dataflow.Projects;
import com.google.api.services.dataflow.Dataflow.Projects.Locations;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.FlexTemplates;
import com.google.api.services.dataflow.Dataflow.Projects.Locations.FlexTemplates.Launch;
import com.google.api.services.dataflow.model.Job;
import com.google.api.services.dataflow.model.LaunchFlexTemplateRequest;
import com.google.api.services.dataflow.model.LaunchFlexTemplateResponse;
import com.google.common.net.MediaType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import java.io.IOException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link GenerateSpec11ReportAction}. */
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class GenerateSpec11ReportActionTest {

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  private FakeResponse response = new FakeResponse();
  private Dataflow dataflow = mock(Dataflow.class);
  private Projects projects = mock(Projects.class);
  private Locations locations = mock(Locations.class);
  private FlexTemplates templates = mock(FlexTemplates.class);
  private Launch launch = mock(Launch.class);
  private LaunchFlexTemplateResponse launchResponse =
      new LaunchFlexTemplateResponse().setJob(new Job().setId("jobid"));

  private final FakeClock clock = new FakeClock(DateTime.parse("2018-06-11T12:23:56Z"));
  private GenerateSpec11ReportAction action;

  @BeforeEach
  void beforeEach() throws IOException {
    when(dataflow.projects()).thenReturn(projects);
    when(projects.locations()).thenReturn(locations);
    when(locations.flexTemplates()).thenReturn(templates);
    when(templates.launch(anyString(), anyString(), any(LaunchFlexTemplateRequest.class)))
        .thenReturn(launch);
    when(launch.execute()).thenReturn(launchResponse);
  }

  @Test
  void testFailure_dataflowFailure() throws IOException {
    action =
        new GenerateSpec11ReportAction(
            "test-project",
            "us-east1-c",
            "gs://staging-project/staging-bucket/",
            "gs://reporting-project/reporting-bucket/",
            "api_key/a",
            clock.nowUtc().toLocalDate(),
            clock,
            response,
            dataflow);
    when(launch.execute()).thenThrow(new IOException("Dataflow failure"));
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getPayload()).contains("Dataflow failure");
    assertNoTasksEnqueued("beam-reporting");
  }

  @Test
  void testSuccess() throws IOException {
    action =
        new GenerateSpec11ReportAction(
            "test-project",
            "us-east1-c",
            "gs://staging-project/staging-bucket/",
            "gs://reporting-project/reporting-bucket/",
            "api_key/a",
            clock.nowUtc().toLocalDate(),
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/publishSpec11")
            .method("POST")
            .param("jobId", "jobid")
            .param("date", "2018-06-11");
    assertTasksEnqueued("beam-reporting", matcher);
  }
}
