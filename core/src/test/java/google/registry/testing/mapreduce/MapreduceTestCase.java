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

package google.registry.testing.mapreduce;

import static google.registry.config.RegistryConfig.getEppResourceIndexBucketCount;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.appengine.api.blobstore.dev.LocalBlobstoreService;
import com.google.appengine.api.modules.ModulesService;
import com.google.appengine.api.taskqueue.dev.LocalTaskQueue;
import com.google.appengine.api.taskqueue.dev.QueueStateInfo;
import com.google.appengine.api.taskqueue.dev.QueueStateInfo.HeaderWrapper;
import com.google.appengine.tools.development.ApiProxyLocal;
import com.google.appengine.tools.development.testing.LocalTaskQueueTestConfig;
import com.google.appengine.tools.mapreduce.MapReduceServlet;
import com.google.appengine.tools.pipeline.impl.servlets.PipelineServlet;
import com.google.appengine.tools.pipeline.impl.servlets.TaskHandler;
import com.google.apphosting.api.ApiProxy;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.flogger.FluentLogger;
import google.registry.mapreduce.MapreduceRunner;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.AppEngineServiceUtilsImpl;
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Base test class for mapreduces.
 *
 * <p>Adapted from EndToEndTestCase with some modifications that allow it to work with Nomulus, most
 * notably inside knowledge of our routing paths and our Datastore/Task Queue configurations.
 *
 * <p>See
 * https://github.com/GoogleCloudPlatform/appengine-mapreduce/blob/master/java/src/test/java/com/google/appengine/tools/mapreduce/EndToEndTestCase.java
 *
 * @param <T> The type of the Action class that implements the mapreduce.
 */
public abstract class MapreduceTestCase<T> {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected T action;

  private final MapReduceServlet mrServlet = new MapReduceServlet();
  private final PipelineServlet pipelineServlet = new PipelineServlet();
  private LocalTaskQueue taskQueue;

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withLocalModules().withTaskQueue().build();

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  AppEngineServiceUtils appEngineServiceUtils;

  @Mock ModulesService modulesService;

  @Before
  public void setUp() {
    taskQueue = LocalTaskQueueTestConfig.getLocalTaskQueue();
    ApiProxyLocal proxy = (ApiProxyLocal) ApiProxy.getDelegate();
    // Creating files is not allowed in some test execution environments, so don't.
    proxy.setProperty(LocalBlobstoreService.NO_STORAGE_PROPERTY, "true");
    appEngineServiceUtils = new AppEngineServiceUtilsImpl(modulesService);
    when(modulesService.getVersionHostname("backend", null))
        .thenReturn("version.backend.projectid.appspot.com");
  }

  protected MapreduceRunner makeDefaultRunner() {
    return new MapreduceRunner(
        Optional.of(getEppResourceIndexBucketCount()), Optional.of(1), appEngineServiceUtils);
  }

  protected List<QueueStateInfo.TaskStateInfo> getTasks(String queueName) {
    return taskQueue.getQueueStateInfo().get(queueName).getTaskInfo();
  }

  protected void executeTask(String queueName, QueueStateInfo.TaskStateInfo taskStateInfo)
      throws Exception {
    logger.atFine().log(
        "Executing task %s with URL %s", taskStateInfo.getTaskName(), taskStateInfo.getUrl());
    // Hack to allow for deferred tasks. Exploits knowing how they work.
    if (taskStateInfo.getUrl().endsWith("__deferred__")) {
      ObjectInputStream oin =
          new ObjectInputStream(new ByteArrayInputStream(taskStateInfo.getBodyAsBytes()));
      Runnable object = (Runnable) oin.readObject();
      object.run();
      return;
    }
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);

