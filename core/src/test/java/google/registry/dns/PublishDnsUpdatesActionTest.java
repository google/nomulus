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

package google.registry.dns;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.dns.DnsModule.PARAM_DNS_WRITER;
import static google.registry.dns.DnsModule.PARAM_DOMAINS;
import static google.registry.dns.DnsModule.PARAM_HOSTS;
import static google.registry.dns.DnsModule.PARAM_LOCK_INDEX;
import static google.registry.dns.DnsModule.PARAM_NUM_PUBLISH_LOCKS;
import static google.registry.dns.DnsModule.PARAM_PUBLISH_TASK_ENQUEUED;
import static google.registry.dns.DnsModule.PARAM_REFRESH_REQUEST_CREATED;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.appengine.api.taskqueue.Queue;
import com.google.cloud.tasks.v2.Task;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsMetrics.ActionStatus;
import google.registry.dns.DnsMetrics.CommitStatus;
import google.registry.dns.DnsMetrics.PublishStatus;
import google.registry.dns.writer.DnsWriter;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.tld.Registry;
import google.registry.request.Action.Service;
import google.registry.request.HttpException.ServiceUnavailableException;
import google.registry.request.lock.LockHandler;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeLockHandler;
import google.registry.testing.FakeSleeper;
import google.registry.testing.InjectExtension;
import google.registry.util.CloudTasksUtils;
import google.registry.util.CloudTasksUtils.SerializableCloudTasksClient;
import google.registry.util.Retrier;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PublishDnsUpdatesAction}. */
public class PublishDnsUpdatesActionTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withCloudSql().withTaskQueue().build();

  @RegisterExtension public final InjectExtension inject = new InjectExtension();
  private final FakeClock clock = new FakeClock(DateTime.parse("1971-01-01TZ"));
  private final FakeLockHandler lockHandler = new FakeLockHandler(true);
  private final DnsWriter dnsWriter = mock(DnsWriter.class);
  private final DnsMetrics dnsMetrics = mock(DnsMetrics.class);
  private final DnsQueue dnsQueue = mock(DnsQueue.class);
  private final CloudTasksUtils cloudTasksUtils =
      new CloudTasksUtils(
          new Retrier(new FakeSleeper(clock), 1),
          clock,
          "project",
          "location",
          mock(SerializableCloudTasksClient.class));
  private final CloudTasksUtils spyCloudTasksUtils = spy(cloudTasksUtils);
  private final Queue queue = mock(Queue.class);
  private PublishDnsUpdatesAction action;

  @BeforeEach
  void beforeEach() {
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("xn--q9jyb4c");
    persistResource(
        Registry.get("xn--q9jyb4c")
            .asBuilder()
            .setDnsWriters(ImmutableSet.of("correctWriter"))
            .build());
    DomainBase domain1 = persistActiveDomain("example.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain1);
    persistActiveSubordinateHost("ns2.example.xn--q9jyb4c", domain1);
    DomainBase domain2 = persistActiveDomain("example2.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain2);
    clock.advanceOneMilli();
  }

  private PublishDnsUpdatesAction createAction(String tld) {
    PublishDnsUpdatesAction action = new PublishDnsUpdatesAction();
    action.timeout = Duration.standardSeconds(10);
    action.tld = tld;
    action.hosts = ImmutableSet.of();
    action.domains = ImmutableSet.of();
    action.itemsCreateTime = clock.nowUtc().minusHours(2);
    action.enqueuedTime = clock.nowUtc().minusHours(1);
    action.dnsWriter = "correctWriter";
    action.dnsWriterProxy = new DnsWriterProxy(ImmutableMap.of("correctWriter", dnsWriter));
    action.dnsMetrics = dnsMetrics;
    action.dnsQueue = dnsQueue;
    action.lockIndex = 1;
    action.numPublishLocks = 1;
    action.lockHandler = lockHandler;
    action.clock = clock;
    action.cloudTasksUtils = spyCloudTasksUtils;
    action.appEngineRetryCount = 0;
    action.dnsPublishPushQueue = queue;
    return action;
  }

  @Test
  void testHost_published() {
    action = createAction("xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");

    action.run();

    verify(dnsWriter).publishHost("ns1.example.xn--q9jyb4c");
    verify(dnsWriter).commit();
    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 1, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics)
        .recordCommit("xn--q9jyb4c", "correctWriter", CommitStatus.SUCCESS, Duration.ZERO, 0, 1);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.SUCCESS,
            1,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testDomain_published() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.xn--q9jyb4c");

    action.run();

    verify(dnsWriter).publishDomain("example.xn--q9jyb4c");
    verify(dnsWriter).commit();
    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 1, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics)
        .recordCommit("xn--q9jyb4c", "correctWriter", CommitStatus.SUCCESS, Duration.ZERO, 1, 0);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.SUCCESS,
            1,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testAction_acquiresCorrectLock() {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setNumDnsPublishLocks(4).build());
    action = createAction("xn--q9jyb4c");
    action.lockIndex = 2;
    action.numPublishLocks = 4;
    action.domains = ImmutableSet.of("example.xn--q9jyb4c");
    LockHandler mockLockHandler = mock(LockHandler.class);
    when(mockLockHandler.executeWithLocks(any(), any(), any(), any())).thenReturn(true);
    action.lockHandler = mockLockHandler;

    action.run();

    verify(mockLockHandler)
        .executeWithLocks(
            action, "xn--q9jyb4c", Duration.standardSeconds(10), "DNS updates-lock 2 of 4");
  }

  @Test
  void testPublish_commitFails() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.xn--q9jyb4c", "example2.xn--q9jyb4c");
    action.hosts =
        ImmutableSet.of(
            "ns1.example.xn--q9jyb4c", "ns2.example.xn--q9jyb4c", "ns1.example2.xn--q9jyb4c");
    doThrow(new RuntimeException()).when(dnsWriter).commit();

    assertThrows(RuntimeException.class, action::run);

    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 2, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 3, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics)
        .recordCommit("xn--q9jyb4c", "correctWriter", CommitStatus.FAILURE, Duration.ZERO, 2, 3);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.COMMIT_FAILURE,
            5,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testTaskFails_splitsBatch() {
    action = createAction("xn--q9jyb4c");
    action.domains =
        ImmutableSet.of(
            "example1.xn--q9jyb4c",
            "example2.xn--q9jyb4c",
            "example3.xn--q9jyb4c",
            "example4.xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");
    action.appEngineRetryCount = 3;
    doThrow(new RuntimeException()).when(dnsWriter).commit();
    action.run();

    Task task1 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(PARAM_DOMAINS, "example1.xn--q9jyb4c,example2.xn--q9jyb4c")
                .put(PARAM_HOSTS, "")
                .build());

    Task task2 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(PARAM_DOMAINS, "example3.xn--q9jyb4c,example4.xn--q9jyb4c")
                .put(PARAM_HOSTS, "ns1.example.xn--q9jyb4c")
                .build());

    verify(spyCloudTasksUtils).enqueue("dns-publish", task1);
    verify(spyCloudTasksUtils).enqueue("dns-publish", task2);
  }

  @Test
  void testTaskFails_splitsBatch5Names() {
    action = createAction("xn--q9jyb4c");
    action.domains =
        ImmutableSet.of(
            "example1.xn--q9jyb4c",
            "example2.xn--q9jyb4c",
            "example3.xn--q9jyb4c",
            "example4.xn--q9jyb4c",
            "example5.xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");
    action.appEngineRetryCount = 3;
    doThrow(new RuntimeException()).when(dnsWriter).commit();
    action.run();

    Task task1 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(PARAM_DOMAINS, "example1.xn--q9jyb4c,example2.xn--q9jyb4c")
                .put(PARAM_HOSTS, "")
                .build());

    Task task2 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(
                    PARAM_DOMAINS, "example3.xn--q9jyb4c,example4.xn--q9jyb4c,example5.xn--q9jyb4c")
                .put(PARAM_HOSTS, "ns1.example.xn--q9jyb4c")
                .build());

    verify(spyCloudTasksUtils).enqueue("dns-publish", task1);
    verify(spyCloudTasksUtils).enqueue("dns-publish", task2);
  }

  @Test
  void testTaskFails_singleHostSingleDomain() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example1.xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");
    action.appEngineRetryCount = 3;
    doThrow(new RuntimeException()).when(dnsWriter).commit();
    action.run();

    Task task1 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(PARAM_DOMAINS, "example1.xn--q9jyb4c")
                .put(PARAM_HOSTS, "")
                .build());

    Task task2 =
        cloudTasksUtils.createPostTask(
            PublishDnsUpdatesAction.PATH,
            Service.BACKEND.toString(),
            ImmutableMultimap.<String, String>builder()
                .put(PARAM_TLD, "xn--q9jyb4c")
                .put(PARAM_DNS_WRITER, "correctWriter")
                .put(PARAM_LOCK_INDEX, "1")
                .put(PARAM_NUM_PUBLISH_LOCKS, "1")
                .put(PARAM_PUBLISH_TASK_ENQUEUED, clock.nowUtc().toString())
                .put(PARAM_REFRESH_REQUEST_CREATED, action.itemsCreateTime.toString())
                .put(PARAM_DOMAINS, "")
                .put(PARAM_HOSTS, "ns1.example.xn--q9jyb4c")
                .build());

    verify(spyCloudTasksUtils).enqueue("dns-publish", task1);
    verify(spyCloudTasksUtils).enqueue("dns-publish", task2);
  }

  @Test
  void testTaskFailsAfterTenRetries_DoesNotRetry() {
    action = createAction("xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");
    action.cloudTasksRetryCount = 9;
    doThrow(new RuntimeException()).when(dnsWriter).commit();
    assertThrows(RuntimeException.class, action::run);
    verifyNoMoreInteractions(spyCloudTasksUtils);
  }

  @Test
  void testHostAndDomain_published() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.xn--q9jyb4c", "example2.xn--q9jyb4c");
    action.hosts =
        ImmutableSet.of(
            "ns1.example.xn--q9jyb4c", "ns2.example.xn--q9jyb4c", "ns1.example2.xn--q9jyb4c");

    action.run();

    verify(dnsWriter).publishDomain("example.xn--q9jyb4c");
    verify(dnsWriter).publishDomain("example2.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns1.example.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns2.example.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns1.example2.xn--q9jyb4c");
    verify(dnsWriter).commit();
    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 2, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 3, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.REJECTED);
    verify(dnsMetrics)
        .recordCommit("xn--q9jyb4c", "correctWriter", CommitStatus.SUCCESS, Duration.ZERO, 2, 3);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.SUCCESS,
            5,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testWrongTld_notPublished() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com", "example2.com");
    action.hosts = ImmutableSet.of("ns1.example.com", "ns2.example.com", "ns1.example2.com");

    action.run();

    verify(dnsWriter).commit();
    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 0, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", 2, PublishStatus.REJECTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 0, PublishStatus.ACCEPTED);
    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", 3, PublishStatus.REJECTED);
    verify(dnsMetrics)
        .recordCommit("xn--q9jyb4c", "correctWriter", CommitStatus.SUCCESS, Duration.ZERO, 0, 0);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.SUCCESS,
            5,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testLockIsntAvailable() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com", "example2.com");
    action.hosts = ImmutableSet.of("ns1.example.com", "ns2.example.com", "ns1.example2.com");
    action.lockHandler = new FakeLockHandler(false);

    ServiceUnavailableException thrown =
        assertThrows(ServiceUnavailableException.class, action::run);

    assertThat(thrown).hasMessageThat().contains("Lock failure");
    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.LOCK_FAILURE,
            5,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testParam_invalidLockIndex() {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setNumDnsPublishLocks(4).build());
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com");
    action.hosts = ImmutableSet.of("ns1.example.com");
    action.lockIndex = 5;
    action.numPublishLocks = 4;

    action.run();

    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.BAD_LOCK_INDEX,
            2,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verify(dnsQueue).addDomainRefreshTask("example.com");
    verify(dnsQueue).addHostRefreshTask("ns1.example.com");
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testRegistryParam_mismatchedMaxLocks() {
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setNumDnsPublishLocks(4).build());
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com");
    action.hosts = ImmutableSet.of("ns1.example.com");
    action.lockIndex = 3;
    action.numPublishLocks = 5;

    action.run();

    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "correctWriter",
            ActionStatus.BAD_LOCK_INDEX,
            2,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verify(dnsQueue).addDomainRefreshTask("example.com");
    verify(dnsQueue).addHostRefreshTask("ns1.example.com");
    verifyNoMoreInteractions(dnsQueue);
  }

  @Test
  void testWrongDnsWriter() {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com", "example2.com");
    action.hosts = ImmutableSet.of("ns1.example.com", "ns2.example.com", "ns1.example2.com");
    action.dnsWriter = "wrongWriter";

    action.run();

    verifyNoMoreInteractions(dnsWriter);
    verify(dnsMetrics)
        .recordActionResult(
            "xn--q9jyb4c",
            "wrongWriter",
            ActionStatus.BAD_WRITER,
            5,
            Duration.standardHours(2),
            Duration.standardHours(1));
    verifyNoMoreInteractions(dnsMetrics);
    verify(dnsQueue).addDomainRefreshTask("example.com");
    verify(dnsQueue).addDomainRefreshTask("example2.com");
    verify(dnsQueue).addHostRefreshTask("ns1.example.com");
    verify(dnsQueue).addHostRefreshTask("ns2.example.com");
    verify(dnsQueue).addHostRefreshTask("ns1.example2.com");
    verifyNoMoreInteractions(dnsQueue);
  }
}
