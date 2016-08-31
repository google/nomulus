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

package google.registry.dns.writer.clouddns;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.Change;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.api.services.dns.model.ResourceRecordSetsListResponse;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Ref;
import google.registry.dns.writer.clouddns.CloudDnsWriter.ZoneStateException;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.util.Retrier;
import google.registry.util.SystemClock;
import google.registry.util.SystemSleeper;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Matchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.runners.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/** Test case for {@link CloudDnsWriter}. */
@RunWith(MockitoJUnitRunner.class)
public class CloudDnsWriterTest {

  private static final Inet4Address IPv4 = (Inet4Address) InetAddresses.forString("127.0.0.1");
  private static final Inet6Address IPv6 = (Inet6Address) InetAddresses.forString("::1");
  private static final DelegationSignerData DS_DATA =
      DelegationSignerData.create(12345, 3, 1, base16().decode("1234567890ABCDEF"));
  private static final Duration DEFAULT_TTL = Duration.standardSeconds(180);

  @Mock private Dns dnsConnection;
  @Mock private Dns.ResourceRecordSets resourceRecordSets;
  @Mock private Dns.ResourceRecordSets.List listResourceRecordSetsRequest;
  @Mock private Dns.Changes changes;
  @Mock private Dns.Changes.Create createChangeRequest;
  @Mock private Callable<Void> mutateZoneCallable;
  @Captor ArgumentCaptor<String> recordNameCaptor;
  @Captor ArgumentCaptor<Change> changeCaptor;
  private CloudDnsWriter writer;
  private ImmutableSet<ResourceRecordSet> stubZone;

  @Rule public final ExceptionRule thrown = new ExceptionRule();

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  @Before
  public void setUp() throws Exception {
    createTld("tld");
    writer =
        new CloudDnsWriter(
            dnsConnection,
            "projectId",
            "zoneName",
            DEFAULT_TTL,
            new SystemClock(),
            new Retrier(new SystemSleeper(), 5));

    // Create an empty zone.
    stubZone = ImmutableSet.of();

    when(dnsConnection.changes()).thenReturn(changes);
    when(dnsConnection.resourceRecordSets()).thenReturn(resourceRecordSets);
    when(resourceRecordSets.list(anyString(), anyString()))
        .thenReturn(listResourceRecordSetsRequest);
    when(listResourceRecordSetsRequest.setName(recordNameCaptor.capture()))
        .thenReturn(listResourceRecordSetsRequest);
    // Return records from our stub zone when a request to list the records is executed
    when(listResourceRecordSetsRequest.execute())
        .thenAnswer(
            new Answer<ResourceRecordSetsListResponse>() {
              @Override
              public ResourceRecordSetsListResponse answer(InvocationOnMock invocationOnMock)
                  throws Throwable {
                return new ResourceRecordSetsListResponse()
                    .setRrsets(
                        FluentIterable.from(stubZone)
                            .filter(
                                new Predicate<ResourceRecordSet>() {
                                  @Override
                                  public boolean apply(
                                      @Nullable ResourceRecordSet resourceRecordSet) {
                                    if (resourceRecordSet == null) {
                                      return false;
                                    }
                                    return resourceRecordSet
                                        .getName()
                                        .equals(recordNameCaptor.getValue());
                                  }
                                })
                            .toList());
              }
            });

    when(changes.create(anyString(), anyString(), changeCaptor.capture()))
        .thenReturn(createChangeRequest);
    // Change our stub zone when a request to change the records is executed
    when(createChangeRequest.execute())
        .thenAnswer(
            new Answer<Change>() {
              @Override
              public Change answer(InvocationOnMock invocationOnMock) throws IOException {
                Change requestedChange = changeCaptor.getValue();
                ImmutableSet<ResourceRecordSet> toDelete =
                    ImmutableSet.copyOf(requestedChange.getDeletions());
                ImmutableSet<ResourceRecordSet> toAdd =
                    ImmutableSet.copyOf(requestedChange.getAdditions());
                // Fail if the records to delete has records that aren't in the stub zone.
                // This matches documented Google Cloud DNS behavior.
                if (!Sets.difference(toDelete, stubZone).isEmpty()) {
                  throw new IOException();
                }
                stubZone =
                    Sets.union(Sets.difference(stubZone, toDelete).immutableCopy(), toAdd)
                        .immutableCopy();
                return requestedChange;
              }
            });
  }

