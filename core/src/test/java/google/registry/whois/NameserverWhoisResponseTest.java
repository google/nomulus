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
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.FakeClock;
import google.registry.whois.WhoisResponse.WhoisResponseResults;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link NameserverWhoisResponse}. */
class NameserverWhoisResponseTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  private HostResource hostResource1;
  private HostResource hostResource2;

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @BeforeEach
  void beforeEach() {
    persistNewRegistrar("example", "Hänsel & Gretel Registrar, Inc.", Registrar.Type.REAL, 8L);
    persistResource(loadRegistrar("example").asBuilder().setUrl("http://my.fake.url").build());
    createTld("tld");

    hostResource1 =
        new HostResource.Builder()
            .setHostName("ns1.example.tld")
            .setPersistedCurrentSponsorRegistrarId("example")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.123"),
                    InetAddresses.forString("2001:0DB8::1")))
            .setRepoId("1-EXAMPLE")
            .build();

    hostResource2 =
        new HostResource.Builder()
            .setHostName("ns2.example.tld")
            .setPersistedCurrentSponsorRegistrarId("example")
            .setInetAddresses(
                ImmutableSet.of(
                    InetAddresses.forString("192.0.2.123"),
                    InetAddresses.forString("2001:0DB8::1")))
            .setRepoId("2-EXAMPLE")
            .build();
  }

  @Test
  void testGetTextOutput() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(hostResource1, clock.nowUtc());
    assertThat(
            nameserverWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_nameserver.txt"), 1));
  }

  @Test
  void testGetMultipleNameserversResponse() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(ImmutableList.of(hostResource1, hostResource2), clock.nowUtc());
    assertThat(
            nameserverWhoisResponse.getResponse(
                false,
                "Doodle Disclaimer\nI exist so that carriage return\nin disclaimer can be tested."))
        .isEqualTo(WhoisResponseResults.create(loadFile("whois_multiple_nameservers.txt"), 2));
  }
}
