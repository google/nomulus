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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.whois.WhoisTestData.loadFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link NameserverWhoisResponse}. */
class NameserverWhoisResponseTest {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private Host host1;
  private Host host2;
  private Host host3;

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("example", "Hänsel & Gretel Registrar, Inc.", Registrar.Type.REAL, 8L);
    persistResource(loadRegistrar("example").asBuilder().setUrl("http://my.fake.url").build());
    createTld("tld");
    Domain domain = persistResource(DatabaseHelper.newDomain("zobo.tld"));

    host1 =
        new Host.Builder()
            .setHostName("ns1.example.tld")
            .setPersistedCurrentSponsorRegistrarId("example")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.123"),
                    InetAddresses.forString("2001:0DB8::1")))
            .setRepoId("1-EXAMPLE")
            .build();

    host2 =
        new Host.Builder()
            .setHostName("ns2.example.tld")
            .setPersistedCurrentSponsorRegistrarId("example")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.123"),
                    InetAddresses.forString("2001:0DB8::1")))
            .setRepoId("2-EXAMPLE")
            .build();

    host3 =
        new Host.Builder()
            .setHostName("ns1.zobo.tld")
            .setSuperordinateDomain(domain.createVKey())
            .setPersistedCurrentSponsorRegistrarId("example")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.123"),
                    InetAddresses.forString("2001:0DB8::1")))
            .setRepoId("3-EXAMPLE")
            .build();
  }

  @Test
  void testGetTextOutput() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(host1, clock.nowUtc());
    assertThat(
            nameserverWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_nameserver.txt"), 1));
  }

  @Test
  void testGetMultipleNameserversResponse() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(ImmutableList.of(host1, host2), clock.nowUtc());
    assertThat(
            nameserverWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_multiple_nameservers.txt"), 2));
  }

  @Test
  void testSubordinateDomains() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(host3, clock.nowUtc());
    assertThat(
            nameserverWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_subord_nameserver.txt"), 1));
  }
}
