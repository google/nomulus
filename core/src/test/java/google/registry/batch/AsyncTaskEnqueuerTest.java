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
import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_REQUESTED_TIME;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESAVE_TIMES;
import static google.registry.batch.AsyncTaskEnqueuer.PARAM_RESOURCE_KEY;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_DELETE;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.testing.TestLogHandlerUtils.assertLogMessage;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.flogger.LoggerConfig;
import com.googlecode.objectify.Key;
import google.registry.model.contact.ContactResource;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.CapturingLogHandler;
import google.registry.util.Retrier;
import java.util.logging.Level;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
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

  @Mock private AppEngineServiceUtils appEngineServiceUtils;

  private AsyncTaskEnqueuer asyncTaskEnqueuer;
  private final CapturingLogHandler logHandler = new CapturingLogHandler();
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-05-18T12:34:56Z"));

  @BeforeEach
  void beforeEach() {
    LoggerConfig.getConfig(AsyncTaskEnqueuer.class).addHandler(logHandler);
    when(appEngineServiceUtils.getServiceHostname("backend")).thenReturn("backend.hostname.fake");
    asyncTaskEnqueuer = createForTesting(appEngineServiceUtils, clock, standardSeconds(90));
  }

  public static AsyncTaskEnqueuer createForTesting(
      AppEngineServiceUtils appEngineServiceUtils, FakeClock clock, Duration asyncDeleteDelay) {
    return new AsyncTaskEnqueuer(
        getQueue(QUEUE_ASYNC_ACTIONS),
        getQueue(QUEUE_ASYNC_DELETE),
        getQueue(QUEUE_ASYNC_HOST_RENAME),
        asyncDeleteDelay,
        appEngineServiceUtils,
        new Retrier(new FakeSleeper(clock), 1));
  }

  @Test
  void test_enqueueAsyncResave_success() {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(contact, clock.nowUtc(), clock.nowUtc().plusDays(5));
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(ResaveEntityAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, Key.create(contact).getString())
            .param(PARAM_REQUESTED_TIME, clock.nowUtc().toString())
            .etaDelta(
                standardDays(5).minus(standardSeconds(30)),
                standardDays(5).plus(standardSeconds(30))));
  }

  @Test
  void test_enqueueAsyncResave_multipleResaves() {
    ContactResource contact = persistActiveContact("jd23456");
    DateTime now = clock.nowUtc();
    asyncTaskEnqueuer.enqueueAsyncResave(
        contact,
        now,
        ImmutableSortedSet.of(now.plusHours(24), now.plusHours(50), now.plusHours(75)));
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(ResaveEntityAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .header("content-type", "application/x-www-form-urlencoded")
            .param(PARAM_RESOURCE_KEY, Key.create(contact).getString())
            .param(PARAM_REQUESTED_TIME, now.toString())
            .param(PARAM_RESAVE_TIMES, "2015-05-20T14:34:56.000Z,2015-05-21T15:34:56.000Z")
            .etaDelta(
                standardHours(24).minus(standardSeconds(30)),
                standardHours(24).plus(standardSeconds(30))));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void test_enqueueAsyncResave_ignoresTasksTooFarIntoFuture() throws Exception {
    ContactResource contact = persistActiveContact("jd23456");
    asyncTaskEnqueuer.enqueueAsyncResave(contact, clock.nowUtc(), clock.nowUtc().plusDays(31));
    assertNoTasksEnqueued(QUEUE_ASYNC_ACTIONS);
    assertLogMessage(logHandler, Level.INFO, "Ignoring async re-save");
  }

  @Test
  void testEnqueueRelock() {
    RegistryLock lock =
        saveRegistryLock(
            new RegistryLock.Builder()
                .setLockCompletionTime(clock.nowUtc())
                .setUnlockRequestTime(clock.nowUtc())
                .setUnlockCompletionTime(clock.nowUtc())
                .isSuperuser(false)
                .setDomainName("example.tld")
                .setRepoId("repoId")
                .setRelockDuration(standardHours(6))
                .setRegistrarId("TheRegistrar")
                .setRegistrarPocId("someone@example.com")
                .setVerificationCode("hi")
                .build());
    asyncTaskEnqueuer.enqueueDomainRelock(lock.getRelockDuration().get(), lock.getRevisionId(), 0);
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(RelockDomainAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lock.getRevisionId()))
            .param(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM, "0")
            .etaDelta(
                standardHours(6).minus(standardSeconds(30)),
                standardHours(6).plus(standardSeconds(30))));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testFailure_enqueueRelock_noDuration() {
    RegistryLock lockWithoutDuration =
        saveRegistryLock(
            new RegistryLock.Builder()
                .isSuperuser(false)
                .setDomainName("example.tld")
                .setRepoId("repoId")
                .setRegistrarId("TheRegistrar")
                .setRegistrarPocId("someone@example.com")
                .setVerificationCode("hi")
                .build());
    assertThat(
        assertThrows(
            IllegalArgumentException.class,
            () -> asyncTaskEnqueuer.enqueueDomainRelock(lockWithoutDuration)))
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Lock with ID %s not configured for relock", lockWithoutDuration.getRevisionId()));
  }
}
