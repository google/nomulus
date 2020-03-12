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

package google.registry.backup;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.ofy.CommitLogCheckpointRoot.loadRoot;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ofy.CommitLogCheckpoint;
import google.registry.model.ofy.CommitLogCheckpointRoot;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CommitLogCheckpointAction}. */
@RunWith(JUnit4.class)
public class CommitLogCheckpointActionTest {

  private static final String QUEUE_NAME = "export-commits";

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  CommitLogCheckpointStrategy strategy = mock(CommitLogCheckpointStrategy.class);

  DateTime now = DateTime.now(UTC);
  CommitLogCheckpointAction task = new CommitLogCheckpointAction();

  @Before
  public void before() {
    task.clock = new FakeClock(now);
    task.strategy = strategy;
    task.taskQueueUtils = new TaskQueueUtils(new Retrier(null, 1));
    when(strategy.computeCheckpoint())
        .thenReturn(
            CommitLogCheckpoint.create(
                now, ImmutableMap.of(1, START_OF_TIME, 2, START_OF_TIME, 3, START_OF_TIME)));
  }

  @Test
  public void testRun_noCheckpointEverWritten_writesCheckpointAndEnqueuesTask() {
    task.run();
    assertTasksEnqueued(
        QUEUE_NAME,
        new TaskMatcher()
            .url(ExportCommitLogDiffAction.PATH)
            .param(ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM, START_OF_TIME.toString())
            .param(ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM, now.toString()));
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(now);
  }

  @Test
  public void testRun_checkpointWrittenBeforeNow_writesCheckpointAndEnqueuesTask() {
    DateTime oneMinuteAgo = now.minusMinutes(1);
    persistResource(CommitLogCheckpointRoot.create(oneMinuteAgo));
    task.run();
    assertTasksEnqueued(
        QUEUE_NAME,
        new TaskMatcher()
            .url(ExportCommitLogDiffAction.PATH)
            .param(ExportCommitLogDiffAction.LOWER_CHECKPOINT_TIME_PARAM, oneMinuteAgo.toString())
            .param(ExportCommitLogDiffAction.UPPER_CHECKPOINT_TIME_PARAM, now.toString()));
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(now);
  }

  @Test
  public void testRun_checkpointWrittenAfterNow_doesntOverwrite_orEnqueueTask() {
    DateTime oneMinuteFromNow = now.plusMinutes(1);
    persistResource(CommitLogCheckpointRoot.create(oneMinuteFromNow));
    task.run();
    assertNoTasksEnqueued(QUEUE_NAME);
    assertThat(loadRoot().getLastWrittenTime()).isEqualTo(oneMinuteFromNow);
  }
}
