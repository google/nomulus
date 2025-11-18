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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.Action.Method.GET;
import static google.registry.request.Action.Method.POST;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.LinkedListMultimap;
import google.registry.batch.CloudTasksUtils.SerializableCloudTasksClient;
import google.registry.request.Action;
import google.registry.request.Action.Service;
import google.registry.request.auth.Auth;
import google.registry.testing.CloudTasksHelper.FakeGoogleCredentialsBundle;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CloudTasksUtils}. */
public class CloudTasksUtilsTest {
  // Use a LinkedListMultimap to preserve order of the inserted entries for assertion.
  private final LinkedListMultimap<String, String> params = LinkedListMultimap.create();
  private final SerializableCloudTasksClient mockClient = mock(SerializableCloudTasksClient.class);
  private final FakeClock clock = new FakeClock(DateTime.parse("2021-11-08"));
  private final CloudTasksUtils cloudTasksUtils =
      new CloudTasksUtils(
          new Retrier(new FakeSleeper(clock), 1),
          clock,
          "project",
          "location",
          "clientId",
          FakeGoogleCredentialsBundle.create(),
          mockClient);

  @BeforeEach
  void beforeEach() {
    params.put("key1", "val1");
    params.put("key2", "val2");
    params.put("key1", "val3");
    when(mockClient.enqueue(anyString(), anyString(), anyString(), any(Task.class)))
        .thenAnswer(invocation -> invocation.getArgument(3));
  }

