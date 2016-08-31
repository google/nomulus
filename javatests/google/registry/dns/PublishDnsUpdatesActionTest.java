// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.dns.DnsMetrics.Status;
import google.registry.dns.writer.DnsWriter;
import google.registry.model.domain.DomainResource;
import google.registry.model.ofy.Ofy;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

/** Unit tests for {@link PublishDnsUpdatesAction}. */
@RunWith(MockitoJUnitRunner.class)
public class PublishDnsUpdatesActionTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private final FakeClock clock = new FakeClock(DateTime.parse("1971-01-01TZ"));
  private final DnsWriter dnsWriter = mock(DnsWriter.class);
  private final DnsMetrics dnsMetrics = mock(DnsMetrics.class);
  private PublishDnsUpdatesAction action;

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);
    createTld("xn--q9jyb4c");
    persistResource(Registry.get("xn--q9jyb4c").asBuilder().setDnsWriter("mock").build());
    DomainResource domain1 = persistActiveDomain("example.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain1);
    persistActiveSubordinateHost("ns2.example.xn--q9jyb4c", domain1);
    DomainResource domain2 = persistActiveDomain("example2.xn--q9jyb4c");
    persistActiveSubordinateHost("ns1.example.xn--q9jyb4c", domain2);
    clock.advanceOneMilli();
  }

  private PublishDnsUpdatesAction createAction(String tld) throws Exception {
    PublishDnsUpdatesAction action = new PublishDnsUpdatesAction();
    action.timeout = Duration.standardSeconds(10);
    action.tld = tld;
    action.hosts = ImmutableSet.<String>of();
    action.domains = ImmutableSet.<String>of();
    action.dnsWriterProxy = new DnsWriterProxy(ImmutableMap.of("mock", dnsWriter));
    action.dnsMetrics = dnsMetrics;
    return action;
  }

  @Test
  public void testHost_published() throws Exception {
    action = createAction("xn--q9jyb4c");
    action.hosts = ImmutableSet.of("ns1.example.xn--q9jyb4c");
    action.run();

    verify(dnsWriter).publishHost("ns1.example.xn--q9jyb4c");
    verify(dnsWriter).close();
    verifyNoMoreInteractions(dnsWriter);

    verify(dnsMetrics).incrementPublishHostRequests("xn--q9jyb4c", Status.ACCEPTED);
    verifyNoMoreInteractions(dnsMetrics);
  }

  @Test
  public void testDomain_published() throws Exception {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.xn--q9jyb4c");
    action.run();

    verify(dnsWriter).publishDomain("example.xn--q9jyb4c");
    verify(dnsWriter).close();
    verifyNoMoreInteractions(dnsWriter);

    verify(dnsMetrics).incrementPublishDomainRequests("xn--q9jyb4c", Status.ACCEPTED);
    verifyNoMoreInteractions(dnsMetrics);
  }

  @Test
  public void testHostAndDomain_published() throws Exception {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.xn--q9jyb4c", "example2.xn--q9jyb4c");
    action.hosts = ImmutableSet.of(
        "ns1.example.xn--q9jyb4c", "ns2.example.xn--q9jyb4c", "ns1.example2.xn--q9jyb4c");
    action.run();

    verify(dnsWriter).publishDomain("example.xn--q9jyb4c");
    verify(dnsWriter).publishDomain("example2.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns1.example.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns2.example.xn--q9jyb4c");
    verify(dnsWriter).publishHost("ns1.example2.xn--q9jyb4c");
    verify(dnsWriter).close();
    verifyNoMoreInteractions(dnsWriter);

    verify(dnsMetrics, times(2)).incrementPublishDomainRequests("xn--q9jyb4c", Status.ACCEPTED);
    verify(dnsMetrics, times(3)).incrementPublishHostRequests("xn--q9jyb4c", Status.ACCEPTED);
    verifyNoMoreInteractions(dnsMetrics);
  }

  @Test
  public void testWrongTld_notPublished() throws Exception {
    action = createAction("xn--q9jyb4c");
    action.domains = ImmutableSet.of("example.com", "example2.com");
    action.hosts = ImmutableSet.of("ns1.example.com", "ns2.example.com", "ns1.example2.com");
    action.run();

    verify(dnsWriter).close();
    verifyNoMoreInteractions(dnsWriter);

    verify(dnsMetrics, times(2)).incrementPublishDomainRequests("xn--q9jyb4c", Status.REJECTED);
    verify(dnsMetrics, times(3)).incrementPublishHostRequests("xn--q9jyb4c", Status.REJECTED);
    verifyNoMoreInteractions(dnsMetrics);
  }
}
