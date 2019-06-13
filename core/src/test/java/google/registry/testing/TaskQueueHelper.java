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

package google.registry.testing;

import static com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig.getLocalTaskQueue;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getFirst;
import static com.google.common.collect.Multisets.containsOccurrences;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assert_;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

import com.google.appengine.api.taskqueue.dev.QueueStateInfo;
import com.google.appengine.api.taskqueue.dev.QueueStateInfo.HeaderWrapper;
import com.google.appengine.api.taskqueue.dev.QueueStateInfo.TaskStateInfo;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import google.registry.dns.DnsConstants;
import google.registry.model.ImmutableObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.joda.time.Duration;

/** Static utility functions for testing task queues. */
public class TaskQueueHelper {

  /**
   * Matcher to match against the tasks in the task queue. Fields that aren't set are not compared.
   */
  public static class TaskMatcher implements Predicate<TaskStateInfo> {

    private final MatchableTaskInfo expected;

    public TaskMatcher() {
      expected = new MatchableTaskInfo();
    }

    /**
     * Constructor to create a TaskMatcher that should exactly match an existing TaskStateInfo.
     *
     * This is useful for checking that a pre-existing task as returned by TaskStateInfo is still
     * in the queue; we can't just directly compare the lists of TaskStateInfos because they have
     * no equals() override and there's no guarantee that reference equality is sufficient.
     */
    private TaskMatcher(TaskStateInfo taskStateInfo) {
      expected = new MatchableTaskInfo(taskStateInfo);
    }

    public TaskMatcher taskName(String taskName) {
      expected.taskName = taskName;
      return this;
    }

    public TaskMatcher url(String url) {
      expected.url = url;
      return this;
    }

    /**
     * Sets the HTTP method to match against.  WARNING: due to b/38459667, pull queue tasks will
     * report "POST" as their method.
     */
    public TaskMatcher method(String method) {
      expected.method = method;
      return this;
    }

    public TaskMatcher payload(String payload) {
      checkState(expected.params.isEmpty(), "Cannot add a payload to a TaskMatcher with params");
      expected.payload = payload;
      return this;
    }

    public TaskMatcher tag(String tag) {
      expected.tag = tag;
      return this;
    }

    public TaskMatcher header(String name, String value) {
      // Lowercase for case-insensitive comparison.
      expected.headers.put(Ascii.toLowerCase(name), value);
      return this;
    }

    public TaskMatcher param(String key, String value) {
      checkState(expected.payload == null, "Cannot add params to a TaskMatcher with a payload");
      checkNotNull(value, "Test error: A task can never have a null value, so don't assert it");
      expected.params.put(key, value);
      return this;
    }