  @Test
  void testFailure_createTasks_withNegativeDelay() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                cloudTasksUtils.createTaskWithDelay(
                    TheAction.class, GET, params, Duration.standardMinutes(-10)));
    assertThat(thrown).hasMessageThat().isEqualTo("Negative duration is not supported.");
  }

  @Test
  void testFailure_illegalPath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> cloudTasksUtils.createTask("the/path", GET, Service.BACKEND, params));
    assertThrows(
        IllegalArgumentException.class,
        () -> cloudTasksUtils.createTask(null, GET, Service.BACKEND, params));
    assertThrows(
        IllegalArgumentException.class,
        () -> cloudTasksUtils.createTask("", GET, Service.BACKEND, params));
  }

  @Test
  void testSuccess_enqueueTask() {
    Task task = cloudTasksUtils.createTask(TheAction.class, GET, params);
    cloudTasksUtils.enqueue("test-queue", task);
    verify(mockClient).enqueue("project", "location", "test-queue", task);
  }

  @Test
  void testSuccess_enqueueTasks_varargs() {
    Task task1 = cloudTasksUtils.createTask(TheAction.class, GET, params);
    Task task2 = cloudTasksUtils.createTask(OtherAction.class, GET, params);
    cloudTasksUtils.enqueue("test-queue", task1, task2);
    verify(mockClient).enqueue("project", "location", "test-queue", task1);
    verify(mockClient).enqueue("project", "location", "test-queue", task2);
  }

  @Test
  void testSuccess_enqueueTasks_iterable() {
    Task task1 = cloudTasksUtils.createTask(TheAction.class, GET, params);
    Task task2 = cloudTasksUtils.createTask(OtherAction.class, GET, params);
    cloudTasksUtils.enqueue("test-queue", ImmutableList.of(task1, task2));
    verify(mockClient).enqueue("project", "location", "test-queue", task1);
    verify(mockClient).enqueue("project", "location", "test-queue", task2);
  }

  @Test
  void testSuccess_createGetTasks() {
    Task task = cloudTasksUtils.createTask(TheAction.class, GET, params);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createTasks_WithPathAndService() {
    Task task = cloudTasksUtils.createTask("/the/path", GET, Service.BACKEND, params);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testFailure_createTasks_notAnAction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> cloudTasksUtils.createTask(NotAnAction.class, GET, params));
  }

  @Test
  void testFailure_methodNotAllowed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> cloudTasksUtils.createTask(OtherAction.class, POST, params));
  }

  @Test
  void testSuccess_createPostTasks() {
    Task task = cloudTasksUtils.createTask(TheAction.class, POST, params);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
    assertThat(task.getHttpRequest().getUrl()).isEqualTo("https://backend.registry.test/the/path");
    assertThat(task.getHttpRequest().getHeadersMap().get("Content-Type"))
        .isEqualTo("application/x-www-form-urlencoded");
    assertThat(task.getHttpRequest().getBody().toString(StandardCharsets.UTF_8))
        .isEqualTo("key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createGetTasks_withNullParams() {
    Task task = cloudTasksUtils.createTask(TheAction.class, GET, null);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl()).isEqualTo("https://backend.registry.test/the/path");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createPostTasks_withNullParams() {
    Task task = cloudTasksUtils.createTask(TheAction.class, POST, null);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
    assertThat(task.getHttpRequest().getUrl()).isEqualTo("https://backend.registry.test/the/path");
    assertThat(task.getHttpRequest().getBody().toString(StandardCharsets.UTF_8)).isEmpty();
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createGetTasks_withEmptyParams() {
    Task task = cloudTasksUtils.createTask(TheAction.class, GET, ImmutableMultimap.of());
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl()).isEqualTo("https://backend.registry.test/the/path");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createPostTasks_withEmptyParams() {
    Task task = cloudTasksUtils.createTask(TheAction.class, POST, ImmutableMultimap.of());
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
    assertThat(task.getHttpRequest().getUrl()).isEqualTo("https://backend.registry.test/the/path");
    assertThat(task.getHttpRequest().getBody().toString(StandardCharsets.UTF_8)).isEmpty();
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @SuppressWarnings("ProtoTimestampGetSecondsGetNano")
  @Test
  void testSuccess_createTasks_withJitterSeconds() {
    Task task =
        cloudTasksUtils.createTaskWithJitter(TheAction.class, GET, params, Optional.of(100));
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);

    assertThat(task.getScheduleTime().getSeconds()).isNotEqualTo(0);
    Instant scheduleTime = Instant.ofEpochSecond(task.getScheduleTime().getSeconds());
    Instant lowerBoundTime = Instant.ofEpochMilli(clock.nowUtc().getMillis());
    Instant upperBound = Instant.ofEpochMilli(clock.nowUtc().plusSeconds(100).getMillis());

    assertThat(scheduleTime.isBefore(lowerBoundTime)).isFalse();
    assertThat(upperBound.isBefore(scheduleTime)).isFalse();
  }

  @Test
  void testSuccess_createTasks_withEmptyJitterSeconds() {
    Task task =
        cloudTasksUtils.createTaskWithJitter(TheAction.class, GET, params, Optional.empty());
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createTasks_withZeroJitterSeconds() {
    Task task = cloudTasksUtils.createTaskWithJitter(TheAction.class, GET, params, Optional.of(0));
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Test
  void testSuccess_createTasks_withDelay() {
    Task task =
        cloudTasksUtils.createTaskWithDelay(
            TheAction.class, GET, params, Duration.standardMinutes(10));
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(Instant.ofEpochSecond(task.getScheduleTime().getSeconds()))
        .isEqualTo(Instant.ofEpochMilli(clock.nowUtc().plusMinutes(10).getMillis()));
  }

  @Test
  void testSuccess_createTasks_withZeroDelay() {
    Task task = cloudTasksUtils.createTaskWithDelay(TheAction.class, GET, params, Duration.ZERO);
    assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.GET);
    assertThat(task.getHttpRequest().getUrl())
        .isEqualTo("https://backend.registry.test/the/path?key1=val1&key2=val2&key1=val3");
    verifyOidcToken(task);
    assertThat(task.getScheduleTime().getSeconds()).isEqualTo(0);
  }

  @Action(
      service = Service.BACKEND,
      path = "/the/path",
      method = {GET, POST},
      auth = Auth.AUTH_ADMIN)
  private static class TheAction implements Runnable {

    @Override
    public void run() {}
  }

  @Action(
      service = Service.BACKEND,
      path = "/other/path",
      method = {GET},
      auth = Auth.AUTH_ADMIN)
  private static class OtherAction implements Runnable {

    @Override
    public void run() {}
  }

  private static class NotAnAction implements Runnable {

    @Override
    public void run() {}
  }

  private static void verifyOidcToken(Task task) {
    assertThat(task.getHttpRequest().getOidcToken())
        .isEqualTo(
            OidcToken.newBuilder()
                .setServiceAccountEmail("service@account.com")
                .setAudience("clientId")
                .build());
  }
}
