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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.config.RegistryConfig.CANARY_HEADER;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.OidcToken;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.common.escape.Escaper;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.net.UrlEscapers;
import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;
import google.registry.config.CredentialModule.ApplicationDefaultCredential;
import google.registry.config.RegistryConfig.Config;
import google.registry.request.Action;
import google.registry.request.Action.Method;
import google.registry.request.Action.Service;
import google.registry.util.Clock;
import google.registry.util.CollectionUtils;
import google.registry.util.GoogleCredentialsBundle;
import google.registry.util.RegistryEnvironment;
import google.registry.util.Retrier;
import jakarta.inject.Inject;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.joda.time.Duration;

/** Utilities for dealing with Cloud Tasks. */
public class CloudTasksUtils implements Serializable {

  @Serial private static final long serialVersionUID = -7605156291755534069L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Random random = new Random();

  private final Retrier retrier;
  private final Clock clock;
  private final String projectId;
  private final String locationId;
  private final String oauthClientId;
  private final GoogleCredentialsBundle credential;
  private final SerializableCloudTasksClient client;

  @Inject
  public CloudTasksUtils(
      Retrier retrier,
      Clock clock,
      @Config("projectId") String projectId,
      @Config("locationId") String locationId,
      @Config("oauthClientId") String oauthClientId,
      // Note that this has to be a service account, due to limitations of the Cloud Tasks API.
      @ApplicationDefaultCredential GoogleCredentialsBundle credential,
      SerializableCloudTasksClient client) {
    this.retrier = retrier;
    this.clock = clock;
    this.projectId = projectId;
    this.locationId = locationId;
    this.oauthClientId = oauthClientId;
    this.credential = credential;
    this.client = client;
  }

