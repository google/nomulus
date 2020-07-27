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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.testing.TestLogHandlerUtils.assertLogMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.ErrorProto;
import com.google.api.services.bigquery.model.Job;
import com.google.api.services.bigquery.model.JobReference;
import com.google.api.services.bigquery.model.JobStatus;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.appengine.api.taskqueue.dev.QueueStateInfo.TaskStateInfo;
import com.google.common.flogger.LoggerConfig;
import google.registry.export.BigqueryPollJobAction.BigqueryPollJobEnqueuer;
import google.registry.request.HttpException.BadRequestException;
import google.registry.request.HttpException.NotModifiedException;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.TaskQueueHelper;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.CapturingLogHandler;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BigqueryPollJobAction}. */
public class BigqueryPollJobActionTest {

  @RegisterExtension
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private static final String PROJECT_ID = "project_id";
  private static final String JOB_ID = "job_id";
  private static final String CHAINED_QUEUE_NAME = UpdateSnapshotViewAction.QUEUE;
  private static final TaskQueueUtils TASK_QUEUE_UTILS =
      new TaskQueueUtils(new Retrier(new FakeSleeper(new FakeClock()), 1));

  private final Bigquery bigquery = mock(Bigquery.class);
  private final Bigquery.Jobs bigqueryJobs = mock(Bigquery.Jobs.class);
  private final Bigquery.Jobs.Get bigqueryJobsGet = mock(Bigquery.Jobs.Get.class);

  private final CapturingLogHandler logHandler = new CapturingLogHandler();
  private BigqueryPollJobAction action = new BigqueryPollJobAction();

  @BeforeEach
  void beforeEach() throws Exception {
    action.bigquery = bigquery;
    when(bigquery.jobs()).thenReturn(bigqueryJobs);
    when(bigqueryJobs.get(PROJECT_ID, JOB_ID)).thenReturn(bigqueryJobsGet);
    action.taskQueueUtils = TASK_QUEUE_UTILS;
    action.projectId = PROJECT_ID;
    action.jobId = JOB_ID;
    action.chainedQueueName = () -> CHAINED_QUEUE_NAME;
    LoggerConfig.getConfig(BigqueryPollJobAction.class).addHandler(logHandler);
  }

  private static TaskMatcher newPollJobTaskMatcher(String method) {
    return new TaskMatcher()
        .method(method)
        .url(BigqueryPollJobAction.PATH)
        .header(BigqueryPollJobAction.PROJECT_ID_HEADER, PROJECT_ID)
        .header(BigqueryPollJobAction.JOB_ID_HEADER, JOB_ID);
  }

  @Test
  void testSuccess_enqueuePollTask() {
    new BigqueryPollJobEnqueuer(TASK_QUEUE_UTILS).enqueuePollTask(
        new JobReference().setProjectId(PROJECT_ID).setJobId(JOB_ID));
    assertTasksEnqueued(BigqueryPollJobAction.QUEUE, newPollJobTaskMatcher("GET"));
  }

  @Test
  void testSuccess_enqueuePollTask_withChainedTask() throws Exception {
    TaskOptions chainedTask = TaskOptions.Builder
        .withUrl("/_dr/something")
        .method(Method.POST)
        .header("X-Testing", "foo")
        .param("testing", "bar");
    new BigqueryPollJobEnqueuer(TASK_QUEUE_UTILS).enqueuePollTask(
        new JobReference().setProjectId(PROJECT_ID).setJobId(JOB_ID),
        chainedTask,
        getQueue(CHAINED_QUEUE_NAME));
    assertTasksEnqueued(BigqueryPollJobAction.QUEUE, newPollJobTaskMatcher("POST"));
    TaskStateInfo taskInfo = getOnlyElement(
        TaskQueueHelper.getQueueInfo(BigqueryPollJobAction.QUEUE).getTaskInfo());
    ByteArrayInputStream taskBodyBytes = new ByteArrayInputStream(taskInfo.getBodyAsBytes());
    TaskOptions taskOptions = (TaskOptions) new ObjectInputStream(taskBodyBytes).readObject();
    assertThat(taskOptions).isEqualTo(chainedTask);
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

    TaskOptions chainedTask =
        TaskOptions.Builder.withUrl("/_dr/something")
            .method(Method.POST)
            .header("X-Testing", "foo")
            .param("testing", "bar")
            .taskName("my_task_name");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    new ObjectOutputStream(bytes).writeObject(chainedTask);
    action.payload = bytes.toByteArray();

    action.run();
    assertLogMessage(
        logHandler, INFO, String.format("Bigquery job succeeded - %s:%s", PROJECT_ID, JOB_ID));
    assertLogMessage(
        logHandler,
        INFO,
        "Added chained task my_task_name for /_dr/something to queue " + CHAINED_QUEUE_NAME);
    assertTasksEnqueued(
        CHAINED_QUEUE_NAME,
        new TaskMatcher()
            .url("/_dr/something")
            .method("POST")
            .header("X-Testing", "foo")
            .param("testing", "bar")
            .taskName("my_task_name"));
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
    assertNoTasksEnqueued(CHAINED_QUEUE_NAME);
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
    action.payload = "payload".getBytes(UTF_8);
    BadRequestException thrown = assertThrows(BadRequestException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("Cannot deserialize task from payload");
  }
}
