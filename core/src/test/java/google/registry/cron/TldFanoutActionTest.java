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

package google.registry.cron;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getLast;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.appengine.api.taskqueue.dev.QueueStateInfo.TaskStateInfo;
import com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableSet;
import google.registry.model.tld.Registry;
import google.registry.model.tld.Registry.TldType;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeResponse;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Retrier;
import google.registry.util.TaskQueueUtils;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link TldFanoutAction}. */
class TldFanoutActionTest {

  private static final String ENDPOINT = "/the/servlet";
  private static final String QUEUE = "the-queue";
  private final FakeResponse response = new FakeResponse();

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withTaskQueue(
              Joiner.on('\n')
                  .join(
                      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
                      "<queue-entries>",
                      "  <queue>",
                      "    <name>the-queue</name>",
                      "    <rate>1/s</rate>",
                      "  </queue>",
                      "</queue-entries>"))
          .build();

  private static ImmutableListMultimap<String, String> getParamsMap(String... keysAndValues) {
    ImmutableListMultimap.Builder<String, String> params = new ImmutableListMultimap.Builder<>();
    params.put("queue", QUEUE);
    params.put("endpoint", ENDPOINT);
    for (int i = 0; i < keysAndValues.length; i += 2) {
      params.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return params.build();
  }

  private void run(ImmutableListMultimap<String, String> params) {
    TldFanoutAction action = new TldFanoutAction();
    action.params = params;
    action.endpoint = getLast(params.get("endpoint"));
    action.queue = getLast(params.get("queue"));
    action.excludes = params.containsKey("exclude")
        ? ImmutableSet.copyOf(Splitter.on(',').split(params.get("exclude").get(0)))
        : ImmutableSet.of();
    action.taskQueueUtils = new TaskQueueUtils(new Retrier(null, 1));
    action.response = response;
    action.runInEmpty = params.containsKey("runInEmpty");
    action.forEachRealTld = params.containsKey("forEachRealTld");
    action.forEachTestTld = params.containsKey("forEachTestTld");
    action.jitterSeconds = Optional.empty();
    action.run();
  }

  @BeforeEach
  void beforeEach() {
    createTlds("com", "net", "org", "example");
    persistResource(Registry.get("example").asBuilder().setTldType(TldType.TEST).build());
  }

  private static void assertTasks(String... tasks) {
    assertTasksEnqueued(
        QUEUE,
        Stream.of(tasks).map(
            namespace ->
                new TaskMatcher()
                    .url(ENDPOINT)
                    .header("content-type", "application/x-www-form-urlencoded")
                    .param("tld", namespace))
        .collect(toImmutableList()));
  }

  private static void assertTaskWithoutTld() {
    assertTasksEnqueued(
        QUEUE,
        new TaskMatcher()
            .url(ENDPOINT)
            .header("content-type", "application/x-www-form-urlencoded"));
  }

  @Test
  void testSuccess_methodPostIsDefault() {
    run(getParamsMap("runInEmpty", ""));
    assertTasksEnqueued(QUEUE, new TaskMatcher().method("POST"));
  }

  @Test
  void testFailure_noTlds() {
    assertThrows(IllegalArgumentException.class, () -> run(getParamsMap()));
  }

  @Test
  void testSuccess_runInEmpty() {
    run(getParamsMap("runInEmpty", ""));
    assertTaskWithoutTld();
  }

  @Test
  void testSuccess_forEachRealTld() {
    run(getParamsMap("forEachRealTld", ""));
    assertTasks("com", "net", "org");
  }

  @Test
  void testSuccess_forEachTestTld() {
    run(getParamsMap("forEachTestTld", ""));
    assertTasks("example");
  }

  @Test
  void testSuccess_forEachTestTldAndForEachRealTld() {
    run(getParamsMap(
        "forEachTestTld", "",
        "forEachRealTld", ""));
    assertTasks("com", "net", "org", "example");
  }

  @Test
  void testSuccess_runEverywhere() {
    run(getParamsMap("forEachTestTld", "", "forEachRealTld", ""));
    assertTasks("com", "net", "org", "example");
  }

  @Test
  void testSuccess_excludeRealTlds() {
    run(getParamsMap(
        "forEachRealTld", "",
        "exclude", "com,net"));
    assertTasks("org");
  }

  @Test
  void testSuccess_excludeTestTlds() {
    run(getParamsMap(
        "forEachTestTld", "",
        "exclude", "example"));
    assertNoTasksEnqueued(QUEUE);
  }

  @Test
  void testSuccess_excludeNonexistentTlds() {
    run(getParamsMap(
        "forEachTestTld", "",
        "forEachRealTld", "",
        "exclude", "foo"));
    assertTasks("com", "net", "org", "example");
  }

  @Test
  void testFailure_runInEmptyAndTest() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                getParamsMap(
                    "runInEmpty", "",
                    "forEachTestTld", "")));
  }

  @Test
  void testFailure_runInEmptyAndReal() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                getParamsMap(
                    "runInEmpty", "",
                    "forEachRealTld", "")));
  }

  @Test
  void testFailure_runInEmptyAndExclude() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            run(
                getParamsMap(
                    "runInEmpty", "",
                    "exclude", "foo")));
  }

  @Test
  void testSuccess_additionalArgsFlowThroughToPostParams() {
    run(getParamsMap("forEachTestTld", "", "newkey", "newval"));
    assertTasksEnqueued(QUEUE,
        new TaskMatcher().url("/the/servlet").param("newkey", "newval"));
  }

  @Test
  void testSuccess_returnHttpResponse() {
    run(getParamsMap("forEachRealTld", "", "endpoint", "/the/servlet"));

    List<TaskStateInfo> taskList =
        LocalTaskQueueTestConfig.getLocalTaskQueue().getQueueStateInfo().get(QUEUE).getTaskInfo();

    assertThat(taskList).hasSize(3);
    String expectedResponse = String.format(
        "OK: Launched the following 3 tasks in queue the-queue\n"
            + "- Task: '%s', tld: 'com', endpoint: '/the/servlet'\n"
            + "- Task: '%s', tld: 'net', endpoint: '/the/servlet'\n"
            + "- Task: '%s', tld: 'org', endpoint: '/the/servlet'\n",
        taskList.get(0).getTaskName(),
        taskList.get(1).getTaskName(),
        taskList.get(2).getTaskName());
    assertThat(response.getPayload()).isEqualTo(expectedResponse);
  }

  @Test
  void testSuccess_returnHttpResponse_runInEmpty() {
    run(getParamsMap("runInEmpty", "", "endpoint", "/the/servlet"));

    List<TaskStateInfo> taskList =
        LocalTaskQueueTestConfig.getLocalTaskQueue().getQueueStateInfo().get(QUEUE).getTaskInfo();

    assertThat(taskList).hasSize(1);
    String expectedResponse = String.format(
        "OK: Launched the following 1 tasks in queue the-queue\n"
            + "- Task: '%s', tld: '', endpoint: '/the/servlet'\n",
        taskList.get(0).getTaskName());
    assertThat(response.getPayload()).isEqualTo(expectedResponse);
  }
}