  private void verifyZone(ImmutableSet<ResourceRecordSet> expectedRecords) throws Exception {
    // Trigger zone changes
    writer.close();

    assertThat(stubZone).containsExactlyElementsIn(expectedRecords);
  }

  /** Returns a a zone cut with records for a domain */
  private static ImmutableSet<ResourceRecordSet> fakeDomainRecords(
      String domainName,
      int v4InBailiwickNameservers,
      int v6InBailiwickNameservers,
      int externalNameservers,
      int dsRecords) {
    ImmutableSet.Builder<ResourceRecordSet> recordSetBuilder = new ImmutableSet.Builder<>();

    // Add IPv4 in-bailiwick nameservers
    if (v4InBailiwickNameservers > 0) {
      ImmutableList.Builder<String> nameserverHostnames = new ImmutableList.Builder<>();
      for (int i = 0; i < v4InBailiwickNameservers; i++) {
        nameserverHostnames.add(i + ".ip4." + domainName + ".");
      }

      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("NS")
              .setName(domainName + ".")
              .setTtl((int) DEFAULT_TTL.getStandardSeconds())
              .setRrdatas(nameserverHostnames.build()));

      // Add glue for IPv4 in-bailiwick nameservers
      for (int i = 0; i < v4InBailiwickNameservers; i++) {
        recordSetBuilder.add(
            new ResourceRecordSet()
                .setKind("dns#resourceRecordSet")
                .setType("A")
                .setName(i + ".ip4." + domainName + ".")
                .setTtl((int) DEFAULT_TTL.getStandardSeconds())
                .setRrdatas(ImmutableList.of(IPv4.toString())));
      }
    }

    // Add IPv6 in-bailiwick nameservers
    if (v6InBailiwickNameservers > 0) {
      ImmutableList.Builder<String> nameserverHostnames = new ImmutableList.Builder<>();
      for (int i = 0; i < v6InBailiwickNameservers; i++) {
        nameserverHostnames.add(i + ".ip6." + domainName + ".");
      }

      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("NS")
              .setName(domainName + ".")
              .setTtl((int) DEFAULT_TTL.getStandardSeconds())
              .setRrdatas(nameserverHostnames.build()));