  public Task enqueue(String queue, Task task) {
    return retrier.callWithRetry(
        () -> {
          logger.atInfo().log(
              "Enqueuing queue='%s' endpoint='%s'", queue, task.getHttpRequest().getUrl());
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
   * Converts a (possible) set of params into an HTTP request via the appropriate method.
   *
   * <p>For GET requests we add them on to the URL, and for POST requests we add them in the body of
   * the request.
   *
   * <p>The parameters {@code putHeadersFunction} and {@code setBodyFunction} are used so that this
   * method can be called with either an AppEngine HTTP request or a standard non-AppEngine HTTP
   * request. The two objects do not have the same methods, but both have ways of setting headers /
   * body.
   *
   * @return the resulting path (unchanged for POST requests, with params added for GET requests)
   */
  private static String processRequestParameters(
      String path,
      Method method,
      Multimap<String, String> params,
      BiConsumer<String, String> putHeadersFunction,
      Consumer<ByteString> setBodyFunction) {
    if (CollectionUtils.isNullOrEmpty(params)) {
      return path;
    }
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
    if (method.equals(Method.GET)) {
      return String.format("%s?%s", path, encodedParams);
    }
    putHeadersFunction.accept(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString());
    setBodyFunction.accept(ByteString.copyFrom(encodedParams, StandardCharsets.UTF_8));
    return path;
  }

  /**
   * Create a {@link Task} to be enqueued.
   *
   * <p>This uses the standard Cloud Tasks auth format to create and send an OIDC ID token with the
   * default service account as the principal. That account must have permission to submit tasks to
   * Cloud Tasks.
   *
   * <p>The caller of this method is responsible for passing in the appropriate service based on the
   * runtime (GAE/GKE). Use the overload that takes an action class if possible.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request.
   * @param service the GAE/GKE service to route the request to.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  protected Task createTask(
      String path, Method method, Service service, Multimap<String, String> params) {
    checkArgument(
        path != null && !path.isEmpty() && path.charAt(0) == '/',
        "The path must start with a '/'.");
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder().setHttpMethod(HttpMethod.valueOf(method.name()));
    path =
        processRequestParameters(
            path, method, params, requestBuilder::putHeaders, requestBuilder::setBody);
    OidcToken.Builder oidcTokenBuilder =
        OidcToken.newBuilder()
            .setServiceAccountEmail(credential.serviceAccount())
            .setAudience(oauthClientId);
    requestBuilder.setOidcToken(oidcTokenBuilder.build());
    String totalPath = String.format("%s%s", service.getServiceUrl(), path);
    requestBuilder.setUrl(totalPath);
    if (RegistryEnvironment.isCanary()) {
      requestBuilder.putHeaders(CANARY_HEADER, "true");
    }
    return Task.newBuilder().setHttpRequest(requestBuilder.build()).build();
  }

  /**
   * Create a {@link Task} to be enqueued.
   *
   * <p>This uses the standard Cloud Tasks auth format to create and send an OIDC ID token with the
   * default service account as the principal. That account must have permission to submit tasks to
   * Cloud Tasks.
   *
   * <p>Prefer this overload over the one where the path and service are explicitly defined, as this
   * class will automatically determine the service to use based on the action and the runtime.
   *
   * @param actionClazz the action class to run, must be annotated with {@link Action}.
   * @param method the HTTP method to be used for the request.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  public Task createTask(
      Class<? extends Runnable> actionClazz, Method method, Multimap<String, String> params) {
    Action action = actionClazz.getAnnotation(Action.class);
    checkArgument(
        action != null,
        "Action class %s is not annotated with @Action",
        actionClazz.getSimpleName());
    String path = action.path();
    ImmutableSet<Method> allowedMethods = ImmutableSet.copyOf(action.method());
    checkArgument(
        allowedMethods.contains(method),
        "Method %s is not allowed for action %s. Allowed methods are %s",
        method,
        actionClazz.getSimpleName(),
        allowedMethods);
    Service service =
        RegistryEnvironment.isOnJetty() ? Action.ServiceGetter.get(action) : action.service();
    return createTask(path, method, service, params);
  }

  /**
   * Create a {@link Task} to be enqueued with a random delay up to {@code jitterSeconds}.
   *
   * <p>The caller of this method is responsible for passing in the appropriate service based on the
   * runtime (GAE/GKE). Use the overload that takes an action class if possible.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request.
   * @param service the GAE/GKE service to route the request to.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @param jitterSeconds the number of seconds that a task is randomly delayed up to.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  public Task createTaskWithJitter(
      String path,
      Method method,
      Service service,
      Multimap<String, String> params,
      Optional<Integer> jitterSeconds) {
    if (jitterSeconds.isEmpty() || jitterSeconds.get() <= 0) {
      return createTask(path, method, service, params);
    }
    return createTaskWithDelay(
        path,
        method,
        service,
        params,
        Duration.millis(random.nextInt((int) SECONDS.toMillis(jitterSeconds.get()))));
  }

  /**
   * Create a {@link Task} to be enqueued with a random delay up to {@code jitterSeconds}.
   *
   * <p>Prefer this overload over the one where the path and service are explicitly defined, as this
   * class will automatically determine the service to use based on the action and the runtime.
   *
   * @param actionClazz the action class to run, must be annotated with {@link Action}.
   * @param method the HTTP method to be used for the request.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @param jitterSeconds the number of seconds that a task is randomly delayed up to.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  public Task createTaskWithJitter(
      Class<? extends Runnable> actionClazz,
      Method method,
      Multimap<String, String> params,
      Optional<Integer> jitterSeconds) {
    Action action = getAction(actionClazz);
    checkState(
        action != null,
        "Action class %s is not annotated with @Action",
        actionClazz.getSimpleName());
    String path = action.path();
    Service service =
        RegistryEnvironment.isOnJetty() ? Action.ServiceGetter.get(action) : action.service();
    return createTaskWithJitter(path, method, service, params, jitterSeconds);
  }

  /**
   * Create a {@link Task} to be enqueued with delay of {@code duration}.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request.
   * @param service the GAE/GKE service to route the request to.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @param delay the amount of time that a task needs to be delayed for.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  private Task createTaskWithDelay(
      String path,
      Method method,
      Service service,
      Multimap<String, String> params,
      Duration delay) {
    if (delay.isEqual(Duration.ZERO)) {
      return createTask(path, method, service, params);
    }
    checkArgument(delay.isLongerThan(Duration.ZERO), "Negative duration is not supported.");
    return Task.newBuilder(createTask(path, method, service, params))
        .setScheduleTime(Timestamps.fromMillis(clock.nowUtc().plus(delay).getMillis()))
        .build();
  }

  /**
   * Create a {@link Task} to be enqueued with delay of {@code duration}.
   *
   * <p>Prefer this overload over the one where the path and service are explicitly defined, as this
   * class will automatically determine the service to use based on the action and the runtime.
   *
   * @param actionClazz the action class to run, must be annotated with {@link Action}.
   * @param method the HTTP method to be used for the request.
   * @param params a multimap of URL query parameters. Duplicate keys are saved as is, and it is up
   *     to the server to process the duplicate keys.
   * @param delay the amount of time that a task needs to be delayed for.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  public Task createTaskWithDelay(
      Class<? extends Runnable> actionClazz,
      Method method,
      Multimap<String, String> params,
      Duration delay) {
    Action action = getAction(actionClazz);
    String path = action.path();
    Service service =
        RegistryEnvironment.isOnJetty() ? Action.ServiceGetter.get(action) : action.service();
    return createTaskWithDelay(path, method, service, params, delay);
  }

  private static Action getAction(Class<? extends Runnable> actionClazz) {
    Action action = actionClazz.getAnnotation(Action.class);
    checkState(
        action != null,
        "Action class %s is not annotated with @Action",
        actionClazz.getSimpleName());
    return action;
  }

  public abstract static class SerializableCloudTasksClient implements Serializable {

    @Serial private static final long serialVersionUID = 7872861868968535498L;

    public abstract Task enqueue(String projectId, String locationId, String queueName, Task task);
  }

  public static class GcpCloudTasksClient extends SerializableCloudTasksClient {

    @Serial private static final long serialVersionUID = -5959253033129154037L;

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
