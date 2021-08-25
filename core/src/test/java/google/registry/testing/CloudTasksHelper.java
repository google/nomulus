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

package google.registry.testing;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Multisets.containsOccurrences;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.truth.Truth8;
import google.registry.model.ImmutableObject;
import google.registry.util.CloudTasksUtils;
import google.registry.util.Retrier;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/**
 * Static utility functions for testing task queues.
 *
 * <p>This class is mostly derived from {@link TaskQueueHelper}. It does not implement as many
 * helper methods because we have not yet encountered all the use cases with Cloud Tasks. As more
 * and more Task Queue API usage is migrated to Cloud Tasks we may replicate more methods from the
 * latter.
 */
public class CloudTasksHelper {

  private static final String PROJECT_ID = "test-project";
  private static final String LOCATION_ID = "test-location";

  private final Retrier retrier = new Retrier(new FakeSleeper(new FakeClock()), 1);
  private final LinkedListMultimap<String, Task> testTasks = LinkedListMultimap.create();
  private final CloudTasksClient mockClient = mock(CloudTasksClient.class);
  private final CloudTasksUtils cloudTasksUtils =
      new CloudTasksUtils(retrier, PROJECT_ID, LOCATION_ID, () -> mockClient);

  public CloudTasksHelper() {
    when(mockClient.createTask(any(QueueName.class), any(Task.class)))
        .thenAnswer(
            invocation -> {
              QueueName queue = invocation.getArgument(0);
              Task task = invocation.getArgument(1);
              if (task.getName().isEmpty()) {
                task = task.toBuilder().setName(String.format("test-%d", testTasks.size())).build();
              }
              testTasks.put(queue.getQueue(), task);
              return task;
            });
  }

  public CloudTasksUtils getTestCloudTasksUtils() {
    return cloudTasksUtils;
  }

  public List<Task> getTestTasksFor(String queue) {
    return testTasks.get(queue);
  }

  /**
   * Ensures that the tasks in the named queue are exactly those with the expected property values
   * after being transformed with the provided property getter function.
   */
  public void assertTasksEnqueuedWithProperty(
      String queueName, Function<Task, String> propertyGetter, String... expectedTaskProperties) {
    // Ordering is irrelevant but duplicates should be considered independently.
    Truth8.assertThat(getTestTasksFor(queueName).stream().map(propertyGetter))
        .containsExactly((Object[]) expectedTaskProperties);
  }

  /** Ensures that the tasks in the named queue are exactly those with the expected names. */
  public void assertTasksEnqueued(String queueName, String... expectedTaskNames) {
    Function<Task, String> nameGetter = Task::getName;
    assertTasksEnqueuedWithProperty(queueName, nameGetter, expectedTaskNames);
  }

  /**
   * Ensures that the only tasks in the named queue are exactly those that match the expected
   * matchers.
   */
  public void assertTasksEnqueued(String queueName, TaskMatcher... taskMatchers) {
    assertTasksEnqueued(queueName, asList(taskMatchers));
  }

  /** Ensures that the named queue contains no tasks. */
  public void assertNoTasksEnqueued(String... queueNames) {
    for (String queueName : queueNames) {
      assertThat(getTestTasksFor(queueName)).isEmpty();
    }
  }

  /**
   * Ensures that the only tasks in the named queue are exactly those that match the expected
   * matchers.
   */
  public void assertTasksEnqueued(String queueName, Collection<TaskMatcher> taskMatchers) {
    List<Task> tasks = getTestTasksFor(queueName);
    assertThat(tasks.size()).isEqualTo(taskMatchers.size());
    for (final TaskMatcher taskMatcher : taskMatchers) {
      try {
        tasks.remove(tasks.stream().filter(taskMatcher).findFirst().get());
      } catch (NoSuchElementException e) {
        final Map<String, Object> taskMatcherMap = taskMatcher.expected.toMap();
        assertWithMessage(
                "Task not found in queue %s:\n\n%s\n\nPotential candidate match diffs:\n\n%s",
                queueName,
                taskMatcher,
                tasks.stream()
                    .map(
                        input ->
                            prettyPrintEntityDeepDiff(
                                taskMatcherMap,
                                Maps.filterKeys(
                                    new MatchableTask(input).toMap(), in(taskMatcherMap.keySet()))))
                    .collect(joining("\n")))
            .fail();
      }
    }
  }

  /** An adapter to clean up a {@link Task} for ease of matching. */
  private static class MatchableTask extends ImmutableObject {

    String taskName;
    String service;
    HttpMethod method;
    String url;
    Multimap<String, String> headers = ArrayListMultimap.create();
    Multimap<String, String> params = ArrayListMultimap.create();

    MatchableTask() {}

