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

package google.registry.export;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TestLogHandlerUtils.assertLogMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.ErrorProto;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.cloud.tasks.v2.AppEngineHttpRequest;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.Task;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.protobuf.ByteString;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotModifiedException;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.util.CapturingLogHandler;
import google.registry.util.JdkLoggerConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BigqueryPollJobAction}. */
public class BigqueryPollJobActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private static final String PROJECT_ID = "project_id";
  private static final String JOB_ID = "job_id";
  private static final String CHAINED_QUEUE_NAME = UpdateSnapshotViewAction.QUEUE;

  private final Bigquery bigquery = mock(Bigquery.class);
  private final Bigquery.Jobs bigqueryJobs = mock(Bigquery.Jobs.class);
  private final Bigquery.Jobs.Get bigqueryJobsGet = mock(Bigquery.Jobs.Get.class);

  private final CapturingLogHandler logHandler = new CapturingLogHandler();
  private BigqueryPollJobAction action = new BigqueryPollJobAction();
  private CloudTasksHelper cloudTasksHelper = new CloudTasksHelper();

  @BeforeEach
  void beforeEach() throws Exception {
    action.bigquery = bigquery;
    when(bigquery.jobs()).thenReturn(bigqueryJobs);
    when(bigqueryJobs.get(PROJECT_ID, JOB_ID)).thenReturn(bigqueryJobsGet);
    action.cloudTasksUtils = cloudTasksHelper.getTestCloudTasksUtils();
    action.projectId = PROJECT_ID;
    action.jobId = JOB_ID;
    action.chainedQueueName = () -> CHAINED_QUEUE_NAME;
    JdkLoggerConfig.getConfig(BigqueryPollJobAction.class).addHandler(logHandler);
  }

  @Test
  void testSuccess_jobCompletedSuccessfully() throws Exception {
    when(bigqueryJobsGet.execute()).thenReturn(
        new Job().setStatus(new JobStatus().setState("DONE")));
    action.run();
    assertLogMessage(
        logHandler, INFO, String.format("Bigquery job succeeded - %s:%s", PROJECT_ID, JOB_ID));
  }

  @Test
  void testSuccess_chainedPayloadAndJobSucceeded_enqueuesChainedTask() throws Exception {
    when(bigqueryJobsGet.execute()).thenReturn(
        new Job().setStatus(new JobStatus().setState("DONE")));

    Task chainedTask =
        Task.newBuilder()
            .setName("my_task_name")
            .setAppEngineHttpRequest(
                AppEngineHttpRequest.newBuilder()
                    .setHttpMethod(HttpMethod.POST)
                    .setRelativeUri("/_dr/something")
                    .putHeaders("X-Test", "foo")
                    .putHeaders(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
                    .setBody(ByteString.copyFromUtf8("testing=bar")))
            .build();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    new ObjectOutputStream(bytes).writeObject(chainedTask);
    action.payload = ByteString.copyFrom(bytes.toByteArray());

    action.run();
    assertLogMessage(
        logHandler, INFO, String.format("Bigquery job succeeded - %s:%s", PROJECT_ID, JOB_ID));
    assertLogMessage(
        logHandler,
        INFO,
        "Added chained task my_task_name for /_dr/something to queue " + CHAINED_QUEUE_NAME);
    cloudTasksHelper.assertTasksEnqueued(
        CHAINED_QUEUE_NAME,
        new TaskMatcher()
            .url("/_dr/something")
            .header("X-Test", "foo")
            .header(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
            .param("testing", "bar")
            .taskName("my_task_name")
            .method(HttpMethod.POST));
  }

  @Test
  void testJobFailed() throws Exception {
    when(bigqueryJobsGet.execute()).thenReturn(new Job().setStatus(
        new JobStatus()
            .setState("DONE")
            .setErrorResult(new ErrorProto().setMessage("Job failed"))));
    action.run();
    assertLogMessage(
        logHandler, SEVERE, String.format("Bigquery job failed - %s:%s", PROJECT_ID, JOB_ID));
    cloudTasksHelper.assertNoTasksEnqueued(CHAINED_QUEUE_NAME);
  }

  @Test
  void testJobPending() throws Exception {
    when(bigqueryJobsGet.execute()).thenReturn(
        new Job().setStatus(new JobStatus().setState("PENDING")));
    assertThrows(NotModifiedException.class, action::run);
  }

  @Test
  void testJobStatusUnreadable() throws Exception {
    when(bigqueryJobsGet.execute()).thenThrow(IOException.class);
    assertThrows(NotModifiedException.class, action::run);
  }

  @Test
  void testFailure_badChainedTaskPayload() throws Exception {
    when(bigqueryJobsGet.execute()).thenReturn(
        new Job().setStatus(new JobStatus().setState("DONE")));
    action.payload = ByteString.copyFrom("payload".getBytes(UTF_8));
    BadRequestException thrown = assertThrows(BadRequestException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("Cannot deserialize task from payload");
  }
}
