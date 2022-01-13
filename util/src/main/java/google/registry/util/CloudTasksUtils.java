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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.tasks.v2.AppEngineHttpRequest;
import com.google.cloud.tasks.v2.AppEngineRouting;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.common.escape.Escaper;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.net.UrlEscapers;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/** Utilities for dealing with Cloud Tasks. */
public class CloudTasksUtils implements Serializable {

  private static final long serialVersionUID = -7605156291755534069L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Random random = new Random();

  private final Retrier retrier;
  private final String projectId;
  private final String locationId;
  private final SerializableCloudTasksClient client;

  public CloudTasksUtils(
      Retrier retrier, String projectId, String locationId, SerializableCloudTasksClient client) {
    this.retrier = retrier;
    this.projectId = projectId;
    this.locationId = locationId;
    this.client = client;
  }

  public Task enqueue(String queue, Task task) {
    return retrier.callWithRetry(
        () -> {
          logger.atInfo().log(
              "Enqueuing queue='%s' endpoint='%s' service='%s'",
              queue,
              task.getAppEngineHttpRequest().getRelativeUri(),
              task.getAppEngineHttpRequest().getAppEngineRouting().getService());
          return client.enqueue(projectId, locationId, queue, task);
        },
        ApiException.class);
  }

  public ImmutableList<Task> enqueue(String queue, Iterable<Task> tasks) {
    return Streams.stream(tasks).map(task -> enqueue(queue, task)).collect(toImmutableList());
  }

  public ImmutableList<Task> enqueue(String queue, Task... tasks) {
    return enqueue(queue, Arrays.asList(tasks));
  }

  /**
   * Create a {@link Task} to be enqueued.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request, only GET and POST are supported.
   * @param service the App Engine service to route the request to. Note that with App Engine Task
   *     Queue API if no service is specified, the service which enqueues the task will be used to
   *     process the task. Cloud Tasks API does not support this feature so the service will always
   *     needs to be explicitly specified.
   * @param params a multi-map of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  private static Task createTask(
      String path, HttpMethod method, String service, Multimap<String, String> params) {
    checkArgument(
        path != null && !path.isEmpty() && path.charAt(0) == '/',
        "The path must start with a '/'.");
    checkArgument(
        method.equals(HttpMethod.GET) || method.equals(HttpMethod.POST),
        "HTTP method %s is used. Only GET and POST are allowed.",
        method);
    AppEngineHttpRequest.Builder requestBuilder =
        AppEngineHttpRequest.newBuilder()
            .setHttpMethod(method)
            .setAppEngineRouting(AppEngineRouting.newBuilder().setService(service).build());

    if (!CollectionUtils.isNullOrEmpty(params)) {
      Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
      String encodedParams =
          Joiner.on("&")
              .join(
                  params.entries().stream()
                      .map(
                          entry ->
                              String.format(
                                  "%s=%s",
                                  escaper.escape(entry.getKey()), escaper.escape(entry.getValue())))
                      .collect(toImmutableList()));
      if (method == HttpMethod.GET) {
        path = String.format("%s?%s", path, encodedParams);
      } else {
        requestBuilder
            .putHeaders(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
            .setBody(ByteString.copyFrom(encodedParams, StandardCharsets.UTF_8));
      }
    }
    requestBuilder.setRelativeUri(path);
    return Task.newBuilder().setAppEngineHttpRequest(requestBuilder.build()).build();
  }

  /**
   * Create a {@link Task} to be enqueued with a random delay up to {@code jitterSeconds}.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request, only GET and POST are supported.
   * @param service the App Engine service to route the request to. Note that with App Engine Task
   *     Queue API if no service is specified, the service which enqueues the task will be used to
   *     process the task. Cloud Tasks API does not support this feature so the service will always
   *     needs to be explicitly specified.
   * @param params a multi-map of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @param clock a source of time.
   * @param jitterSeconds the number of seconds that a task is randomly delayed up to.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  private static Task createTask(
      String path,
      HttpMethod method,
      String service,
      Multimap<String, String> params,
      Clock clock,
      Optional<Integer> jitterSeconds) {
    if (!jitterSeconds.isPresent() || jitterSeconds.get() <= 0) {
      return createTask(path, method, service, params);
    }
    Instant scheduleTime =
        Instant.ofEpochMilli(
            clock
                .nowUtc()
                .plusMillis(random.nextInt((int) SECONDS.toMillis(jitterSeconds.get())))
                .getMillis());
    return Task.newBuilder(createTask(path, method, service, params))
        .setScheduleTime(
            Timestamp.newBuilder()
                .setSeconds(scheduleTime.getEpochSecond())
                .setNanos(scheduleTime.getNano())
                .build())
        .build();
  }

  public static Task createPostTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.POST, service, params);
  }

  public static Task createGetTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.GET, service, params);
  }

  /**
   * Create a {@link Task} via HTTP.POST that will be randomly delayed up to {@code jitterSeconds}.
   */
  public static Task createPostTask(
      String path,
      String service,
      Multimap<String, String> params,
      Clock clock,
      Optional<Integer> jitterSeconds) {
    return createTask(path, HttpMethod.POST, service, params, clock, jitterSeconds);
  }

  /**
   * Create a {@link Task} via HTTP.GET that will be randomly delayed up to {@code jitterSeconds}.
   */
  public static Task createGetTask(
      String path,
      String service,
      Multimap<String, String> params,
      Clock clock,
      Optional<Integer> jitterSeconds) {
    return createTask(path, HttpMethod.GET, service, params, clock, jitterSeconds);
  }

  public abstract static class SerializableCloudTasksClient implements Serializable {
    public abstract Task enqueue(String projectId, String locationId, String queueName, Task task);
  }

  public static class GcpCloudTasksClient extends SerializableCloudTasksClient {

    private static final long serialVersionUID = -5959253033129154037L;

    // Use a supplier so that we can use try-with-resources with the client, which implements
    // Autocloseable.
    private final Supplier<CloudTasksClient> clientSupplier;

    public GcpCloudTasksClient(Supplier<CloudTasksClient> clientSupplier) {
      this.clientSupplier = clientSupplier;
    }

    @Override
    public Task enqueue(String projectId, String locationId, String queueName, Task task) {
      try (CloudTasksClient client = clientSupplier.get()) {
        return client.createTask(QueueName.of(projectId, locationId, queueName), task);
      }
    }
  }
}