      // Add glue for IPv6 in-bailiwick nameservers
      for (int i = 0; i < v6InBailiwickNameservers; i++) {
        recordSetBuilder.add(
            new ResourceRecordSet()
                .setKind("dns#resourceRecordSet")
                .setType("AAAA")
                .setName(i + ".ip6." + domainName + ".")
                .setTtl((int) DEFAULT_TTL.getStandardSeconds())
                .setRrdatas(ImmutableList.of(IPv6.toString())));
      }
    }

    // Add external nameservers
    if (externalNameservers > 0) {
      ImmutableList.Builder<String> nameserverHostnames = new ImmutableList.Builder<>();
      for (int i = 0; i < externalNameservers; i++) {
        nameserverHostnames.add(i + ".external.");
      }

      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("NS")
              .setName(domainName + ".")
              .setTtl((int) DEFAULT_TTL.getStandardSeconds())
              .setRrdatas(nameserverHostnames.build()));
    }

    // Add DS records
    if (dsRecords > 0) {
      ImmutableList.Builder<String> dsRecordData = new ImmutableList.Builder<>();

      for (int i = 0; i < dsRecords; i++) {
        dsRecordData.add(
            DelegationSignerData.create(
                    i, DS_DATA.getAlgorithm(), DS_DATA.getDigestType(), DS_DATA.getDigest())
                .toRrData());
      }
      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("DS")
              .setName(domainName + ".")
              .setTtl((int) DEFAULT_TTL.getStandardSeconds())
              .setRrdatas(dsRecordData.build()));
    }

    return recordSetBuilder.build();
  }

  /** Returns a domain to be persisted in the datastore. */
  private static DomainResource fakeDomain(
      String domainName, ImmutableSet<HostResource> nameservers, int numDsRecords) {
    ImmutableSet.Builder<DelegationSignerData> dsDataBuilder = new ImmutableSet.Builder<>();

    for (int i = 0; i < numDsRecords; i++) {
      dsDataBuilder.add(
          DelegationSignerData.create(
              i, DS_DATA.getAlgorithm(), DS_DATA.getDigestType(), DS_DATA.getDigest()));
    }

    ImmutableSet.Builder<Ref<HostResource>> hostResourceRefBuilder = new ImmutableSet.Builder<>();
    for (HostResource nameserver : nameservers) {
      hostResourceRefBuilder.add(Ref.create(nameserver));
    }

    return newDomainResource(domainName)
        .asBuilder()
        .setNameservers(hostResourceRefBuilder.build())
        .setDsData(dsDataBuilder.build())
        .build();
  }

  /** Returns a nameserver used for its NS record. */
  private static HostResource fakeHost(String nameserver, InetAddress... addresses) {
    return newHostResource(nameserver)
        .asBuilder()
        .setInetAddresses(ImmutableSet.copyOf(addresses))
        .build();
  }

  @Test
  public void testLoadDomain_nonExistentDomain() throws Exception {
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.<ResourceRecordSet>of());
  }

  @Test
  public void testLoadDomain_noDsDataOrNameservers() throws Exception {
    persistResource(fakeDomain("example.tld", ImmutableSet.<HostResource>of(), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 0, 0));
  }

  @Test
  public void testLoadDomain_deleteOldData() throws Exception {
    stubZone = fakeDomainRecords("example.tld", 2, 2, 2, 2);
    persistResource(fakeDomain("example.tld", ImmutableSet.<HostResource>of(), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 0, 0));
  }

  @Test
  public void testLoadDomain_withExternalNs() throws Exception {
    persistResource(
        fakeDomain("example.tld", ImmutableSet.of(persistResource(fakeHost("0.external"))), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 1, 0));
  }

  @Test
  public void testLoadDomain_withDsData() throws Exception {
    persistResource(
        fakeDomain("example.tld", ImmutableSet.of(persistResource(fakeHost("0.external"))), 1));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 1, 1));
  }

  @Test
  public void testLoadDomain_withInBailiwickNs_IPv4() throws Exception {
    persistResource(
            fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0))
        .asBuilder()
        .addSubordinateHost("0.ip4.example.tld")
        .build();
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 1, 0, 0, 0));
  }

  @Test
  public void testLoadDomain_withInBailiwickNs_IPv6() throws Exception {
    persistResource(
            fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip6.example.tld", IPv6))),
                0))
        .asBuilder()
        .addSubordinateHost("0.ip6.example.tld")
        .build();
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 1, 0, 0));
  }

  @Test
  public void testLoadHost_externalHost() throws Exception {
    writer.publishHost("ns1.example.com");

    // external hosts should not be published in our zone
    verifyZone(ImmutableSet.<ResourceRecordSet>of());
  }

  @Test
  public void testLoadHost_removeStaleNsRecords() throws Exception {
    // Initialize the zone with both NS records
    stubZone = fakeDomainRecords("example.tld", 2, 0, 0, 0);

    // Model the domain with only one NS record -- this is equivalent to creating it
    // with two NS records and then deleting one
    persistResource(
            fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0))
        .asBuilder()
        .addSubordinateHost("0.ip4.example.tld")
        .build();

    // Ask the writer to delete the deleted NS record and glue
    writer.publishHost("1.ip4.example.tld");

    verifyZone(fakeDomainRecords("example.tld", 1, 0, 0, 0));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void retryMutateZoneOnError() throws Exception {
    try (CloudDnsWriter spyWriter = spy(writer)) {
      when(mutateZoneCallable.call()).thenThrow(ZoneStateException.class).thenReturn(null);
      when(spyWriter.getMutateZoneCallback(
              Matchers.<ImmutableMap<String, ImmutableSet<ResourceRecordSet>>>any()))
          .thenReturn(mutateZoneCallable);
    }

    verify(mutateZoneCallable, times(2)).call();
  }

  @Test
  public void testLoadDomain_withClientHold() throws Exception {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_HOLD)
            .build());
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.<ResourceRecordSet>of());
  }

  @Test
  public void testLoadDomain_withServerHold() throws Exception {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_HOLD)
            .build());

    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.<ResourceRecordSet>of());
  }

  @Test
  public void testLoadDomain_withPendingDelete() throws Exception {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.<ResourceRecordSet>of());
  }
}