    // Strip off routing paths that are handled in web.xml in non-test scenarios.
    String pathInfo = taskStateInfo.getUrl();
    if (pathInfo.startsWith("/_dr/mapreduce/")) {
      pathInfo = pathInfo.replace("/_dr/mapreduce", "");
    } else if (pathInfo.startsWith("/mapreduce/")) {
        pathInfo = pathInfo.replace("/mapreduce", "");
    } else if (pathInfo.startsWith("/")) {
      pathInfo = pathInfo.replace("/_ah/", "");
      pathInfo = pathInfo.substring(pathInfo.indexOf('/'));
    } else {
      pathInfo = "/" + pathInfo;
    }
    when(request.getPathInfo()).thenReturn(pathInfo);
    when(request.getHeader("X-AppEngine-QueueName")).thenReturn(queueName);
    when(request.getHeader("X-AppEngine-TaskName")).thenReturn(taskStateInfo.getTaskName());
    // Pipeline looks at this header but uses the value only for diagnostic messages
    when(request.getIntHeader(TaskHandler.TASK_RETRY_COUNT_HEADER)).thenReturn(-1);
    for (HeaderWrapper header : taskStateInfo.getHeaders()) {
      int value = parseAsQuotedInt(header.getValue());
      when(request.getIntHeader(header.getKey())).thenReturn(value);
      logger.atFine().log("header: %s=%s", header.getKey(), header.getValue());
      when(request.getHeader(header.getKey())).thenReturn(header.getValue());
    }

    Map<String, String> parameters = decodeParameters(taskStateInfo.getBody());
    for (String name : parameters.keySet()) {
      when(request.getParameter(name)).thenReturn(parameters.get(name));
    }
    when(request.getParameterNames()).thenReturn(Collections.enumeration(parameters.keySet()));

    if (taskStateInfo.getMethod().equals("POST")) {
      if (taskStateInfo.getUrl().startsWith(PipelineServlet.BASE_URL)) {
        pipelineServlet.doPost(request, response);
      } else {
        mrServlet.doPost(request, response);
      }
    } else {
      throw new UnsupportedOperationException();
    }
  }

  private int parseAsQuotedInt(String str) {
    try {
      return Integer.parseInt(CharMatcher.is('"').trimFrom(str));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Executes tasks in the mapreduce queue until all are finished.
   *
   * <p>If you are mocking a clock in your tests, use the
   * {@link #executeTasksUntilEmpty(String, FakeClock)} version instead.
   */
  protected void executeTasksUntilEmpty(String queueName) throws Exception {
    executeTasksUntilEmpty(queueName, null);
  }

  /**
   * Executes mapreduce tasks, increment the clock between each task.
   *
   * <p>Incrementing the clock between tasks is important if tasks have transactions inside the
   * mapper or reducer, which don't have access to the fake clock.
   */
  protected void executeTasksUntilEmpty(String queueName, @Nullable FakeClock clock)
      throws Exception {
    executeTasks(queueName, clock, Optional.empty());
  }

  /**
   * Executes mapreduce tasks, increment the clock between each task.
   *
   * <p>Incrementing the clock between tasks is important if tasks have transactions inside the
   * mapper or reducer, which don't have access to the fake clock.
   *
   * <p>The maxTasks parameter determines how many tasks (at most) will be run. If maxTasks is
   * absent(), all tasks are run until the queue is empty. If maxTasks is zero, no tasks are run.
   */
  protected void executeTasks(
      String queueName, @Nullable FakeClock clock, Optional<Integer> maxTasks) throws Exception {
    for (int numTasksDeleted = 0;
        !maxTasks.isPresent() || (numTasksDeleted < maxTasks.get());
        numTasksDeleted++) {
      ofy().clearSessionCache();
      // We have to re-acquire task list every time, because local implementation returns a copy.
      List<QueueStateInfo.TaskStateInfo> taskInfo =
          taskQueue.getQueueStateInfo().get(queueName).getTaskInfo();
      if (taskInfo.isEmpty()) {
        break;
      }
      QueueStateInfo.TaskStateInfo taskStateInfo = taskInfo.get(0);
      taskQueue.deleteTask(queueName, taskStateInfo.getTaskName());
      executeTask(queueName, taskStateInfo);
      if (clock != null) {
        clock.advanceOneMilli();
      }
    }
  }

  // Sadly there's no way to parse query string with JDK. This is a good enough approximation.
  private static Map<String, String> decodeParameters(String requestBody)
      throws UnsupportedEncodingException {
    Map<String, String> result = new HashMap<>();

    Iterable<String> params = Splitter.on('&').split(requestBody);
    for (String param : params) {
      List<String> pair = Splitter.on('=').splitToList(param);
      String name = pair.get(0);
      String value = URLDecoder.decode(pair.get(1), "UTF-8");
      if (result.containsKey(name)) {
        throw new IllegalArgumentException("Duplicate parameter: " + requestBody);
      }
      result.put(name, value);
    }

    return result;
  }
}
