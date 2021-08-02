// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CloudTasksUtils}. */
public class CloudTasksUtilsTest {
  // Use a LinkedListMultimap to preserve order of the inserted entries for assertion.
  private final LinkedListMultimap<String, String> params = LinkedListMultimap.create();
  private final CloudTasksClient mockClient = mock(CloudTasksClient.class);
  private final CloudTasksUtils cloudTasksUtils =
      new CloudTasksUtils(
          new Retrier(new FakeSleeper(new FakeClock()), 1),
          "project",
          "location",
          () -> mockClient);

  @BeforeEach
  void beforeEach() {
    params.put("key1", "val1");
    params.put("key2", "val2");
    params.put("key1", "val3");
    when(mockClient.createTask(any(QueueName.class), any(Task.class)))
        .thenAnswer(invocation -> invocation.getArgument(1));
  }

  @Test
  void testSuccess_createGetTasks() {
    Task task = CloudTasksUtils.createGetTask("/the/path", "myservice", params);
    assertThat(task.getAppEngineHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getAppEngineHttpRequest().getRelativeUri())
        .isEqualTo("/the/path?key1=val1&key2=val2&key1=val3");
    assertThat(task.getAppEngineHttpRequest().getAppEngineRouting().getService())
        .isEqualTo("myservice");
  }

  @Test
  void testSuccess_createPostTasks() {
    Task task = CloudTasksUtils.createPostTask("/the/path", "myservice", params);
    assertThat(task.getAppEngineHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
    assertThat(task.getAppEngineHttpRequest().getRelativeUri()).isEqualTo("/the/path");
    assertThat(task.getAppEngineHttpRequest().getAppEngineRouting().getService())
        .isEqualTo("myservice");
    assertThat(task.getAppEngineHttpRequest().getHeadersMap().get("Content-Type"))
        .isEqualTo("application/x-www-form-urlencoded");
    assertThat(task.getAppEngineHttpRequest().getBody().toString(StandardCharsets.UTF_8))
        .isEqualTo("key1=val1&key2=val2&key1=val3");
  }

  @Test
  void testFailure_illegalPath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudTasksUtils.createPostTask("the/path", "myservice", params));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudTasksUtils.createPostTask(null, "myservice", params));
    assertThrows(
        IllegalArgumentException.class,
        () -> CloudTasksUtils.createPostTask("", "myservice", params));
  }

  @Test
  void testSuccess_enqueueTask() {
    Task task = CloudTasksUtils.createGetTask("/the/path", "myservice", params);
    cloudTasksUtils.enqueue("test-queue", task);
    verify(mockClient).createTask(QueueName.of("project", "location", "test-queue"), task);
  }

  @Test
  void testSuccess_enqueueTasks_varargs() {
    Task task1 = CloudTasksUtils.createGetTask("/the/path", "myservice", params);
    Task task2 = CloudTasksUtils.createGetTask("/other/path", "yourservice", params);
    cloudTasksUtils.enqueue("test-queue", task1, task2);
    verify(mockClient).createTask(QueueName.of("project", "location", "test-queue"), task1);
    verify(mockClient).createTask(QueueName.of("project", "location", "test-queue"), task2);
  }

  @Test
  void testSuccess_enqueueTasks_iterable() {
    Task task1 = CloudTasksUtils.createGetTask("/the/path", "myservice", params);
    Task task2 = CloudTasksUtils.createGetTask("/other/path", "yourservice", params);
    cloudTasksUtils.enqueue("test-queue", ImmutableList.of(task1, task2));
    verify(mockClient).createTask(QueueName.of("project", "location", "test-queue"), task1);
    verify(mockClient).createTask(QueueName.of("project", "location", "test-queue"), task2);
  }
}