    MatchableTask(Task task) {
      URI uri;
      try {
        // Construct a fake full URI for parsing purpose. The relative URI must start with a slash.
        uri =
            new URI(
                String.format(
                    "https://nomulus.foo%s", task.getAppEngineHttpRequest().getRelativeUri()));
      } catch (java.net.URISyntaxException e) {
        throw new IllegalArgumentException(e);
      }
      taskName = task.getName();
      service =
          Ascii.toLowerCase(task.getAppEngineHttpRequest().getAppEngineRouting().getService());
      method = task.getAppEngineHttpRequest().getHttpMethod();
      url = uri.getPath();
      ImmutableMultimap.Builder<String, String> headerBuilder = new ImmutableMultimap.Builder<>();
      task.getAppEngineHttpRequest()
          .getHeadersMap()
          .forEach(
              (key, value) -> {
                // Lowercase header name for comparison since HTTP header names are
                // case-insensitive.
                headerBuilder.put(Ascii.toLowerCase(key), value);
              });
      headers = headerBuilder.build();
      ImmutableMultimap.Builder<String, String> paramBuilder = new ImmutableMultimap.Builder<>();
      String query = null;
      if (method == HttpMethod.GET) {
        query = uri.getQuery();
      } else if (method == HttpMethod.POST) {
        assertThat(
                headers.containsEntry(
                    Ascii.toLowerCase(HttpHeaders.CONTENT_TYPE), MediaType.FORM_DATA.toString()))
            .isTrue();
        query = task.getAppEngineHttpRequest().getBody().toString(StandardCharsets.UTF_8);
      }
      if (query != null) {
        // Note that UriParameters.parse() does not throw an IAE on a bad query string (e.g. one
        // where parameters are not properly URL-encoded); it always does a best-effort parse.
        paramBuilder.putAll(UriParameters.parse(query));
        params = paramBuilder.build();
      }
    }

    public Map<String, Object> toMap() {
      Map<String, Object> builder = new HashMap<>();
      builder.put("taskName", taskName);
      builder.put("method", method);
      builder.put("service", service);
      builder.put("url", url);
      builder.put("headers", headers);
      builder.put("params", params);
      return Maps.filterValues(builder, not(in(asList(null, "", Collections.EMPTY_MAP))));
    }
  }

  /**
   * Matcher to match against the tasks in the task queue. Fields that aren't set are not compared.
   */
  public static class TaskMatcher implements Predicate<Task> {

    private final MatchableTask expected;

    public TaskMatcher() {
      expected = new MatchableTask();
    }

    public TaskMatcher taskName(String taskName) {
      expected.taskName = taskName;
      return this;
    }

    public TaskMatcher url(String url) {
      expected.url = url;
      return this;
    }

    public TaskMatcher service(String service) {
      // Lowercase for case-insensitive comparison.
      expected.service = Ascii.toLowerCase(service);
      return this;
    }

    public TaskMatcher method(HttpMethod method) {
      expected.method = method;
      return this;
    }

    public TaskMatcher header(String name, String value) {
      // Lowercase for case-insensitive comparison.
      expected.headers.put(Ascii.toLowerCase(name), value);
      return this;
    }

    public TaskMatcher param(String key, String value) {
      checkNotNull(value, "Test error: A param can never have a null value, so don't assert it");
      expected.params.put(key, value);
      return this;
    }

    /**
     * Returns {@code true} if there are not more occurrences in {@code sub} of each of its entries
     * than there are in {@code super}.
     */
    private static boolean containsEntries(
        Multimap<?, ?> superMultimap, Multimap<?, ?> subMultimap) {
      return containsOccurrences(
          ImmutableMultiset.copyOf(superMultimap.entries()),
          ImmutableMultiset.copyOf(subMultimap.entries()));
    }

    /**
     * Returns true if the fields set on the current object match the given task. This is not quite
     * the same contract as {@link #equals}, since it will ignore null fields.
     *
     * <p>Match fails if any headers or params expected on the TaskMatcher are not found on the
     * Task. Note that the inverse is not true (i.e. there may be extra headers on the Task).
     */
    @Override
    public boolean test(@Nonnull Task task) {
      MatchableTask actual = new MatchableTask(task);
      return (expected.taskName == null || Objects.equals(expected.taskName, actual.taskName))
          && (expected.url == null || Objects.equals(expected.url, actual.url))
          && (expected.method == null || Objects.equals(expected.method, actual.method))
          && (expected.service == null || Objects.equals(expected.service, actual.service))
          && containsEntries(actual.params, expected.params)
          && containsEntries(actual.headers, expected.headers);
    }

    @Override
    public String toString() {
      return Joiner.on('\n')
          .withKeyValueSeparator(":\n")
          .join(
              Maps.transformValues(
                  expected.toMap(),
                  input -> "\t" + String.valueOf(input).replaceAll("\n", "\n\t")));
    }
  }
}
