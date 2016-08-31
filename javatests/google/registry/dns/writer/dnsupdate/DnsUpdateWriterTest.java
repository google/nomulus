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

package google.registry.dns.writer.dnsupdate;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assert_;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistActiveSubordinateHost;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import com.googlecode.objectify.Ref;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.ofy.Ofy;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import java.util.ArrayList;
import java.util.Iterator;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
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
@RunWith(MockitoJUnitRunner.class)
public class DnsUpdateWriterTest {

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastore().withTaskQueue().build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final InjectRule inject = new InjectRule();

  @Mock
  private DnsMessageTransport mockResolver;

  @Captor
  private ArgumentCaptor<Update> updateCaptor;

  private final FakeClock clock = new FakeClock(DateTime.parse("1971-01-01TZ"));

  private DnsUpdateWriter writer;

  @Before
  public void setUp() throws Exception {
    inject.setStaticField(Ofy.class, "clock", clock);

    createTld("tld");
    when(mockResolver.send(any(Update.class))).thenReturn(messageWithResponseCode(Rcode.NOERROR));

    writer = new DnsUpdateWriter(Duration.ZERO, mockResolver, clock);
  }

  @Test
  public void testPublishDomainCreate_publishesNameServers() throws Exception {
    HostResource host1 = persistActiveHost("ns1.example.tld");
    HostResource host2 = persistActiveHost("ns2.example.tld");
    DomainResource domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(Ref.create(host1), Ref.create(host2)))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.tld.", "ns2.example.tld.");
    assertThatTotalUpdateSetsIs(update, 2); // The delete and NS sets
  }

  @Test
  public void testPublishDomainCreate_publishesDelegationSigner() throws Exception {
    DomainResource domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(Ref.create(persistActiveHost("ns1.example.tld"))))
            .setDsData(
                ImmutableSet.of(
                    DelegationSignerData.create(1, 3, 1, base16().decode("0123456789ABCDEF"))))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.tld.");
    assertThatUpdateAdds(update, "example.tld.", Type.DS, "1 3 1 0123456789ABCDEF");
    assertThatTotalUpdateSetsIs(update, 3); // The delete, the NS, and DS sets
  }

  @Test
  public void testPublishDomainWhenNotActive_removesDnsRecords() throws Exception {
    DomainResource domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .addStatusValue(StatusValue.SERVER_HOLD)
            .setNameservers(ImmutableSet.of(Ref.create(persistActiveHost("ns1.example.tld"))))
            .build();
    persistResource(domain);

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 1); // Just the delete set
  }

  @Test
  public void testPublishDomainDelete_removesDnsRecords() throws Exception {
    persistDeletedDomain("example.tld", clock.nowUtc());

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 1); // Just the delete set
  }

  @Test
  public void testPublishHostCreate_publishesAddressRecords() throws Exception {
    HostResource host =
        persistResource(
            newHostResource("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());
    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addNameservers(ImmutableSet.of(Ref.create(host)))
            .build());

    writer.publishHost("ns1.example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.A, "10.0.0.1", "10.1.0.1");
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.AAAA, "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.tld.");
    assertThatTotalUpdateSetsIs(update, 5);
  }

  @Test
  public void testPublishHostDelete_removesDnsRecords() throws Exception {
    persistDeletedHost("ns1.example.tld", clock.nowUtc());
    persistActiveDomain("example.tld");

    writer.publishHost("ns1.example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatTotalUpdateSetsIs(update, 2); // Just the delete set
  }

  @Test
  public void testPublishHostDelete_removesGlueRecords() throws Exception {
    persistDeletedHost("ns1.example.tld", clock.nowUtc());
    persistResource(
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(Ref.create(persistActiveHost("ns1.example.com"))))
            .build());

    writer.publishHost("ns1.example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.com.");
    assertThatTotalUpdateSetsIs(update, 3);
  }

  @Test
  public void testPublishDomainExternalAndInBailiwickNameServer() throws Exception {
    HostResource externalNameserver = persistResource(newHostResource("ns1.example.com"));
    HostResource inBailiwickNameserver =
        persistResource(
            newHostResource("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());

    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addNameservers(
                ImmutableSet.of(Ref.create(externalNameserver), Ref.create(inBailiwickNameserver)))
            .build());

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.com.", "ns1.example.tld.");
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.A, "10.0.0.1", "10.1.0.1");
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.AAAA, "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatTotalUpdateSetsIs(update, 5);
  }

  @Test
  public void testPublishDomainDeleteOrphanGlues() throws Exception {
    HostResource inBailiwickNameserver =
        persistResource(
            newHostResource("ns1.example.tld")
                .asBuilder()
                .setInetAddresses(
                    ImmutableSet.of(
                        InetAddresses.forString("10.0.0.1"),
                        InetAddresses.forString("10.1.0.1"),
                        InetAddresses.forString("fd0e:a5c8:6dfb:6a5e:0:0:0:1")))
                .build());

    persistResource(
        newDomainResource("example.tld")
            .asBuilder()
            .addSubordinateHost("ns1.example.tld")
            .addSubordinateHost("foo.example.tld")
            .addNameservers(ImmutableSet.of(Ref.create(inBailiwickNameserver)))
            .build());

    writer.publishDomain("example.tld");

    verify(mockResolver).send(updateCaptor.capture());
    Update update = updateCaptor.getValue();
    assertThatUpdatedZoneIs(update, "tld.");
    assertThatUpdateDeletes(update, "example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "ns1.example.tld.", Type.ANY);
    assertThatUpdateDeletes(update, "foo.example.tld.", Type.ANY);
    assertThatUpdateAdds(update, "example.tld.", Type.NS, "ns1.example.tld.");
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.A, "10.0.0.1", "10.1.0.1");
    assertThatUpdateAdds(update, "ns1.example.tld.", Type.AAAA, "fd0e:a5c8:6dfb:6a5e:0:0:0:1");
    assertThatTotalUpdateSetsIs(update, 6);
  }

  @Test
  public void testPublishDomainFails_whenDnsUpdateReturnsError() throws Exception {
    DomainResource domain =
        persistActiveDomain("example.tld")
            .asBuilder()
            .setNameservers(ImmutableSet.of(Ref.create(persistActiveHost("ns1.example.tld"))))
            .build();
    persistResource(domain);
    when(mockResolver.send(any(Message.class))).thenReturn(messageWithResponseCode(Rcode.SERVFAIL));
    thrown.expect(VerifyException.class, "SERVFAIL");

    writer.publishDomain("example.tld");
  }

  @Test
  public void testPublishHostFails_whenDnsUpdateReturnsError() throws Exception {
    HostResource host =
        persistActiveSubordinateHost("ns1.example.tld", persistActiveDomain("example.tld"))
            .asBuilder()
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("10.0.0.1")))
            .build();
    persistResource(host);
    when(mockResolver.send(any(Message.class))).thenReturn(messageWithResponseCode(Rcode.SERVFAIL));
    thrown.expect(VerifyException.class, "SERVFAIL");

    writer.publishHost("ns1.example.tld");
  }

  private void assertThatUpdatedZoneIs(Update update, String zoneName) {
    Record[] zoneRecords = update.getSectionArray(Section.ZONE);
    assertThat(zoneRecords[0].getName().toString()).isEqualTo(zoneName);
  }

  private void assertThatTotalUpdateSetsIs(Update update, int count) {
    assertThat(update.getSectionRRsets(Section.UPDATE)).hasLength(count);
  }

  private void assertThatUpdateDeletes(Update update, String resourceName, int recordType) {
    ImmutableList<Record> deleted = findUpdateRecords(update, resourceName, recordType);
    // There's only an empty (i.e. "delete") record.
    assertThat(deleted.get(0).rdataToString()).hasLength(0);
    assertThat(deleted).hasSize(1);
  }

  private void assertThatUpdateAdds(
      Update update, String resourceName, int recordType, String... resourceData) {
    ArrayList<String> expectedData = new ArrayList<>();
    for (String resourceDatum : resourceData) {
      expectedData.add(resourceDatum);
    }

    ArrayList<String> actualData = new ArrayList<>();
    for (Record record : findUpdateRecords(update, resourceName, recordType)) {
      actualData.add(record.rdataToString());
    }
    assertThat(actualData).containsExactlyElementsIn(expectedData);
  }

  private ImmutableList<Record> findUpdateRecords(
      Update update, String resourceName, int recordType) {
    for (RRset set : update.getSectionRRsets(Section.UPDATE)) {
      if (set.getName().toString().equals(resourceName) && set.getType() == recordType) {
        return fixIterator(Record.class, set.rrs());
      }
    }
    assert_().fail(
        "No record set found for resource '%s' type '%s'",
        resourceName, Type.string(recordType));
    throw new AssertionError();
  }

  @SuppressWarnings({"unchecked", "unused"})
  private static <T> ImmutableList<T> fixIterator(Class<T> clazz, final Iterator<?> iterator) {
    return ImmutableList.copyOf((Iterator<T>) iterator);
  }

  private Message messageWithResponseCode(int responseCode) {
    Message message = new Message();
    message.getHeader().setOpcode(Opcode.UPDATE);
    message.getHeader().setFlag(Flags.QR);
    message.getHeader().setRcode(responseCode);
    return message;
  }
}
