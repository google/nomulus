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

import static com.google.appengine.api.taskqueue.QueueFactory.getQueue;
import static com.google.common.collect.Lists.transform;
import static google.registry.dns.DnsConstants.DNS_PUBLISH_PUSH_QUEUE_NAME;
import static google.registry.dns.DnsConstants.DNS_PULL_QUEUE_NAME;
import static google.registry.dns.DnsConstants.DNS_TARGET_NAME_PARAM;
import static google.registry.dns.DnsConstants.DNS_TARGET_TYPE_PARAM;
import static google.registry.request.RequestParameters.PARAM_TLD;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.TaskQueueHelper.assertNoTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static java.util.Arrays.asList;

import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;
import google.registry.dns.DnsConstants.TargetType;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import google.registry.request.RequestParameters;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.util.Retrier;
import google.registry.util.TaskEnqueuer;
import java.util.ArrayList;
import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReadDnsQueueAction}. */
@RunWith(JUnit4.class)
public class ReadDnsQueueActionTest {

  private static final int TEST_TLD_UPDATE_BATCH_SIZE = 100;
  private DnsQueue dnsQueue;
  private FakeClock clock = new FakeClock(DateTime.now(DateTimeZone.UTC));

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue(Joiner.on('\n').join(
          "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
          "<queue-entries>",
          "  <queue>",
          "    <name>dns-publish</name>",
          "    <rate>1/s</rate>",
          "  </queue>",
          "  <queue>",
          "    <name>dns-pull</name>",
          "    <mode>pull</mode>",
          "  </queue>",
          "</queue-entries>"))
      .withClock(clock)
      .build();

  @Before
  public void before() throws Exception {
    clock.setTo(DateTime.now(DateTimeZone.UTC));
    createTlds("com", "net", "example");
    persistResource(Registry.get("example").asBuilder().setTldType(TldType.TEST).build());
    dnsQueue = DnsQueue.create();
  }

  private void run(boolean keepTasks) throws Exception {
    ReadDnsQueueAction action = new ReadDnsQueueAction();
    action.tldUpdateBatchSize = TEST_TLD_UPDATE_BATCH_SIZE;
    action.writeLockTimeout = Duration.standardSeconds(10);
    action.dnsQueue = dnsQueue;
    action.dnsPublishPushQueue = QueueFactory.getQueue(DNS_PUBLISH_PUSH_QUEUE_NAME);
    action.taskEnqueuer = new TaskEnqueuer(new Retrier(null, 1));
    action.jitterSeconds = Optional.absent();
    action.keepTasks = keepTasks;
    // Advance the time a little, to ensure that leaseTasks() returns all tasks.
    clock.setTo(DateTime.now(DateTimeZone.UTC).plusMillis(1));
    action.run();
  }

  private static TaskOptions createRefreshTask(String name, TargetType type) {
    TaskOptions options = TaskOptions.Builder
        .withMethod(Method.PULL)
        .param(DNS_TARGET_TYPE_PARAM, type.toString())
        .param(DNS_TARGET_NAME_PARAM, name);
    String tld = InternetDomainName.from(name).parts().reverse().get(0);
    return options.param(PARAM_TLD, tld);
  }

  private void assertTldsEnqueuedInPushQueue(String... tlds) throws Exception {
    assertTasksEnqueued(
        DNS_PUBLISH_PUSH_QUEUE_NAME,
        transform(asList(tlds), new Function<String, TaskMatcher>() {
          @Override
          public TaskMatcher apply(String tld) {
            return new TaskMatcher()
                .url(PublishDnsUpdatesAction.PATH)
                .param(RequestParameters.PARAM_TLD, tld)
                .header("content-type", "application/x-www-form-urlencoded");
          }}));
  }

  @Test
  public void testSuccess_methodPostIsDefault() throws Exception {
    dnsQueue.addDomainRefreshTask("domain.com");
    dnsQueue.addDomainRefreshTask("domain.net");
    dnsQueue.addDomainRefreshTask("domain.example");
    run(false);
    assertNoTasksEnqueued(DNS_PULL_QUEUE_NAME);
    assertTasksEnqueued(
        DNS_PUBLISH_PUSH_QUEUE_NAME,
        new TaskMatcher().method("POST"),
        new TaskMatcher().method("POST"),
        new TaskMatcher().method("POST"));
  }

