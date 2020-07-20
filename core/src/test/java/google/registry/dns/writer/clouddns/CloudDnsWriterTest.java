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

package google.registry.dns.writer.clouddns;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.services.dns.Dns;
import com.google.api.services.dns.model.Change;
import com.google.api.services.dns.model.ResourceRecordSet;
import com.google.api.services.dns.model.ResourceRecordSetsListResponse;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.RateLimiter;
import google.registry.dns.writer.clouddns.CloudDnsWriter.ZoneStateException;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import google.registry.util.Retrier;
import google.registry.util.SystemClock;
import google.registry.util.SystemSleeper;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Test case for {@link CloudDnsWriter}. */
@ExtendWith(MockitoExtension.class)
public class CloudDnsWriterTest {

  @RegisterExtension
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  private static final Inet4Address IPv4 = (Inet4Address) InetAddresses.forString("127.0.0.1");
  private static final Inet6Address IPv6 = (Inet6Address) InetAddresses.forString("::1");
  private static final Duration DEFAULT_A_TTL = Duration.standardSeconds(11);
  private static final Duration DEFAULT_NS_TTL = Duration.standardSeconds(222);
  private static final Duration DEFAULT_DS_TTL = Duration.standardSeconds(3333);

  @Mock private Dns dnsConnection;
  @Mock private Dns.ResourceRecordSets resourceRecordSets;
  @Mock private Dns.Changes changes;
  @Mock private Dns.Changes.Create createChangeRequest;
  @Captor ArgumentCaptor<String> zoneNameCaptor;
  @Captor ArgumentCaptor<Change> changeCaptor;

  private CloudDnsWriter writer;
  private ImmutableSet<ResourceRecordSet> stubZone;

  /*
   * Because of multi-threading in the CloudDnsWriter, we need to return a different instance of
   * List for every request, with its own ArgumentCaptor. Otherwise, we can't separate the arguments
   * of the various Lists
   */
  private Dns.ResourceRecordSets.List newListResourceRecordSetsRequestMock() throws Exception {
    Dns.ResourceRecordSets.List listResourceRecordSetsRequest =
        mock(Dns.ResourceRecordSets.List.class);
    ArgumentCaptor<String> recordNameCaptor = ArgumentCaptor.forClass(String.class);
    when(listResourceRecordSetsRequest.setName(recordNameCaptor.capture()))
        .thenReturn(listResourceRecordSetsRequest);
    // Return records from our stub zone when a request to list the records is executed
    when(listResourceRecordSetsRequest.execute())
        .thenAnswer(
            invocationOnMock ->
                new ResourceRecordSetsListResponse()
                    .setRrsets(
                        stubZone
                            .stream()
                            .filter(
                                rs ->
                                    rs != null && rs.getName().equals(recordNameCaptor.getValue()))
                            .collect(toImmutableList())));
    return listResourceRecordSetsRequest;
  }

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    writer =
        new CloudDnsWriter(
            dnsConnection,
            "projectId",
            "triple.secret.tld", // used by testInvalidZoneNames()
            DEFAULT_A_TTL,
            DEFAULT_NS_TTL,
            DEFAULT_DS_TTL,
            RateLimiter.create(20),
            10, // max num threads
            new SystemClock(),
            new Retrier(new SystemSleeper(), 5));

    // Create an empty zone.
    stubZone = ImmutableSet.of();

