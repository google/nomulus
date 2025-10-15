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
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistNewRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.domain.Domain;
import google.registry.model.registrar.Registrar;
import google.registry.testing.DatabaseHelper;
import java.util.List;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RenewDomainCommand}. */
public class RenewDomainCommandTest extends EppToolCommandTestCase<RenewDomainCommand> {

  @BeforeEach
  void beforeEach() {
    fakeClock.setTo(DateTime.parse("2015-04-05T05:05:05Z"));
    command.clock = fakeClock;
  }

  @Test
  void testSuccess() throws Exception {
    persistResource(
        persistActiveDomain(
                "domain.tld",
                DateTime.parse("2014-09-05T05:05:05Z"),
                DateTime.parse("2015-09-05T05:05:05Z"))
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    runCommandForced("domain.tld");
    eppVerifier
        .expectRegistrarId("NewRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain.tld", "EXPDATE", "2015-09-05", "YEARS", "1"))
        .verifyNoMoreSent();
  }

  private static List<Domain> persistThreeDomains() {
    ImmutableList.Builder<Domain> domains = new ImmutableList.Builder<>();
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
            DatabaseHelper.newDomain("domain3.tld")
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("2015-01-05T05:05:05Z"))
                .setRegistrationExpirationTime(DateTime.parse("2016-01-05T05:05:05Z"))
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .build()));
    return domains.build();
  }

  @Test
  void testSuccess_multipleDomains_renewsAndUsesEachDomainsRegistrar() throws Exception {
    persistThreeDomains();
    runCommandForced("--period 3", "domain1.tld", "domain2.tld", "domain3.tld");
    eppVerifier
        .expectRegistrarId("TheRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain1.tld", "EXPDATE", "2015-09-05", "YEARS", "3"))
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain2.tld", "EXPDATE", "2015-11-05", "YEARS", "3"))
        .expectRegistrarId("NewRegistrar")
        .verifySent(
            "domain_renew.xml",
            ImmutableMap.of("DOMAIN", "domain3.tld", "EXPDATE", "2016-01-05", "YEARS", "3"))
        .verifyNoMoreSent();
  }

  @Test
  void testSuccess_multipleDomains_renewsAndUsesSpecifiedRegistrar() throws Exception {
    persistThreeDomains();
    persistNewRegistrar("reg3", "Registrar 3", Registrar.Type.REAL, 9783L);
    runCommandForced("--period 3", "domain1.tld", "domain2.tld", "domain3.tld", "-u", "-c reg3");
    eppVerifier
        .expectRegistrarId("reg3")
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
  void testSuccess_withReasonAndRegistrarRequest() throws Exception {
    persistActiveDomain(
        "domain.tld",
        DateTime.parse("2014-09-05T05:05:05Z"),
        DateTime.parse("2015-09-05T05:05:05Z"));
    runCommandForced(
        "domain.tld", "--period=1", "--reason=Renewing test domain", "--registrar_request=true");

    eppVerifier
        .expectRegistrarId("TheRegistrar")
        .verifySent(
            "domain_renew_via_urs.xml",
            ImmutableMap.of(
                "DOMAIN",
                "domain.tld",
                "EXPDATE",
                "2015-09-05",
                "YEARS",
                "1",
                "REASON",
                "Renewing test domain",
                "REQUESTED",
                "true"));
  }

  @Test
  void testSuccess_withReqistrarRequestOnly() throws Exception {
    persistActiveDomain(
        "domain.tld",
        DateTime.parse("2014-09-05T05:05:05Z"),
        DateTime.parse("2015-09-05T05:05:05Z"));
    runCommandForced("domain.tld", "--period=1", "--registrar_request=true");

    eppVerifier
        .expectRegistrarId("TheRegistrar")
        .verifySent(
            "domain_renew_with_metadata_requestedByRegistrar_only.xml",
            ImmutableMap.of(
                "DOMAIN",
                "domain.tld",
                "EXPDATE",
                "2015-09-05",
                "YEARS",
                "1",
                "REQUESTED",
                "true"));
  }

  @Test
  void testFailure_domainDoesntExist() {
    assertThat(
            assertThrows(
                ResourceFlowUtils.ResourceDoesNotExistException.class,
                () -> runCommandForced("nonexistent.tld")))
        .hasMessageThat()
        .isEqualTo("The domain with given ID (nonexistent.tld) doesn't exist.");
  }

  @Test
  void testFailure_domainIsDeleted() {
    persistDeletedDomain("deleted.tld", DateTime.parse("2012-10-05T05:05:05Z"));
    assertThat(
            assertThrows(
                ResourceFlowUtils.ResourceDoesNotExistException.class,
                () -> runCommandForced("deleted.tld")))
        .hasMessageThat()
        .isEqualTo("The domain with given ID (deleted.tld) doesn't exist.");
  }

  @Test
  void testFailure_duplicateDomainSpecified() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("dupe.tld", "dupe.tld"));
    assertThat(e).hasMessageThat().isEqualTo("Duplicate domain arguments found: 'dupe.tld'");
  }

  @Test
  void testFailure_cantRenewForTenYears() {
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
  void testFailure_missingDomainNames() {
    assertThrows(ParameterException.class, () -> runCommand("--period 4"));
  }

  @Test
  void testFailure_registrarRequestIsRequiredWhenReasonIsPresent() {
    persistActiveDomain(
        "domain.tld",
        DateTime.parse("2014-09-05T05:05:05Z"),
        DateTime.parse("2015-09-05T05:05:05Z"));
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("domain.tld", "--period=1", "--reason=testing_only"));
    assertThat(e)
        .hasMessageThat()
        .isEqualTo("--registrar_request is required when --reason is specified");
  }
}
