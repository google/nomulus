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

package google.registry.util;

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TaskQueueHelper.getQueueInfo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskHandle;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.common.collect.ImmutableList;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TaskQueueUtils}. */
public final class TaskQueueUtilsTest {

  private static final int MAX_RETRIES = 3;

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  private int origBatchSize;

  private final FakeClock clock = new FakeClock(DateTime.parse("2000-01-01TZ"));
  private final FakeSleeper sleeper = new FakeSleeper(clock);
  private final TaskQueueUtils taskQueueUtils =
      new TaskQueueUtils(new Retrier(sleeper, MAX_RETRIES));
  private final Queue queue = mock(Queue.class);
  private final TaskOptions task = withUrl("url").taskName("name");
  private final TaskHandle handle = new TaskHandle(task, "handle");

  @BeforeEach
  void beforeEach() {
    origBatchSize = TaskQueueUtils.BATCH_SIZE;
    TaskQueueUtils.BATCH_SIZE = 2;
  }

  @AfterEach
  void afterEach() {
    TaskQueueUtils.BATCH_SIZE = origBatchSize;
  }

  @Test
  void testEnqueue_worksOnFirstTry_doesntSleep() {
    when(queue.add(ImmutableList.of(task))).thenReturn(ImmutableList.of(handle));
    assertThat(taskQueueUtils.enqueue(queue, task)).isSameInstanceAs(handle);
    verify(queue).add(ImmutableList.of(task));
    assertThat(clock.nowUtc()).isEqualTo(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  void testEnqueue_twoTransientErrorsThenSuccess_stillWorksAfterSleeping() {
    when(queue.add(ImmutableList.of(task)))
        .thenThrow(new TransientFailureException(""))
        .thenThrow(new TransientFailureException(""))
        .thenReturn(ImmutableList.of(handle));
    assertThat(taskQueueUtils.enqueue(queue, task)).isSameInstanceAs(handle);
    verify(queue, times(3)).add(ImmutableList.of(task));
    assertThat(clock.nowUtc()).isEqualTo(DateTime.parse("2000-01-01T00:00:00.6Z"));  // 200 + 400ms
  }

  @Test
  void testEnqueue_multiple() {
    TaskOptions taskA = withUrl("a").taskName("a");
    TaskOptions taskB = withUrl("b").taskName("b");
    ImmutableList<TaskHandle> handles =
        ImmutableList.of(new TaskHandle(taskA, "a"), new TaskHandle(taskB, "b"));
    when(queue.add(ImmutableList.of(taskA, taskB))).thenReturn(handles);
    assertThat(taskQueueUtils.enqueue(queue, ImmutableList.of(taskA, taskB)))
        .isSameInstanceAs(handles);
    assertThat(clock.nowUtc()).isEqualTo(DateTime.parse("2000-01-01TZ"));
  }

  @Test
  void testEnqueue_maxRetries_givesUp() {
    when(queue.add(ImmutableList.of(task)))
        .thenThrow(new TransientFailureException("one"))
        .thenThrow(new TransientFailureException("two"))
        .thenThrow(new TransientFailureException("three"))
        .thenThrow(new TransientFailureException("four"));
    TransientFailureException thrown =
        assertThrows(TransientFailureException.class, () -> taskQueueUtils.enqueue(queue, task));
    assertThat(thrown).hasMessageThat().contains("three");
  }

  @Test
  void testEnqueue_transientErrorThenInterrupt_throwsTransientError() {
    when(queue.add(ImmutableList.of(task))).thenThrow(new TransientFailureException(""));
    try {
      Thread.currentThread().interrupt();
      assertThrows(TransientFailureException.class, () -> taskQueueUtils.enqueue(queue, task));
    } finally {
      Thread.interrupted();  // Clear interrupt state so it doesn't pwn other tests.
    }
  }

  @Test
  void testDeleteTasks_usesMultipleBatches() {
    Queue defaultQ = QueueFactory.getQueue("default");
    TaskOptions taskOptA = withUrl("/a").taskName("a");
    TaskOptions taskOptB = withUrl("/b").taskName("b");
    TaskOptions taskOptC = withUrl("/c").taskName("c");
    taskQueueUtils.enqueue(defaultQ, ImmutableList.of(taskOptA, taskOptB, taskOptC));
    assertThat(getQueueInfo("default").getTaskInfo()).hasSize(3);

    taskQueueUtils.deleteTasks(
        defaultQ,
        ImmutableList.of(
            new TaskHandle(taskOptA, "default"),
            new TaskHandle(taskOptB, "default"),
            new TaskHandle(taskOptC, "default")));
    assertThat(getQueueInfo("default").getTaskInfo()).hasSize(0);
  }
}
