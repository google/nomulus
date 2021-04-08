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
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;

import com.google.common.net.MediaType;
import google.registry.beam.BeamActionTestBase;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.billing.GenerateInvoicesAction}. */
class GenerateInvoicesActionTest extends BeamActionTestBase {

  @RegisterExtension
  final AppEngineExtension appEngine = AppEngineExtension.builder().withTaskQueue().build();

  private final BillingEmailUtils emailUtils = mock(BillingEmailUtils.class);
  private FakeClock clock = new FakeClock();
  private GenerateInvoicesAction action;

  @Test
  void testLaunchTemplateJob_withPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            true,
            new YearMonth(2017, 10),
            emailUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched dataflow template.");

    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/publishInvoices")
            .method("POST")
            .param("jobId", "jobid")
            .param("yearMonth", "2017-10");
    assertTasksEnqueued("beam-reporting", matcher);
  }
  /*

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

   */
}
