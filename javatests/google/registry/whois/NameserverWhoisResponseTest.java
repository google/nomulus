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

package google.registry.whois;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.whois.WhoisHelper.loadWhoisTestFile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;
import google.registry.model.host.HostResource;
import google.registry.model.registrar.Registrar;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link NameserverWhoisResponse}. */
@RunWith(JUnit4.class)
public class NameserverWhoisResponseTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  private HostResource hostResource1;
  private HostResource hostResource2;

  private final FakeClock clock = new FakeClock(DateTime.parse("2009-05-29T20:15:00Z"));

  @Before
  public void setUp() {
    persistResource(new Registrar.Builder()
        .setClientIdentifier("example")
        .setRegistrarName("Example Registrar, Inc.")
        .setType(Registrar.Type.REAL)
        .setIanaIdentifier(8L)
        .build());

    createTld("tld");

    hostResource1 = new HostResource.Builder()
        .setFullyQualifiedHostName("NS1.EXAMPLE.tld")
        .setCurrentSponsorClientId("example")
        .setInetAddresses(ImmutableSet.of(
            InetAddresses.forString("192.0.2.123"),
            InetAddresses.forString("2001:0DB8::1")))
        .setRepoId("1-EXAMPLE")
        .build();

    hostResource2 = new HostResource.Builder()
        .setFullyQualifiedHostName("NS2.EXAMPLE.tld")
        .setCurrentSponsorClientId("example")
        .setInetAddresses(ImmutableSet.of(
            InetAddresses.forString("192.0.2.123"),
            InetAddresses.forString("2001:0DB8::1")))
        .setRepoId("2-EXAMPLE")
        .build();
  }

  @Test
  public void testGetTextOutput() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(hostResource1, clock.nowUtc());
    assertThat(nameserverWhoisResponse.getPlainTextOutput(false, "Doodle Disclaimer"))
        .isEqualTo(loadWhoisTestFile("whois_nameserver.txt"));
  }

  @Test
  public void testGetMultipleNameserversResponse() {
    NameserverWhoisResponse nameserverWhoisResponse =
        new NameserverWhoisResponse(ImmutableList.of(hostResource1, hostResource2), clock.nowUtc());
    assertThat(nameserverWhoisResponse.getPlainTextOutput(false, "Doodle Disclaimer"))
        .isEqualTo(loadWhoisTestFile("whois_multiple_nameservers.txt"));
  }
}
