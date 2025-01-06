// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.dns;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsUtils.TargetType;
import google.registry.model.common.DnsRefreshRequest;
import google.registry.model.common.DnsRefreshRequestTest;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.FakeClock;
import java.util.Collection;
import java.util.Optional;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link DnsRefreshRequestTest}. */
public class ReadDnsRefreshRequestsActionTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2020-02-02T01:23:45Z"));
  private final CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);
  private final Optional<Integer> jitterSeconds = Optional.of(5);

  @RegisterExtension
  JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final ReadDnsRefreshRequestsAction action =
      spy(
          new ReadDnsRefreshRequestsAction(
              2,
              Duration.standardSeconds(10),
              jitterSeconds,
              "tld",
              clock,
              null,
              cloudTasksHelper.getTestCloudTasksUtils()));

  private ImmutableList<DnsRefreshRequest> requests;

  @BeforeEach
  void beforeEach() {
    persistResource(
        createTld("tld")
            .asBuilder()
            .setDnsWriters(ImmutableSet.of("FooWriter", "BarWriter"))
            .setNumDnsPublishLocks(2)
            .build());
    requests =
        new ImmutableList.Builder<DnsRefreshRequest>()
            .add(new DnsRefreshRequest(TargetType.DOMAIN, "domain.tld", "tld", clock.nowUtc()))
            .add(
                new DnsRefreshRequest(
                    TargetType.HOST, "ns1.domain.tld", "tld", clock.nowUtc().minusMinutes(1)))
            .add(
                new DnsRefreshRequest(
                    TargetType.DOMAIN, "future.tld", "tld", clock.nowUtc().plusMinutes(1)))
            .build();
    clock.advanceBy(Duration.standardMinutes(5));
    persistResources(requests);
    requests = loadAllOf(DnsRefreshRequest.class);
  }

  @Test
  void testSuccess_runAction_pausedTld() {
    persistResource(createTld("tld").asBuilder().setDnsPaused(true).build());
    action.run();
    verify(action, never()).enqueueUpdates(anyInt(), anyInt(), anyCollection());
    verify(action, never()).processRequests(anyCollection());
  }

  @Test
  void testSuccess_runAction_requestTimeInTheFuture() {
    clock.setTo(DateTime.parse("2000-01-01T00:00:00Z"));
    action.run();
    verify(action, never()).enqueueUpdates(anyInt(), anyInt(), anyCollection());
    verify(action, never()).processRequests(anyCollection());
  }

  @Test
  void testSuccess_runAction_oneBatch() {
    // The read batch size is 2 * 2 = 4. All requests will be in the same bucket, even though the
    // bucket size should be roughly 2. But this is as expected because getLockIndex() only evenly
    // distributes tasks in a statistical sense.
    doReturn(2).when(action).getLockIndex(anyInt(), any(DnsRefreshRequest.class));
    action.run();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<DnsRefreshRequest>> captor =
        ArgumentCaptor.forClass(Collection.class);
    verify(action, times(1)).enqueueUpdates(eq(2), eq(2), captor.capture());
    assertThat(captor.getValue().size()).isEqualTo(3);
    verify(action, times(1)).processRequests(captor.capture());
    assertThat(captor.getValue().size()).isEqualTo(3);
    assertThat(loadAllOf(DnsRefreshRequest.class).isEmpty()).isTrue();
  }

  @Test
  void testSuccess_runAction_twoBatches() {
    // Make the read batch size 2 * 1 = 2.
    persistResource(Tld.get("tld").asBuilder().setNumDnsPublishLocks(1).build());
    doReturn(1).when(action).getLockIndex(anyInt(), any(DnsRefreshRequest.class));
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ImmutableList<DnsRefreshRequest> ans =
                  (ImmutableList<DnsRefreshRequest>) invocation.callRealMethod();
              // The next read should not time out as we only increment by one millisecond, whereas
              // the timeout is set to 10 seconds.
              clock.advanceOneMilli();
              return ans;
            })
        .when(action)
        .processRequests(anyCollection());
    action.run();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<DnsRefreshRequest>> captor =
        ArgumentCaptor.forClass(Collection.class);
    verify(action, times(2)).enqueueUpdates(eq(1), eq(1), captor.capture());
    assertThat(captor.getAllValues().get(0).size()).isEqualTo(2);
    assertThat(captor.getAllValues().get(1).size()).isEqualTo(1);
    verify(action, times(2)).processRequests(captor.capture());
    assertThat(captor.getAllValues().get(0).size()).isEqualTo(2);
    assertThat(captor.getAllValues().get(1).size()).isEqualTo(1);
    assertThat(loadAllOf(DnsRefreshRequest.class).isEmpty()).isTrue();
  }

  @Test
  void testSuccess_runAction_timeOutAfterFirstRead() {
    // Make the process batch size 2 * 1 = 2.
    persistResource(Tld.get("tld").asBuilder().setNumDnsPublishLocks(1).build());
    // Both requests in the first batch will be bucketed to the same bucket.
    doReturn(1).when(action).getLockIndex(anyInt(), any(DnsRefreshRequest.class));
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              ImmutableList<DnsRefreshRequest> ans =
                  (ImmutableList<DnsRefreshRequest>) invocation.callRealMethod();
              // After this function is called once, the loop in run() should top when it checks
              // if the current time is before the request end time.
              clock.advanceBy(Duration.standardHours(1));
              return ans;
            })
        .when(action)
        .processRequests(anyCollection());
    action.run();
    verify(action, times(1)).enqueueUpdates(anyInt(), anyInt(), anyCollection());
    verify(action, times(1)).processRequests(anyCollection());
    // The third request is left untouched because it is not read;
    ImmutableList<DnsRefreshRequest> remainingRequests = loadAllOf(DnsRefreshRequest.class);
    assertThat(remainingRequests.size()).isEqualTo(1);
    assertThat(remainingRequests.get(0).getLastProcessTime()).isEqualTo(START_OF_TIME);
  }

  @Test
  void testSuccess_processTasks() {
    doReturn(2)
        .doReturn(1)
        .doReturn(2)
        .when(action)
        .getLockIndex(eq(2), any(DnsRefreshRequest.class));
    action.processRequests(requests);
    verify(action).enqueueUpdates(2, 2, ImmutableSet.of(requests.get(0), requests.get(2)));
    verify(action).enqueueUpdates(1, 2, ImmutableSet.of(requests.get(1)));
    assertThat(loadAllOf(DnsRefreshRequest.class)).isEmpty();
  }

  @Test
  void testSuccess_processTasks_enqueueFailed_tasksNotDeleted() {
    doReturn(2)
        .doReturn(1)
        .doReturn(2)
        .when(action)
        .getLockIndex(eq(2), any(DnsRefreshRequest.class));
    doThrow(new RuntimeException("Something went wrong!"))
        .when(action)
        .enqueueUpdates(eq(2), eq(2), anyCollection());
    action.processRequests(requests);
    verify(action).enqueueUpdates(2, 2, ImmutableSet.of(requests.get(0), requests.get(2)));
    verify(action).enqueueUpdates(1, 2, ImmutableSet.of(requests.get(1)));
    assertThat(loadAllOf(DnsRefreshRequest.class).size()).isEqualTo(2);
  }

  @Test
  void testSuccess_enqueueTasks() {
    action.enqueueUpdates(2, 3, requests);
    cloudTasksHelper.assertTasksEnqueued(
        "dns-publish",
        new TaskMatcher()
            .path("/_dr/task/publishDnsUpdates")
            .service("BACKEND")
            .param("tld", "tld")
            .param("dnsWriter", "FooWriter")
            .param("lockIndex", "2")
            .param("numPublishLocks", "3")
            .param("enqueued", clock.nowUtc().toString())
            .param("requestTime", clock.nowUtc().minusMinutes(6).toString())
            .param("domains", "domain.tld,future.tld")
            .param("hosts", "ns1.domain.tld"),
        new TaskMatcher()
            .path("/_dr/task/publishDnsUpdates")
            .service("BACKEND")
            .param("tld", "tld")
            .param("dnsWriter", "BarWriter")
            .param("lockIndex", "2")
            .param("numPublishLocks", "3")
            .param("enqueued", clock.nowUtc().toString())
            .param("requestTime", clock.nowUtc().minusMinutes(6).toString())
            .param("domains", "domain.tld,future.tld")
            .param("hosts", "ns1.domain.tld"));
    cloudTasksHelper
        .getTestTasksFor("dns-publish")
        .forEach(
            task -> {
              DateTime scheduledTime =
                  new DateTime(task.getScheduleTime().getSeconds() * 1000, DateTimeZone.UTC);
              assertThat(new Duration(clock.nowUtc(), scheduledTime))
                  .isAtMost(Duration.standardSeconds(jitterSeconds.get()));
            });
  }
}
