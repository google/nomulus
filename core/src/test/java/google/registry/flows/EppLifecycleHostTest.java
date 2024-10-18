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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.EppMetricSubject.assertThat;
import static google.registry.testing.HostSubject.assertAboutHosts;

import com.google.common.collect.ImmutableMap;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for host lifecycle. */
class EppLifecycleHostTest extends EppTestCase {

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  @Test
  void testLifecycle() throws Exception {
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    assertThatCommand("hello.xml")
        .atTime("2000-06-02T00:00:00Z")
        .hasResponse("greeting.xml", ImmutableMap.of("DATE", "2000-06-02T00:00:00Z"));
    // Note that Hello commands don't set a status code on the response.
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("Hello")
        .and()
        .hasNoStatus();
    assertThatCommand("host_create.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"))
        .atTime("2000-06-02T00:01:00Z")
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of("HOSTNAME", "ns1.example.tld", "CRDATE", "2000-06-02T00:01:00Z"));
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostCreate")
        .and()
        .hasStatus(SUCCESS);
    assertThatCommand("host_info.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"))
        .atTime("2000-06-02T00:02:00Z")
        .hasResponse(
            "host_info_response.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns1.example.tld", "ROID", "1-ROID", "CRDATE", "2000-06-02T00:01:00Z"));
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostInfo")
        .and()
        .hasStatus(SUCCESS);
    assertThatCommand("host_delete.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"))
        .atTime("2000-06-02T00:03:00Z")
        .hasResponse("generic_success_response.xml");
    assertThat(getRecordedEppMetric())
        .hasClientId("NewRegistrar")
        .and()
        .hasCommandName("HostDelete")
        .and()
        .hasStatus(SUCCESS);
    assertThatLogoutSucceeds();
  }

  @Test
  void testRenamingHostToExistingHost_fails() throws Exception {
    createTld("example");
    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");
    // Create the fakesite domain.
    assertThatCommand("contact_create_sh8013.xml")
        .atTime("2000-06-01T00:00:00Z")
        .hasResponse(
            "contact_create_response_sh8013.xml",
            ImmutableMap.of("CRDATE", "2000-06-01T00:00:00Z"));
    assertThatCommand("contact_create_jd1234.xml")
        .atTime("2000-06-01T00:01:00Z")
        .hasResponse("contact_create_response_jd1234.xml");
    assertThatCommand("domain_create_fakesite_no_nameservers.xml")
        .atTime("2000-06-01T00:04:00Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "fakesite.example",
                "CRDATE", "2000-06-01T00:04:00.0Z",
                "EXDATE", "2002-06-01T00:04:00.0Z"));
    assertThatCommand("domain_info_fakesite.xml")
        .atTime("2000-06-05T00:02:00Z")
        .hasResponse("domain_info_response_fakesite_inactive.xml");
    // Add the fakesite subordinate host (requires that domain is already created).
    assertThatCommand("host_create_fakesite.xml")
        .atTime("2000-06-06T00:01:00Z")
        .hasResponse("host_create_response_fakesite.xml");
    // Add the 2nd fakesite subordinate host.
    assertThatCommand("host_create_fakesite2.xml")
        .atTime("2000-06-09T00:01:00Z")
        .hasResponse("host_create_response_fakesite2.xml");
    // Attempt overwriting of 2nd fakesite subordinate host with the 1st.
    assertThatCommand("host_update_fakesite1_to_fakesite2.xml")
        .atTime("2000-06-10T00:01:00Z")
        .hasResponse(
            "response_error.xml",
            ImmutableMap.of(
                "CODE", "2302",
                "MSG", "Object with given ID (ns4.fakesite.example) already exists"));
    // Verify that fakesite hosts still exist in their unmodified states.
    assertThatCommand("host_info_fakesite.xml")
        .atTime("2000-06-11T00:07:00Z")
        .hasResponse("host_info_response_fakesite_ok.xml");
    assertThatCommand("host_info_fakesite2.xml")
        .atTime("2000-06-11T00:08:00Z")
        .hasResponse("host_info_response_fakesite2.xml");
    assertThatLogoutSucceeds();
  }

  @Test
  void testSuccess_multipartTldsWithSharedSuffixes() throws Exception {
    createTlds("bar.foo.tld", "foo.tld", "tld");

    assertThatLoginSucceeds("NewRegistrar", "foo-BAR2");

    assertThatCommand("contact_create_sh8013.xml")
        .atTime("2000-06-01T00:00:00Z")
        .hasResponse(
            "contact_create_response_sh8013.xml",
            ImmutableMap.of("CRDATE", "2000-06-01T00:00:00Z"));
    assertThatCommand("contact_create_jd1234.xml")
        .atTime("2000-06-01T00:01:00Z")
        .hasResponse("contact_create_response_jd1234.xml");

    // Create domain example.bar.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml",
            ImmutableMap.of("DOMAIN", "example.bar.foo.tld"))
        .atTime("2000-06-01T00:02:00.000Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.bar.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.foo.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.foo.tld"))
        .atTime("2000-06-01T00:02:00.001Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.foo.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create domain example.tld
    assertThatCommand(
            "domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"))
        .atTime("2000-06-01T00:02:00.002Z")
        .hasResponse(
            "domain_create_response.xml",
            ImmutableMap.of(
                "DOMAIN", "example.tld",
                "CRDATE", "2000-06-01T00:02:00Z",
                "EXDATE", "2002-06-01T00:02:00Z"));

    // Create host ns1.example.bar.foo.tld
    assertThatCommand(
            "host_create_with_ips.xml", ImmutableMap.of("HOSTNAME", "ns1.example.bar.foo.tld"))
        .atTime("2000-06-01T00:03:00Z")
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of(
                "HOSTNAME", "ns1.example.bar.foo.tld", "CRDATE", "2000-06-01T00:03:00Z"));

    // Create host ns1.example.foo.tld
    assertThatCommand(
            "host_create_with_ips.xml", ImmutableMap.of("HOSTNAME", "ns1.example.foo.tld"))
        .atTime("2000-06-01T00:04:00Z")
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of("HOSTNAME", "ns1.example.foo.tld", "CRDATE", "2000-06-01T00:04:00Z"));

    // Create host ns1.example.tld
    assertThatCommand("host_create_with_ips.xml", ImmutableMap.of("HOSTNAME", "ns1.example.tld"))
        .atTime("2000-06-01T00:05:00Z")
        .hasResponse(
            "host_create_response.xml",
            ImmutableMap.of("HOSTNAME", "ns1.example.tld", "CRDATE", "2000-06-01T00:05:00Z"));

    DateTime timeAfterCreates = DateTime.parse("2000-06-01T00:06:00Z");

    Host exampleBarFooTldHost =
        loadByForeignKey(Host.class, "ns1.example.bar.foo.tld", timeAfterCreates).get();
    Domain exampleBarFooTldDomain =
        loadByForeignKey(Domain.class, "example.bar.foo.tld", timeAfterCreates).get();
    assertAboutHosts()
        .that(exampleBarFooTldHost)
        .hasSuperordinateDomain(exampleBarFooTldDomain.createVKey());
    assertThat(exampleBarFooTldDomain.getSubordinateHosts())
        .containsExactly("ns1.example.bar.foo.tld");

    Host exampleFooTldHost =
        loadByForeignKey(Host.class, "ns1.example.foo.tld", timeAfterCreates).get();
    Domain exampleFooTldDomain =
        loadByForeignKey(Domain.class, "example.foo.tld", timeAfterCreates).get();
    assertAboutHosts()
        .that(exampleFooTldHost)
        .hasSuperordinateDomain(exampleFooTldDomain.createVKey());
    assertThat(exampleFooTldDomain.getSubordinateHosts()).containsExactly("ns1.example.foo.tld");

    Host exampleTldHost = loadByForeignKey(Host.class, "ns1.example.tld", timeAfterCreates).get();
    Domain exampleTldDomain = loadByForeignKey(Domain.class, "example.tld", timeAfterCreates).get();
    assertAboutHosts().that(exampleTldHost).hasSuperordinateDomain(exampleTldDomain.createVKey());
    assertThat(exampleTldDomain.getSubordinateHosts()).containsExactly("ns1.example.tld");

    assertThatLogoutSucceeds();
  }
}