    when(dnsConnection.changes()).thenReturn(changes);
    when(dnsConnection.resourceRecordSets()).thenReturn(resourceRecordSets);
    when(resourceRecordSets.list(anyString(), anyString()))
        .thenAnswer(invocationOnMock -> newListResourceRecordSetsRequestMock());
    when(changes.create(anyString(), zoneNameCaptor.capture(), changeCaptor.capture()))
        .thenReturn(createChangeRequest);
    // Change our stub zone when a request to change the records is executed
    when(createChangeRequest.execute())
        .thenAnswer(
            invocationOnMock -> {
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
            });
  }

  private void verifyZone(ImmutableSet<ResourceRecordSet> expectedRecords) {
    // Trigger zone changes
    writer.commit();

    assertThat(stubZone).containsExactlyElementsIn(expectedRecords);
  }

  /** Returns a a zone cut with records for a domain and given nameservers, with no glue records. */
  private static ImmutableSet<ResourceRecordSet> fakeDomainRecords(
      String domainName, String... nameservers) {
    ImmutableSet.Builder<ResourceRecordSet> recordSetBuilder = new ImmutableSet.Builder<>();
    if (nameservers.length > 0) {
      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("NS")
              .setName(domainName + ".")
              .setTtl(222)
              .setRrdatas(ImmutableList.copyOf(nameservers)));
    }
    return recordSetBuilder.build();
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
              .setTtl(222)
              .setRrdatas(nameserverHostnames.build()));

      // Add glue for IPv4 in-bailiwick nameservers
      for (int i = 0; i < v4InBailiwickNameservers; i++) {
        recordSetBuilder.add(
            new ResourceRecordSet()
                .setKind("dns#resourceRecordSet")
                .setType("A")
                .setName(i + ".ip4." + domainName + ".")
                .setTtl(11)
                .setRrdatas(ImmutableList.of("127.0.0.1")));
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
              .setTtl(222)
              .setRrdatas(nameserverHostnames.build()));
      // Add glue for IPv6 in-bailiwick nameservers
      for (int i = 0; i < v6InBailiwickNameservers; i++) {
        recordSetBuilder.add(
            new ResourceRecordSet()
                .setKind("dns#resourceRecordSet")
                .setType("AAAA")
                .setName(i + ".ip6." + domainName + ".")
                .setTtl(11)
                .setRrdatas(ImmutableList.of("0:0:0:0:0:0:0:1")));
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
              .setTtl(222)
              .setRrdatas(nameserverHostnames.build()));
    }

    // Add DS records
    if (dsRecords > 0) {
      ImmutableList.Builder<String> dsRecordData = new ImmutableList.Builder<>();

      for (int i = 0; i < dsRecords; i++) {
        dsRecordData.add(
            DelegationSignerData.create(i, 3, 1, base16().decode("1234567890ABCDEF")).toRrData());
      }
      recordSetBuilder.add(
          new ResourceRecordSet()
              .setKind("dns#resourceRecordSet")
              .setType("DS")
              .setName(domainName + ".")
              .setTtl(3333)
              .setRrdatas(dsRecordData.build()));
    }

    return recordSetBuilder.build();
  }

  /** Returns a domain to be persisted in Datastore. */
  private static DomainBase fakeDomain(
      String domainName, ImmutableSet<HostResource> nameservers, int numDsRecords) {
    ImmutableSet.Builder<DelegationSignerData> dsDataBuilder = new ImmutableSet.Builder<>();

    for (int i = 0; i < numDsRecords; i++) {
      dsDataBuilder.add(DelegationSignerData.create(i, 3, 1, base16().decode("1234567890ABCDEF")));
    }

    ImmutableSet.Builder<VKey<HostResource>> hostResourceRefBuilder = new ImmutableSet.Builder<>();
    for (HostResource nameserver : nameservers) {
      hostResourceRefBuilder.add(nameserver.createVKey());
    }

    return newDomainBase(domainName)
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

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadDomain_nonExistentDomain() {
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.of());
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadDomain_noDsDataOrNameservers() {
    persistResource(fakeDomain("example.tld", ImmutableSet.of(), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 0, 0));
  }

  @Test
  void testLoadDomain_deleteOldData() {
    stubZone = fakeDomainRecords("example.tld", 2, 2, 2, 2);
    persistResource(fakeDomain("example.tld", ImmutableSet.of(), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 0, 0));
  }

  @Test
  void testLoadDomain_withExternalNs() {
    persistResource(
        fakeDomain("example.tld", ImmutableSet.of(persistResource(fakeHost("0.external"))), 0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 1, 0));
  }

  @Test
  void testLoadDomain_withDsData() {
    persistResource(
        fakeDomain("example.tld", ImmutableSet.of(persistResource(fakeHost("0.external"))), 1));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 0, 1, 1));
  }

  @Test
  void testLoadDomain_withInBailiwickNs_IPv4() {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addSubordinateHost("0.ip4.example.tld")
            .build());
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 1, 0, 0, 0));
  }

  @Test
  void testLoadDomain_withInBailiwickNs_IPv6() {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip6.example.tld", IPv6))),
                0)
            .asBuilder()
            .addSubordinateHost("0.ip6.example.tld")
            .build());
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", 0, 1, 0, 0));
  }

  @Test
  void testLoadDomain_withNameserveThatEndsWithDomainName() {
    persistResource(
        fakeDomain(
            "example.tld",
            ImmutableSet.of(persistResource(fakeHost("ns.another-example.tld", IPv4))),
            0));
    writer.publishDomain("example.tld");

    verifyZone(fakeDomainRecords("example.tld", "ns.another-example.tld."));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadHost_externalHost() {
    writer.publishHost("ns1.example.com");

    // external hosts should not be published in our zone
    verifyZone(ImmutableSet.of());
  }

  @Test
  void testLoadHost_removeStaleNsRecords() {
    // Initialize the zone with both NS records
    stubZone = fakeDomainRecords("example.tld", 2, 0, 0, 0);

    // Model the domain with only one NS record -- this is equivalent to creating it
    // with two NS records and then deleting one
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addSubordinateHost("0.ip4.example.tld")
            .build());

    // Ask the writer to delete the deleted NS record and glue
    writer.publishHost("1.ip4.example.tld");

    verifyZone(fakeDomainRecords("example.tld", 1, 0, 0, 0));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void retryMutateZoneOnError() {
    CloudDnsWriter spyWriter = spy(writer);
    // First call - throw. Second call - do nothing.
    doThrow(ZoneStateException.class)
        .doNothing()
        .when(spyWriter)
        .mutateZone(ArgumentMatchers.any());
    spyWriter.commit();

    verify(spyWriter, times(2)).mutateZone(ArgumentMatchers.any());
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadDomain_withClientHold() {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.CLIENT_HOLD)
            .build());
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.of());
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadDomain_withServerHold() {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_HOLD)
            .build());

    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.of());
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testLoadDomain_withPendingDelete() {
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    writer.publishDomain("example.tld");

    verifyZone(ImmutableSet.of());
  }

  @Test
  void testDuplicateRecords() {
    // In publishing DNS records, we can end up publishing information on the same host twice
    // (through a domain change and a host change), so this scenario needs to work.
    persistResource(
        fakeDomain(
                "example.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .addSubordinateHost("0.ip4.example.tld")
            .build());
    writer.publishDomain("example.tld");
    writer.publishHost("0.ip4.example.tld");

    verifyZone(fakeDomainRecords("example.tld", 1, 0, 0, 0));
  }

  @Test
  void testInvalidZoneNames() {
    createTld("triple.secret.tld");
    persistResource(
        fakeDomain(
                "example.triple.secret.tld",
                ImmutableSet.of(persistResource(fakeHost("0.ip4.example.tld", IPv4))),
                0)
            .asBuilder()
            .build());
    writer.publishDomain("example.triple.secret.tld");
    writer.commit();
    assertThat(zoneNameCaptor.getValue()).isEqualTo("triple-secret-tld");
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testEmptyCommit() {
    writer.commit();
    verify(dnsConnection, times(0)).changes();
  }
}
