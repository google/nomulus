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

package google.registry.batch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.TestLogHandlerUtils.assertLogMessage;
import static org.joda.time.Duration.standardSeconds;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.collect.ImmutableSortedSet;
import google.registry.model.contact.ContactResource;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.util.CapturingLogHandler;
import google.registry.util.CloudTasksUtils;
import google.registry.util.JdkLoggerConfig;
import google.registry.util.Retrier;
import java.util.logging.Level;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link AsyncTaskEnqueuer}. */
@ExtendWith(MockitoExtension.class)
public class AsyncTaskEnqueuerTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private AsyncTaskEnqueuer asyncTaskEnqueuer;
  private final CapturingLogHandler logHandler = new CapturingLogHandler();
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-05-18T12:34:56Z"));
  private CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);

  @BeforeEach
  void beforeEach() {
    JdkLoggerConfig.getConfig(AsyncTaskEnqueuer.class).addHandler(logHandler);
    asyncTaskEnqueuer =
        createForTesting(cloudTasksHelper.getTestCloudTasksUtils(), clock, standardSeconds(90));
  }

  public static AsyncTaskEnqueuer createForTesting(
      CloudTasksUtils cloudTasksUtils, FakeClock clock, Duration asyncDeleteDelay) {
    return new AsyncTaskEnqueuer(
        getQueue(QUEUE_ASYNC_DELETE),
        getQueue(QUEUE_ASYNC_HOST_RENAME),
        asyncDeleteDelay,
        cloudTasksUtils,
        new Retrier(new FakeSleeper(clock), 1));
  }

  @Test
  void test_enqueueAsyncResave_success() {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(
        contact.createVKey(), clock.nowUtc(), clock.nowUtc().plusDays(5));
    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new CloudTasksHelper.TaskMatcher()
            .url(ResaveEntityAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, contact.createVKey().stringify())
            .param(PARAM_REQUESTED_TIME, clock.nowUtc().toString())
            .scheduleTime(clock.nowUtc().plus(Duration.standardDays(5))));
  }

  @Test
  void test_enqueueAsyncResave_multipleResaves() {
    ContactResource contact = persistActiveContact("jd23456");
    DateTime now = clock.nowUtc();
    asyncTaskEnqueuer.enqueueAsyncResave(
        contact.createVKey(),
        now,
        ImmutableSortedSet.of(now.plusHours(24), now.plusHours(50), now.plusHours(75)));
    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(ResaveEntityAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, contact.createVKey().stringify())
            .param(PARAM_REQUESTED_TIME, now.toString())
            .param(PARAM_RESAVE_TIMES, "2015-05-20T14:34:56.000Z,2015-05-21T15:34:56.000Z")
            .scheduleTime(clock.nowUtc().plus(Duration.standardHours(24))));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void test_enqueueAsyncResave_ignoresTasksTooFarIntoFuture() {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(
        contact.createVKey(), clock.nowUtc(), clock.nowUtc().plusDays(31));
    cloudTasksHelper.assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
    assertLogMessage(logHandler, Level.INFO, "Ignoring async re-save");
  }
}
