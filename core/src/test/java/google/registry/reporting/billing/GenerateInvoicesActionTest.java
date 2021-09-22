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
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.net.MediaType;
import google.registry.beam.BeamActionTestBase;
import google.registry.model.common.DatabaseMigrationStateSchedule.PrimaryDatabase;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.TestOfyAndSql;
import java.io.IOException;
import org.joda.time.YearMonth;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.reporting.billing.GenerateInvoicesAction}. */
@DualDatabaseTest
class GenerateInvoicesActionTest extends BeamActionTestBase {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private final BillingEmailUtils emailUtils = mock(BillingEmailUtils.class);
  private FakeClock clock = new FakeClock();
  private GenerateInvoicesAction action;

  @TestOfyAndSql
  void testLaunchTemplateJob_withPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            true,
            PrimaryDatabase.DATASTORE,
            new YearMonth(2017, 10),
            emailUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched invoicing pipeline: jobid.");

    TaskMatcher matcher =
        new TaskMatcher()
            .url("/_dr/task/publishInvoices")
            .method("POST")
            .param("jobId", "jobid")
            .param("yearMonth", "2017-10");
    assertTasksEnqueued("beam-reporting", matcher);
  }

  @TestOfyAndSql
  void testLaunchTemplateJob_withoutPublish() throws Exception {
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            false,
            PrimaryDatabase.DATASTORE,
            new YearMonth(2017, 10),
            emailUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getContentType()).isEqualTo(MediaType.PLAIN_TEXT_UTF_8);
    assertThat(response.getStatus()).isEqualTo(SC_OK);
    assertThat(response.getPayload()).isEqualTo("Launched invoicing pipeline: jobid.");
    assertNoTasksEnqueued("beam-reporting");
  }

  @TestOfyAndSql
  void testCaughtIOException() throws IOException {
    when(launch.execute()).thenThrow(new IOException("Pipeline error"));
    action =
        new GenerateInvoicesAction(
            "test-project",
            "test-region",
            "staging_bucket",
            "billing_bucket",
            "REG-INV",
            false,
            PrimaryDatabase.DATASTORE,
            new YearMonth(2017, 10),
            emailUtils,
            clock,
            response,
            dataflow);
    action.run();
    assertThat(response.getStatus()).isEqualTo(SC_INTERNAL_SERVER_ERROR);
    assertThat(response.getPayload()).isEqualTo("Pipeline launch failed: Pipeline error");
    verify(emailUtils).sendAlertEmail("Pipeline Launch failed due to Pipeline error");
    assertNoTasksEnqueued("beam-reporting");
  }
}
