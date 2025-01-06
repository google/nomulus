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

package google.registry.dns.writer.dnsupdate;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.domain.Domain;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.RRset;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;
import org.xbill.DNS.Update;

/** Unit tests for {@link DnsUpdateWriter}. */
@ExtendWith(MockitoExtension.class)
public class DnsUpdateWriterTest {

  @RegisterExtension
  public final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  // Base16 encoded sha1 digest. Must have 40 chars (20 bytes after decoding)
  private static final String BASE16_SHA1_DIGEST = "0123".repeat(10);

  @Mock private DnsMessageTransport mockResolver;
  @Captor private ArgumentCaptor<Update> updateCaptor;

  private final FakeClock clock = new FakeClock(DateTime.parse("1971-01-01TZ"));

  private DnsUpdateWriter writer;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("tld");
    when(mockResolver.send(any(Update.class))).thenReturn(messageWithResponseCode(Rcode.NOERROR));

    writer = new DnsUpdateWriter(
        "tld", Duration.ZERO, Duration.ZERO, Duration.ZERO, mockResolver, clock);
  }

  @Test
  void testPublishDomainCreate_publishesNameServers() throws Exception {
    Host host1 = persistActiveHost("ns1.example.tld");
    Host host2 = persistActiveHost("ns2.example.tld");
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host1.createVKey(), host2.createVKey()))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.NS,
        Duration.ZERO.getStandardSeconds(),
        "ns1.example.tld.",
        "ns2.example.tld.");
    assertThatTotalUpdateSetsIs(update, 2); // The delete and NS sets
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testPublishAtomic_noCommit() {
    Host host1 = persistActiveHost("ns.example1.tld");
    Domain domain1 =
        persistActiveDomain("example1.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host1.createVKey()))
            .build();
    persistResource(domain1);

    Host host2 = persistActiveHost("ns.example2.tld");
    Domain domain2 =
        persistActiveDomain("example2.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host2.createVKey()))
            .build();
    persistResource(domain2);

    writer.publishDomain("example1.tld");
    writer.publishDomain("example2.tld");

    verifyNoInteractions(mockResolver);
  }

  @Test
  void testPublishAtomic_oneUpdate() throws Exception {
    Host host1 = persistActiveHost("ns.example1.tld");
    Domain domain1 =
        persistActiveDomain("example1.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host1.createVKey()))
            .build();
    persistResource(domain1);

    Host host2 = persistActiveHost("ns.example2.tld");
    Domain domain2 =
        persistActiveDomain("example2.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(host2.createVKey()))
            .build();
    persistResource(domain2);

    writer.publishDomain("example1.tld");
    writer.publishDomain("example2.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example1.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "example2.tld.", Type.ANY);
    assertThatUpdateAdds(
        update, "example1.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns.example1.tld.");
    assertThatUpdateAdds(
        update, "example2.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns.example2.tld.");
    assertThatTotalUpdateSetsIs(update, 4); // The delete and NS sets for each TLD
  }

  @Test
  void testPublishDomainCreate_publishesDelegationSigner_usesDefaultTtl() throws Exception {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .setDsData(
                ImmutableSet.of(DomainDsData.create(1, 3, 1, base16().decode(BASE16_SHA1_DIGEST))))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update, "example.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns1.example.tld.");
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.DS,
        Duration.ZERO.getStandardSeconds(),
        "1 3 1 " + BASE16_SHA1_DIGEST);
    assertThatTotalUpdateSetsIs(update, 3); // The delete, the NS, and DS sets
  }

  @Test
  void testPublishDomainCreate_publishesDelegationSigner_usesTldConfiguredTtl() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDnsNsTtl(Duration.millis(500))
            .setDnsDsTtl(Duration.millis(400))
            .build());
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .setDsData(
                ImmutableSet.of(DomainDsData.create(1, 3, 1, base16().decode(BASE16_SHA1_DIGEST))))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.NS,
        Duration.millis(500).getStandardSeconds(),
        "ns1.example.tld.");
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.DS,
        Duration.millis(400).getStandardSeconds(),
        "1 3 1 " + BASE16_SHA1_DIGEST);
    assertThatTotalUpdateSetsIs(update, 3); // The delete, the NS, and DS sets
  }

  @Test
  void testPublishDomainWhenNotActive_removesDnsRecords() throws Exception {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_HOLD)
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 1); // Just the delete set
  }

  @Test
  void testPublishDomainDelete_removesDnsRecords() throws Exception {
    persistDeletedDomain("example.tld", clock.nowUtc().minusDays(1));

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 1); // Just the delete set
  }

  @Test
  void testPublishHostCreate_publishesAddressRecords_usesDefaultTtl() throws Exception {
    Host host =
        persistResource(
            newHost("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());
    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addNameserver(host.createVKey())
            .build());

    writer.publishHost("ns1.example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.A,
        Duration.ZERO.getStandardSeconds(),
        "10.0.0.1",
        "10.1.0.1");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.AAAA,
        Duration.ZERO.getStandardSeconds(),
        "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatUpdateAdds(
        update, "example.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns1.example.tld.");
    assertThatTotalUpdateSetsIs(update, 5);
  }

  @Test
  void testPublishHostCreate_publishesAddressRecords_usesTldConfiguredTtl() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDnsAPlusAaaaTtl(Duration.millis(500))
            .setDnsNsTtl(Duration.millis(400))
            .build());
    Host host =
        persistResource(
            newHost("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());
    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addNameserver(host.createVKey())
            .build());

    writer.publishHost("ns1.example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.A,
        Duration.millis(500).getStandardSeconds(),
        "10.0.0.1",
        "10.1.0.1");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.AAAA,
        Duration.millis(500).getStandardSeconds(),
        "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.NS,
        Duration.millis(400).getStandardSeconds(),
        "ns1.example.tld.");
    assertThatTotalUpdateSetsIs(update, 5);
  }

  @Test
  void testPublishHostDelete_removesDnsRecords() throws Exception {
    persistDeletedHost("ns1.example.tld", clock.nowUtc().minusDays(1));
    persistActiveDomain("example.tld");

    writer.publishHost("ns1.example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 2); // Just the delete set
  }

  @Test
  void testPublishHostDelete_removesGlueRecords() throws Exception {
    persistDeletedHost("ns1.example.tld", clock.nowUtc().minusDays(1));
    persistResource(
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.com").createVKey()))
            .build());

    writer.publishHost("ns1.example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update, "example.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns1.example.com.");
    assertThatTotalUpdateSetsIs(update, 3);
  }

  @Test
  void testPublishDomainExternalAndInBailiwickNameServer() throws Exception {
    Host externalNameserver = persistResource(newHost("ns1.example.com"));
    Host inBailiwickNameserver =
        persistResource(
            newHost("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());

    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addNameservers(
                ImmutableSet.of(
                    externalNameserver.createVKey(), inBailiwickNameserver.createVKey()))
            .build());

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update,
        "example.tld.",
        Type.NS,
        Duration.ZERO.getStandardSeconds(),
        "ns1.example.com.",
        "ns1.example.tld.");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.A,
        Duration.ZERO.getStandardSeconds(),
        "10.0.0.1",
        "10.1.0.1");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.AAAA,
        Duration.ZERO.getStandardSeconds(),
        "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatTotalUpdateSetsIs(update, 5);
  }

  @Test
  void testPublishDomainDeleteOrphanGlues() throws Exception {
    Host inBailiwickNameserver =
        persistResource(
            newHost("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());

    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addSubordinateHost("foo.example.tld")
            .addNameserver(inBailiwickNameserver.createVKey())
            .build());

    writer.publishDomain("example.tld");
    writer.commit();

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "foo.example.tld.", Type.ANY);
    assertThatUpdateAdds(
        update, "example.tld.", Type.NS, Duration.ZERO.getStandardSeconds(), "ns1.example.tld.");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.A,
        Duration.ZERO.getStandardSeconds(),
        "10.0.0.1",
        "10.1.0.1");
    assertThatUpdateAdds(
        update,
        "ns1.example.tld.",
        Type.AAAA,
        Duration.ZERO.getStandardSeconds(),
        "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatTotalUpdateSetsIs(update, 6);
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @SuppressWarnings("AssertThrowsMultipleStatements")
  @Test
  void testPublishDomainFails_whenDnsUpdateReturnsError() throws Exception {
    Domain domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(persistActiveHost("ns1.example.tld").createVKey()))
            .build();
    persistResource(domain);
    when(mockResolver.send(any(Message.class))).thenReturn(messageWithResponseCode(Rcode.SERVFAIL));
    VerifyException thrown =
        assertThrows(
            VerifyException.class,
            () -> {
              writer.publishDomain("example.tld");
              writer.commit();
            });
    assertThat(thrown).hasMessageThat().contains("SERVFAIL");
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @SuppressWarnings("AssertThrowsMultipleStatements")
  @Test
  void testPublishHostFails_whenDnsUpdateReturnsError() throws Exception {
    Host host =
        persistActiveSubordinateHost("ns1.example.tld", persistActiveDomain("example.tld"))
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("10.0.0.1")))
            .build();
    persistResource(host);
    when(mockResolver.send(any(Message.class))).thenReturn(messageWithResponseCode(Rcode.SERVFAIL));
    VerifyException thrown =
        assertThrows(
            VerifyException.class,
            () -> {
              writer.publishHost("ns1.example.tld");
              writer.commit();
            });
    assertThat(thrown).hasMessageThat().contains("SERVFAIL");
  }

  private static void assertThatUpdatedZoneIs(Update update, String zoneName) {
    List<Record> zoneRecords = update.getSection(Section.ZONE);
    assertThat(zoneRecords.get(0).getName().toString()).isEqualTo(zoneName);
  }

  private static void assertThatTotalUpdateSetsIs(Update update, int count) {
    assertThat(update.getSectionRRsets(Section.UPDATE)).hasSize(count);
  }

  private static void assertThatUpdateDeletes(Update update, String resourceName, int recordType) {
    ImmutableList<Record> deleted = findUpdateRecords(update, resourceName, recordType);
    // There's only an empty (i.e. "delete") record.
    assertThat(deleted.get(0).rdataToString()).hasLength(0);
    assertThat(deleted).hasSize(1);
  }

  private static void assertThatUpdateAdds(
      Update update, String resourceName, int recordType, Long ttl, String... resourceData) {
    ArrayList<String> expectedData = new ArrayList<>();
    Collections.addAll(expectedData, resourceData);

    ArrayList<String> actualData = new ArrayList<>();
    for (Record record : findUpdateRecords(update, resourceName, recordType)) {
      actualData.add(record.rdataToString());
      assertThat(record.getTTL()).isEqualTo(ttl);
    }
    assertThat(actualData).containsExactlyElementsIn(expectedData);
  }

  private static ImmutableList<Record> findUpdateRecords(
      Update update, String resourceName, int recordType) {
    for (RRset set : update.getSectionRRsets(Section.UPDATE)) {
      if (set.getName().toString().equals(resourceName) && set.getType() == recordType) {
        return ImmutableList.copyOf(set.rrs());
      }
    }
    assertWithMessage(
            "No record set found for resource '%s' type '%s'",
            resourceName, Type.string(recordType))
        .fail();
    throw new AssertionError();
  }

  private static Message messageWithResponseCode(int responseCode) {
    Message message = new Message();
    message.getHeader().setOpcode(Opcode.UPDATE);
    message.getHeader().setFlag(Flags.QR);
    message.getHeader().setRcode(responseCode);
    return message;
  }
}