  @Test
  public void testSuccess_allTlds() throws Exception {
    dnsQueue.addDomainRefreshTask("domain.com");
    dnsQueue.addDomainRefreshTask("domain.net");
    dnsQueue.addDomainRefreshTask("domain.example");
    run(false);
    assertNoTasksEnqueued(DNS_PULL_QUEUE_NAME);
    assertTldsEnqueuedInPushQueue("com", "net", "example");
  }

  @Test
  public void testSuccess_allTldsKeepTasks() throws Exception {
    dnsQueue.addDomainRefreshTask("domain.com");
    dnsQueue.addDomainRefreshTask("domain.net");
    dnsQueue.addDomainRefreshTask("domain.example");
    run(true);
    assertTasksEnqueued(
        DNS_PULL_QUEUE_NAME,
        new TaskMatcher().payload("Target-Type=DOMAIN&Target-Name=domain.com&tld=com"),
        new TaskMatcher().payload("Target-Type=DOMAIN&Target-Name=domain.net&tld=net"),
        new TaskMatcher().payload("Target-Type=DOMAIN&Target-Name=domain.example&tld=example"));
    assertTldsEnqueuedInPushQueue("com", "net", "example");
  }

  @Test
  public void testSuccess_oneTldPaused() throws Exception {
    persistResource(Registry.get("net").asBuilder().setDnsPaused(true).build());
    dnsQueue.addDomainRefreshTask("domain.com");
    dnsQueue.addDomainRefreshTask("domain.net");
    dnsQueue.addDomainRefreshTask("domain.example");
    run(false);
    assertTasksEnqueued(DNS_PULL_QUEUE_NAME, new TaskMatcher());
    assertTldsEnqueuedInPushQueue("com", "example");
  }

  @Test
  public void testSuccess_zone_getsIgnored() throws Exception {
    dnsQueue.addHostRefreshTask("ns1.domain.com");
    dnsQueue.addDomainRefreshTask("domain.net");
    dnsQueue.addZoneRefreshTask("example");
    run(false);
    assertNoTasksEnqueued(DNS_PULL_QUEUE_NAME);
    assertTasksEnqueued(DNS_PUBLISH_PUSH_QUEUE_NAME,
        new TaskMatcher()
            .url(PublishDnsUpdatesAction.PATH)
            .param("domains", "domain.net"),
        new TaskMatcher()
            .url(PublishDnsUpdatesAction.PATH)
            .param("hosts", "ns1.domain.com"));
  }

  @Test
  public void testSuccess_manyDomainsAndHosts() throws Exception {
    List<TaskMatcher> expectedTasks = new ArrayList<>();
    for (String tld : ImmutableList.of("com", "net")) {
      int refreshItemsInTask = 0;
      TaskMatcher task = null;
      // 0: domain; 1: host 1; 2: host 2
      for (int thingType = 0; thingType < 3; thingType++) {
        for (int i = 0; i < 150; i++) {
          String domainName = String.format("domain%04d.%s", i, tld);
          // If we don't have an existing task into which to dump new refreshes, create one.
          if (task == null) {
            task = new TaskMatcher().url(PublishDnsUpdatesAction.PATH);
            expectedTasks.add(task);
            refreshItemsInTask = 0;
          }
          switch (thingType) {
            default:
              dnsQueue.addDomainRefreshTask(domainName);
              task.param("domains", domainName);
              break;
            case 1:
              getQueue(DNS_PULL_QUEUE_NAME)
                  .add(createRefreshTask("ns1." + domainName, TargetType.HOST));
              task.param("hosts", "ns1." + domainName);
              break;
            case 2:
              getQueue(DNS_PULL_QUEUE_NAME)
                  .add(createRefreshTask("ns2." + domainName, TargetType.HOST));
              task.param("hosts", "ns2." + domainName);
              break;
          }
          // If this task is now full up, wash our hands of it, so that we'll start a new one the
          // next time through the loop.
          refreshItemsInTask++;
          if (refreshItemsInTask >= TEST_TLD_UPDATE_BATCH_SIZE) {
            task = null;
          }
        }
      }
    }
    run(false);
    assertNoTasksEnqueued(DNS_PULL_QUEUE_NAME);
    assertTasksEnqueued(DNS_PUBLISH_PUSH_QUEUE_NAME, expectedTasks);
  }
}
