// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistNewRegistrar;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.junit.Assert.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import google.registry.model.domain.DomainBase;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.util.Clock;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

/** Unit tests for {@link RenewDomainCommand}. */
public class RenewDomainCommandTest extends EppToolCommandTestCase<RenewDomainCommand> {

  @Rule public final InjectRule inject = new InjectRule();

  private final Clock clock = new FakeClock(DateTime.parse("2015-04-05T05:05:05Z"));

  @Before
  public void before() {
    inject.setStaticField(Ofy.class, "clock", clock);
    command.clock = clock;
  }

  @Test
  public void testSuccess() throws Exception {
    persistResource(
        persistActiveDomain(
                "domain.tld",
                DateTime.parse("2014-09-05T05:05:05Z"),
                DateTime.parse("2015-09-05T05:05:05Z"))
            .asBuilder()
            .setPersistedCurrentSponsorClientId("NewRegistrar")
            .build());
    runCommandForced("domain.tld");
    eppVerifier
        .expectClientId("NewRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain.tld", "EXPDATE", "2015-09-05", "YEARS", "1"))
        .verifyNoMoreSent();
  }

  private static List<DomainBase> persistThreeDomains() {
    ImmutableList.Builder<DomainBase> domains = new ImmutableList.Builder<>();
    domains.add(
        persistActiveDomain(
            "domain1.tld",
            DateTime.parse("2014-09-05T05:05:05Z"),
            DateTime.parse("2015-09-05T05:05:05Z")));
    domains.add(
        persistActiveDomain(
            "domain2.tld",
            DateTime.parse("2014-11-05T05:05:05Z"),
            DateTime.parse("2015-11-05T05:05:05Z")));
    // The third domain is owned by a different registrar.
    domains.add(
        persistResource(
            newDomainBase("domain3.tld")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("2015-01-05T05:05:05Z"))
                .setRegistrationExpirationTime(DateTime.parse("2016-01-05T05:05:05Z"))
                .setPersistedCurrentSponsorClientId("NewRegistrar")
                .build()));
    return domains.build();
  }

  @Test
  public void testSuccess_multipleDomains_renewsAndUsesEachDomainsRegistrar() throws Exception {
    persistThreeDomains();
    runCommandForced("--period 3", "domain1.tld", "domain2.tld", "domain3.tld");
    eppVerifier
        .expectClientId("TheRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain1.tld", "EXPDATE", "2015-09-05", "YEARS", "3"))
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain2.tld", "EXPDATE", "2015-11-05", "YEARS", "3"))
        .expectClientId("NewRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain3.tld", "EXPDATE", "2016-01-05", "YEARS", "3"))
        .verifyNoMoreSent();
  }

  @Test
  public void testSuccess_multipleDomains_renewsAndUsesSpecifiedRegistrar() throws Exception {
    persistThreeDomains();
    persistNewRegistrar("reg3", "Registrar 3", Registrar.Type.REAL, 9783L);
    runCommandForced("--period 3", "domain1.tld", "domain2.tld", "domain3.tld", "-u", "-c reg3");
    eppVerifier
        .expectClientId("reg3")
        .expectSuperuser()
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain1.tld", "EXPDATE", "2015-09-05", "YEARS", "3"))
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain2.tld", "EXPDATE", "2015-11-05", "YEARS", "3"))
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain3.tld", "EXPDATE", "2016-01-05", "YEARS", "3"))
        .verifyNoMoreSent();
  }

  @Test
  public void testFailure_domainDoesntExist() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("nonexistent.tld"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("Domain 'nonexistent.tld' does not exist or is deleted");
  }

  @Test
  public void testFailure_domainIsDeleted() {
    persistDeletedDomain("deleted.tld", DateTime.parse("2012-10-05T05:05:05Z"));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("deleted.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Domain 'deleted.tld' does not exist or is deleted");
  }

  @Test
  public void testFailure_duplicateDomainSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }

  @Test
  public void testFailure_cantRenewForTenYears() {
    persistActiveDomain(
        "domain.tld",
        DateTime.parse("2014-09-05T05:05:05Z"),
        DateTime.parse("2015-09-05T05:05:05Z"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("domain.tld", "--period 10"));
    assertThat(e).hasMessageThat().isEqualTo("Cannot renew domains for 10 or more years");
  }

  @Test
  public void testFailure_missingDomainNames() {
    assertThrows(ParameterException.class, () -> runCommand("--period 4"));
  }
}
