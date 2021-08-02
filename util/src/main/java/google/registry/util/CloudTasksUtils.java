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
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.inject.Provider;

/** Utilities for dealing with Cloud Tasks. */
public class CloudTasksUtils implements Serializable {

  private static final long serialVersionUID = -7605156291755534069L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Retrier retrier;
  private final String projectId;
  private final String locationId;
  private final Provider<CloudTasksClient> clientProvider;

  public CloudTasksUtils(
      Retrier retrier,
      String projectId,
      String locationId,
      Provider<CloudTasksClient> clientProvider) {
    this.retrier = retrier;
    this.projectId = projectId;
    this.locationId = locationId;
    this.clientProvider = clientProvider;
  }

  public Task enqueue(String queue, Task task) {
    return retrier.callWithRetry(
        () -> {
          logger.atInfo().log(
              "Enqueuing queue='%s' endpoint='%s' service='%s'",
              queue,
              task.getAppEngineHttpRequest().getRelativeUri(),
              task.getAppEngineHttpRequest().getAppEngineRouting().getService());
          try (CloudTasksClient client = clientProvider.get()) {
            return client.createTask(QueueName.of(projectId, locationId, queue), task);
          }
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
   * @param params A multi-map of URL query parameters. Duplicate keys are saved as is, and it is up
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
    AppEngineHttpRequest.Builder requestBuilder =
        AppEngineHttpRequest.newBuilder()
            .setHttpMethod(method)
            .setAppEngineRouting(AppEngineRouting.newBuilder().setService(service).build());
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
    } else if (method == HttpMethod.POST) {
      requestBuilder
          .putHeaders(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
          .setBody(ByteString.copyFrom(encodedParams, StandardCharsets.UTF_8));
    } else {
      throw new IllegalArgumentException(
          String.format("HTTP method %s is used. Only GET and POST are allowed.", method));
    }
    requestBuilder.setRelativeUri(path);
    return Task.newBuilder().setAppEngineHttpRequest(requestBuilder.build()).build();
  }

  public static Task createPostTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.POST, service, params);
  }

  public static Task createGetTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.GET, service, params);
  }
}
