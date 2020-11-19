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

package google.registry.batch;

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_HOST_RENAME;
import static google.registry.batch.AsyncTaskMetrics.OperationType.DNS_REFRESH;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.newHostResource;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.Duration.millis;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.googlecode.objectify.Key;
import google.registry.batch.AsyncTaskMetrics.OperationResult;
import google.registry.batch.RefreshDnsOnHostRenameAction.RefreshDnsOnHostRenameReducer;
import google.registry.model.host.HostResource;
import google.registry.model.server.Lock;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeResponse;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.mapreduce.MapreduceTestCase;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.RequestStatusChecker;
import google.registry.util.Retrier;
import google.registry.util.Sleeper;
import google.registry.util.SystemSleeper;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

/** Unit tests for {@link RefreshDnsOnHostRenameAction}. */
public class RefreshDnsOnHostRenameActionTest
    extends MapreduceTestCase<RefreshDnsOnHostRenameAction> {

  @RegisterExtension public final InjectExtension inject = new InjectExtension();

  private AsyncTaskEnqueuer enqueuer;
  private final FakeClock clock = new FakeClock(DateTime.parse("2015-01-15T11:22:33Z"));
  private final FakeResponse fakeResponse = new FakeResponse();
  @Mock private RequestStatusChecker requestStatusChecker;

  @BeforeEach
  void beforeEach() {
    createTld("tld");
    enqueuer =
        AsyncTaskEnqueuerTest.createForTesting(
            mock(AppEngineServiceUtils.class), clock, Duration.ZERO);
    AsyncTaskMetrics asyncTaskMetricsMock = mock(AsyncTaskMetrics.class);
    action = new RefreshDnsOnHostRenameAction();
    action.asyncTaskMetrics = asyncTaskMetricsMock;
    inject.setStaticField(
        RefreshDnsOnHostRenameReducer.class, "asyncTaskMetrics", asyncTaskMetricsMock);
    action.clock = clock;
    action.mrRunner = makeDefaultRunner();
    action.pullQueue = getQueue(QUEUE_ASYNC_HOST_RENAME);
    action.requestStatusChecker = requestStatusChecker;
    action.response = fakeResponse;
    action.retrier = new Retrier(new FakeSleeper(clock), 1);
    when(requestStatusChecker.getLogId()).thenReturn("requestId");
    when(requestStatusChecker.isRunning(anyString()))
        .thenThrow(new AssertionError("Should not be called"));
  }

  private void runMapreduce() throws Exception {
    clock.advanceOneMilli();
    // Use hard sleeps to ensure that the tasks are enqueued properly and will be leased.
    Sleeper sleeper = new SystemSleeper();
    sleeper.sleep(millis(50));
    action.run();
    sleeper.sleep(millis(50));
    executeTasksUntilEmpty("mapreduce", clock);
    sleeper.sleep(millis(50));
    clock.advanceBy(standardSeconds(5));
    ofy().clearSessionCache();
  }

  /** Kicks off, but does not run, the mapreduce tasks. Useful for testing validation/setup. */
  private void enqueueMapreduceOnly() {
    clock.advanceOneMilli();
    action.run();
    clock.advanceBy(standardSeconds(5));
    ofy().clearSessionCache();
  }

  @Test
  void testSuccess_dnsUpdateEnqueued() throws Exception {
    HostResource host = persistActiveHost("ns1.example.tld");
    persistResource(newDomainBase("example.tld", host));
    persistResource(newDomainBase("otherexample.tld", host));
    persistResource(newDomainBase("untouched.tld", persistActiveHost("ns2.example.tld")));
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDnsRefresh(host, timeEnqueued);
    runMapreduce();
    assertDnsTasksEnqueued("example.tld", "otherexample.tld");
    assertNoTasksEnqueued(QUEUE_ASYNC_HOST_RENAME);
    verify(action.asyncTaskMetrics).recordDnsRefreshBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(DNS_REFRESH, OperationResult.SUCCESS, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_multipleHostsProcessedInBatch() throws Exception {
    HostResource host1 = persistActiveHost("ns1.example.tld");
    HostResource host2 = persistActiveHost("ns2.example.tld");
    HostResource host3 = persistActiveHost("ns3.example.tld");
    persistResource(newDomainBase("example1.tld", host1));
    persistResource(newDomainBase("example2.tld", host2));
    persistResource(newDomainBase("example3.tld", host3));
    DateTime timeEnqueued = clock.nowUtc();
    DateTime laterTimeEnqueued = timeEnqueued.plus(standardSeconds(10));
    enqueuer.enqueueAsyncDnsRefresh(host1, timeEnqueued);
    enqueuer.enqueueAsyncDnsRefresh(host2, timeEnqueued);
    enqueuer.enqueueAsyncDnsRefresh(host3, laterTimeEnqueued);
    runMapreduce();
    assertDnsTasksEnqueued("example1.tld", "example2.tld", "example3.tld");
    assertNoTasksEnqueued(QUEUE_ASYNC_HOST_RENAME);
    verify(action.asyncTaskMetrics).recordDnsRefreshBatchSize(3L);
    verify(action.asyncTaskMetrics, times(2))
        .recordAsyncFlowResult(DNS_REFRESH, OperationResult.SUCCESS, timeEnqueued);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(DNS_REFRESH, OperationResult.SUCCESS, laterTimeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_deletedHost_doesntTriggerDnsRefresh() throws Exception {
    HostResource host = persistDeletedHost("ns11.fakesss.tld", clock.nowUtc().minusDays(4));
    persistResource(newDomainBase("example1.tld", host));
    DateTime timeEnqueued = clock.nowUtc();
    enqueuer.enqueueAsyncDnsRefresh(host, timeEnqueued);
    runMapreduce();
    assertNoDnsTasksEnqueued();
    assertNoTasksEnqueued(QUEUE_ASYNC_HOST_RENAME);
    verify(action.asyncTaskMetrics).recordDnsRefreshBatchSize(1L);
    verify(action.asyncTaskMetrics)
        .recordAsyncFlowResult(DNS_REFRESH, OperationResult.STALE, timeEnqueued);
    verifyNoMoreInteractions(action.asyncTaskMetrics);
  }

  @Test
  void testSuccess_noDnsTasksForDeletedDomain() throws Exception {
    HostResource renamedHost = persistActiveHost("ns1.example.tld");
    persistResource(
        newDomainBase("example.tld", renamedHost)
            .asBuilder()
            .setDeletionTime(START_OF_TIME)
            .build());
    enqueuer.enqueueAsyncDnsRefresh(renamedHost, clock.nowUtc());
    runMapreduce();
    assertNoDnsTasksEnqueued();
    assertNoTasksEnqueued(QUEUE_ASYNC_HOST_RENAME);
  }

  @Test
  void testRun_hostDoesntExist_delaysTask() throws Exception {
    HostResource host = newHostResource("ns1.example.tld");
    enqueuer.enqueueAsyncDnsRefresh(host, clock.nowUtc());
    enqueueMapreduceOnly();
    assertNoDnsTasksEnqueued();
    assertTasksEnqueued(
        QUEUE_ASYNC_HOST_RENAME,
        new TaskMatcher()
            .etaDelta(standardHours(23), standardHours(25))
            .param("hostKey", Key.create(host).getString()));
    assertThat(acquireLock()).isPresent();
  }

  @Test
  void test_cannotAcquireLock() {
    // Make lock acquisition fail.
    acquireLock();
    enqueueMapreduceOnly();
    assertThat(fakeResponse.getPayload()).isEqualTo("Can't acquire lock; aborting.");
    assertNoDnsTasksEnqueued();
  }

  @Test
  void test_mapreduceHasWorkToDo_lockIsAcquired() {
    HostResource host = persistActiveHost("ns1.example.tld");
    enqueuer.enqueueAsyncDnsRefresh(host, clock.nowUtc());
    enqueueMapreduceOnly();
    assertThat(acquireLock()).isEmpty();
  }

  @Test
  void test_noTasksToLease_releasesLockImmediately() throws Exception {
    enqueueMapreduceOnly();
    assertNoDnsTasksEnqueued();
    assertNoTasksEnqueued(QUEUE_ASYNC_HOST_RENAME);
    // If the Lock was correctly released, then we can acquire it now.
    assertThat(acquireLock()).isPresent();
  }

  private Optional<Lock> acquireLock() {
    return Lock.acquire(
        RefreshDnsOnHostRenameAction.class.getSimpleName(),
        null,
        standardDays(30),
        requestStatusChecker,
        false);
  }
}