    public TaskMatcher etaDelta(Duration lowerBound, Duration upperBound) {
      checkState(!lowerBound.isShorterThan(Duration.ZERO), "lowerBound must be non-negative");
      checkState(
          upperBound.isLongerThan(lowerBound), "upperBound must be greater than lowerBound");
      expected.etaDeltaLowerBound = lowerBound.getStandardSeconds();
      expected.etaDeltaUpperBound = upperBound.getStandardSeconds();
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
     * Returns true if the fields set on the current object match the given task info. This is not
     * quite the same contract as {@link #equals}, since it will ignore null fields.
     *
     * <p>Match fails if any headers or params expected on the TaskMatcher are not found on the
     * TaskStateInfo. Note that the inverse is not true (i.e. there may be extra headers on the
     * TaskStateInfo).
     */
    @Override
    public boolean test(@Nonnull TaskStateInfo info) {
      MatchableTaskInfo actual = new MatchableTaskInfo(info);
      return (expected.taskName == null || Objects.equals(expected.taskName, actual.taskName))
          && (expected.url == null || Objects.equals(expected.url, actual.url))
          && (expected.method == null || Objects.equals(expected.method, actual.method))
          && (expected.payload == null || Objects.equals(expected.payload, actual.payload))
          && (expected.tag == null || Objects.equals(expected.tag, actual.tag))
          && (expected.etaDeltaLowerBound == null
              || expected.etaDeltaLowerBound <= actual.etaDelta)
          && (expected.etaDeltaUpperBound == null
              || expected.etaDeltaUpperBound >= actual.etaDelta)
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

  /** Returns the info object for the provided queue name. */
  public static QueueStateInfo getQueueInfo(String queueName) {
    return getLocalTaskQueue().getQueueStateInfo().get(queueName);
  }

  /**
   * Ensures that the tasks in the named queue are exactly those with the expected property
   * values after being transformed with the provided property getter function.
   */
  public static void assertTasksEnqueuedWithProperty(
      String queueName,
      Function<TaskStateInfo, String> propertyGetter,
      String... expectedTaskProperties) {
    // Ordering is irrelevant but duplicates should be considered independently.
    assertThat(getQueueInfo(queueName).getTaskInfo().stream().map(propertyGetter))
        .containsExactly((Object[]) expectedTaskProperties);
  }

  /** Ensures that the tasks in the named queue are exactly those with the expected names. */
  public static void assertTasksEnqueued(String queueName, String... expectedTaskNames) {
    Function<TaskStateInfo, String> nameGetter = TaskStateInfo::getTaskName;
    assertTasksEnqueuedWithProperty(queueName, nameGetter, expectedTaskNames);
  }

  public static void assertTasksEnqueued(String queueName, Iterable<TaskStateInfo> taskStateInfos) {
    ImmutableList.Builder<TaskMatcher> taskMatchers = new ImmutableList.Builder<>();
    for (TaskStateInfo taskStateInfo : taskStateInfos) {
      taskMatchers.add(new TaskMatcher(taskStateInfo));
    }
    assertTasksEnqueued(queueName, taskMatchers.build());
  }

  /**
   * Ensures that the only tasks in the named queue are exactly those that match the expected
   * matchers.
   */
  public static void assertTasksEnqueued(String queueName, TaskMatcher... taskMatchers) {
    assertTasksEnqueued(queueName, Arrays.asList(taskMatchers));
  }

  /**
   * Ensures that the only tasks in the named queue are exactly those that match the expected
   * matchers.
   */
  public static void assertTasksEnqueued(String queueName, Collection<TaskMatcher> taskMatchers) {
    QueueStateInfo qsi = getQueueInfo(queueName);
    assertThat(qsi.getTaskInfo()).hasSize(taskMatchers.size());
    List<TaskStateInfo> taskInfos = new ArrayList<>(qsi.getTaskInfo());
    for (final TaskMatcher taskMatcher : taskMatchers) {
      try {
        taskInfos.remove(taskInfos.stream().filter(taskMatcher).findFirst().get());
      } catch (NoSuchElementException e) {
        final Map<String, Object> taskMatcherMap = taskMatcher.expected.toMap();
        assert_()
            .fail(
                "Task not found in queue %s:\n\n%s\n\nPotential candidate match diffs:\n\n%s",
                queueName,
                taskMatcher,
                taskInfos
                    .stream()
                    .map(
                        input ->
                            prettyPrintEntityDeepDiff(
                                taskMatcherMap,
                                Maps.filterKeys(
                                    new MatchableTaskInfo(input).toMap(),
                                    in(taskMatcherMap.keySet()))))
                    .collect(joining("\n")));
      }
    }
  }

  public static ImmutableList<ImmutableMultimap<String, String>> getQueuedParams(String queueName) {
    return getQueueInfo(queueName)
        .getTaskInfo()
        .stream()
        .map(MatchableTaskInfo::new)
        .map(taskInfo -> ImmutableMultimap.copyOf(taskInfo.params))
        .collect(toImmutableList());
  }

  /** Empties the task queue. */
  public static void clearTaskQueue(String queueName) {
    getLocalTaskQueue().flushQueue(queueName);
  }

  /** Asserts at least one task exists in {@code queue}. */
  public static void assertAtLeastOneTaskIsEnqueued(String queue) {
    assertThat(getQueueInfo(queue).getCountTasks()).isGreaterThan(0);
  }

  /** Ensures that the named queue contains no tasks. */
  public static void assertNoTasksEnqueued(String ... queueNames) {
    for (String queueName : queueNames) {
      assertThat(getQueueInfo(queueName).getCountTasks()).isEqualTo(0);
    }
  }

  /** Returns the value for the param on a task info, or empty if it is missing. */
  private static String getParamFromTaskInfo(TaskStateInfo taskInfo, String paramName) {
    return getFirst(UriParameters.parse(taskInfo.getBody()).get(paramName), "");
  }

  /** Ensures that the DNS queue tasks are exactly those for the expected target names. */
  public static void assertDnsTasksEnqueued(String... expectedTaskTargetNames) {
    assertTasksEnqueuedWithProperty(
        DnsConstants.DNS_PULL_QUEUE_NAME,
        taskStateInfo -> getParamFromTaskInfo(taskStateInfo, DnsConstants.DNS_TARGET_NAME_PARAM),
        expectedTaskTargetNames);
  }

  /** Ensures that the DNS queue does not contain any tasks. */
  public static void assertNoDnsTasksEnqueued() {
    assertNoTasksEnqueued(DnsConstants.DNS_PULL_QUEUE_NAME);
  }

  /** An adapter to clean up a {@link TaskStateInfo} for ease of matching. */
  private static class MatchableTaskInfo extends ImmutableObject {

    String taskName;
    String method;
    String url;
    String payload;
    String tag;
    Double etaDelta;
    Long etaDeltaLowerBound;
    Long etaDeltaUpperBound;
    Multimap<String, String> headers = ArrayListMultimap.create();
    Multimap<String, String> params = ArrayListMultimap.create();

    MatchableTaskInfo() {}

    MatchableTaskInfo(TaskStateInfo info) {
      URI uri;
      try {
        uri = new URI(info.getUrl());
      } catch (java.net.URISyntaxException e) {
        throw new IllegalArgumentException(e);
      }
      this.taskName = info.getTaskName();
      this.method = info.getMethod();
      this.url = uri.getPath();
      this.payload = info.getBody();
      this.etaDelta = info.getEtaDelta();
      if (info.getTagAsBytes() != null) {
        this.tag = new String(info.getTagAsBytes(), UTF_8);
      }
      ImmutableMultimap.Builder<String, String> headerBuilder = new ImmutableMultimap.Builder<>();
      for (HeaderWrapper header : info.getHeaders()) {
        // Lowercase header name for comparison since HTTP
        // header names are case-insensitive.
        headerBuilder.put(Ascii.toLowerCase(header.getKey()), header.getValue());
      }
      this.headers = headerBuilder.build();
      ImmutableMultimap.Builder<String, String> inputParams = new ImmutableMultimap.Builder<>();
      String query = uri.getQuery();
      if (query != null) {
        inputParams.putAll(UriParameters.parse(query));
      }
      boolean hasFormDataContentType =
          headers.containsEntry(
              Ascii.toLowerCase(HttpHeaders.CONTENT_TYPE), MediaType.FORM_DATA.toString());
      // Try decoding the body as a parameter map if it either has the "x-www-form-urlencoded"
      // content type, or if it's a POST or PULL task (in which case parameters should be encoded
      // into the body automatically upon being enqueued).  Note that pull queue tasks also report
      // "POST" as their method (which is misleading - see b/38459667) so we just check for "POST".
      if (hasFormDataContentType || "POST".equals(this.method)) {
        // Note that UriParameters.parse() does not throw an IAE on a bad query string (e.g. one
        // where parameters are not properly URL-encoded); it always does a best-effort parse.
        inputParams.putAll(UriParameters.parse(info.getBody()));
      }
      this.params = inputParams.build();
    }

    public Map<String, Object> toMap() {
      Map<String, Object> builder = new HashMap<>();
      builder.put("taskName", taskName);
      builder.put("url", url);
      builder.put("method", method);
      builder.put("headers", headers.asMap());
      builder.put("params", params.asMap());
      builder.put("payload", payload);
      builder.put("tag", tag);
      builder.put("etaDelta", etaDelta);
      builder.put("etaDeltaLowerBound", etaDeltaLowerBound);
      builder.put("etaDeltaUpperBound", etaDeltaUpperBound);
      return Maps.filterValues(builder, not(in(asList(null, "", Collections.EMPTY_MAP))));
    }
  }
}
