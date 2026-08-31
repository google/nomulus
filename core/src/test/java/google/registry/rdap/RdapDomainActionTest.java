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

package google.registry.rdap;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDomainWithDependentResources;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.FullFieldsTestEntityHelper.makeAndPersistHost;
import static google.registry.testing.FullFieldsTestEntityHelper.makeDomain;
import static google.registry.testing.FullFieldsTestEntityHelper.makeHistoryEntry;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrar;
import static google.registry.testing.FullFieldsTestEntityHelper.makeRegistrarPocs;
import static google.registry.testing.GsonSubject.assertAboutJson;
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusDays;
import static google.registry.util.DateTimeUtils.minusMonths;
import static google.registry.util.DateTimeUtils.minusYears;
import static google.registry.util.DateTimeUtils.plusDays;
import static google.registry.util.DateTimeUtils.plusYears;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.gson.JsonObject;
import google.registry.cache.CacheMetrics;
import google.registry.cache.MultilayerDomainCache;
import google.registry.cache.SimplifiedJedisClient;
import google.registry.model.domain.Domain;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.Period;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.ExpiryAccessPeriodMode;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManager.ThrowingRunnable;
import google.registry.persistence.transaction.TransactionManagerFactory;
import google.registry.rdap.RdapMetrics.EndpointType;
import google.registry.rdap.RdapMetrics.SearchType;
import google.registry.rdap.RdapMetrics.WildcardType;
import google.registry.rdap.RdapSearchResults.IncompletenessWarningType;
import google.registry.request.Action;
import google.registry.testing.FakeResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapDomainAction}. */
class RdapDomainActionTest extends RdapActionBaseTestCase<RdapDomainAction> {

  RdapDomainActionTest() {
    super(RdapDomainAction.class);
  }

  private Host host1;
  private Domain domainDeleted;
  private Domain domainIdn;

  @BeforeEach
  void beforeEach() {
    // lol
    createTld("lol");
    Registrar registrarLol =
        persistResource(
            makeRegistrar("evilregistrar", "Yes Virginia <script>", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrarLol));
    host1 = makeAndPersistHost("ns1.cat.lol", "1.2.3.4", null, minusYears(clock.now(), 1));
    Host host2 =
        makeAndPersistHost(
            "ns2.cat.lol", "bad:f00d:cafe:0:0:0:15:beef", minusYears(clock.now(), 2));
    persistResource(
        makeDomain("cat.lol", host1, host2, registrarLol)
            .asBuilder()
            .setCreationTimeForTest(minusYears(clock.now(), 3))
            .setCreationRegistrarId("TheRegistrar")
            .build());

    // deleted domain in lol
    Host hostDodo2 =
        makeAndPersistHost(
            "ns2.dodo.lol", "bad:f00d:cafe:0:0:0:15:beef", minusYears(clock.now(), 2));
    domainDeleted =
        persistResource(
            makeDomain("dodo.lol", host1, hostDodo2, registrarLol)
                .asBuilder()
                .setCreationTimeForTest(minusYears(clock.now(), 3))
                .setCreationRegistrarId("TheRegistrar")
                .setDeletionTime(minusDays(clock.now(), 1))
                .build());
    // cat.みんな
    createTld("xn--q9jyb4c");
    Registrar registrarIdn =
        persistResource(makeRegistrar("idnregistrar", "IDN Registrar", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrarIdn));
    domainIdn =
        persistResource(
            makeDomain("cat.みんな", host1, host2, registrarIdn)
                .asBuilder()
                .setCreationTimeForTest(minusYears(clock.now(), 3))
                .setCreationRegistrarId("TheRegistrar")
                .build());

    // 1.tld
    createTld("1.tld");
    Registrar registrar1Tld =
        persistResource(
            makeRegistrar("1tldregistrar", "Multilevel Registrar", Registrar.State.ACTIVE));
    persistResources(makeRegistrarPocs(registrar1Tld));
    persistResource(
        makeDomain("cat.1.tld", host1, host2, registrar1Tld)
            .asBuilder()
            .setCreationTimeForTest(minusYears(clock.now(), 3))
            .setCreationRegistrarId("TheRegistrar")
            .build());

    // history entries
    persistResource(
        makeHistoryEntry(
            domainDeleted,
            HistoryEntry.Type.DOMAIN_DELETE,
            Period.create(1, Period.Unit.YEARS),
            "deleted",
            minusMonths(clock.now(), 6)));
  }

  private void assertProperResponseForCatLol(String queryString, String expectedOutputFile) {
    assertAboutJson()
        .that(generateActualJson(queryString))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("cat.lol", "6-LOL")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .addRegistrar("Yes Virginia <script>")
                    .load(expectedOutputFile)));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testInvalidDomain_returns400() {
    assertAboutJson()
        .that(generateActualJson("invalid/domain/name"))
        .isEqualTo(
            generateExpectedJsonError(
                "invalid/domain/name is not a valid domain name: Domain names can only contain a-z,"
                    + " 0-9, '.' and '-'",
                400));
    assertThat(response.getStatus()).isEqualTo(400);
  }

  @Test
  void testUnknownDomain_returns404() {
    assertAboutJson()
        .that(generateActualJson("missingdomain.com"))
        .isEqualTo(generateExpectedJsonError("missingdomain.com not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testValidDomain_works() {
    login("evilregistrar");
    assertProperResponseForCatLol("cat.lol", "rdap_domain.json");
  }

  @Test
  void testValidDomain_asAdministrator_works() {
    loginAsAdmin();
    assertProperResponseForCatLol("cat.lol", "rdap_domain.json");
  }

  @Test
  void testUpperCase_ignored() {
    assertProperResponseForCatLol("CaT.lOl", "rdap_domain.json");
  }

  @Test
  void testTrailingDot_ignored() {
    assertProperResponseForCatLol("cat.lol.", "rdap_domain.json");
  }

  @Test
  void testQueryParameter_ignored() {
    assertProperResponseForCatLol("cat.lol?key=value", "rdap_domain.json");
  }

  @Test
  void testIdnDomain_works() {
    login("idnregistrar");
    assertAboutJson()
        .that(generateActualJson("cat.みんな"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("cat.みんな", "B-Q9JYB4C")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .addRegistrar("IDN Registrar")
                    .load("rdap_domain_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testIdnDomainWithPercentEncoding_works() {
    login("idnregistrar");
    assertAboutJson()
        .that(generateActualJson("cat.%E3%81%BF%E3%82%93%E3%81%AA"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("cat.みんな", "B-Q9JYB4C")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .addRegistrar("IDN Registrar")
                    .load("rdap_domain_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testPunycodeDomain_works() {
    login("idnregistrar");
    assertAboutJson()
        .that(generateActualJson("cat.xn--q9jyb4c"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("cat.みんな", "B-Q9JYB4C")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .addRegistrar("IDN Registrar")
                    .load("rdap_domain_unicode.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testMultilevelDomain_works() {
    login("1tldregistrar");
    assertAboutJson()
        .that(generateActualJson("cat.1.tld"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("cat.1.tld", "D-1TLD")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.cat.lol", "4-ROID")
                    .addRegistrar("Multilevel Registrar")
                    .load("rdap_domain.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  // todo (b/27378695): reenable or delete this test
  @Disabled
  @Test
  void testDomainInTestTld_notFound() {
    persistResource(Tld.get("lol").asBuilder().setTldType(Tld.TldType.TEST).build());
    generateActualJson("cat.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedDomain_notFound() {
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedDomain_notFound_includeDeletedSetFalse() {
    action.includeDeletedParam = Optional.of(true);
    generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedDomain_notFound_notLoggedIn() {
    action.includeDeletedParam = Optional.of(true);
    generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedDomain_notFound_loggedInAsDifferentRegistrar() {
    login("1tldregistrar");
    action.includeDeletedParam = Optional.of(true);
    generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDeletedDomain_works_loggedInAsCorrectRegistrar() {
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("dodo.lol", "9-LOL")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.dodo.lol", "7-ROID")
                    .addRegistrar("Yes Virginia <script>")
                    .load("rdap_domain_deleted.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDeletedDomain_works_loggedInAsAdmin() {
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("dodo.lol", "9-LOL")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.dodo.lol", "7-ROID")
                    .addRegistrar("Yes Virginia <script>")
                    .load("rdap_domain_deleted.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testAddGracePeriod() {
    persistActiveDomainWithHost("addgraceperiod", "lol", clock.now(), plusYears(clock.now(), 1));
    assertAboutJson()
        .that(generateActualJson("addgraceperiod.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder().load("rdap_domain_add_grace_period.json")));
  }

  @Test
  void testAutoRenewGracePeriod() {
    persistActiveDomainWithHost(
        "autorenew", "lol", minusDays(minusYears(clock.now(), 1), 1), minusDays(clock.now(), 1));
    assertAboutJson()
        .that(generateActualJson("autorenew.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder().load("rdap_domain_auto_renew_grace_period.json")));
  }

  @Test
  void testRedemptionGracePeriod() {
    Domain domain = persistActiveDomain("redemption.lol", minusYears(clock.now(), 1));
    persistResource(
        domain
            .asBuilder()
            .addNameserver(host1.createVKey())
            .setDeletionTime(plusDays(clock.now(), 1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .setGracePeriods(
                ImmutableSet.of(
                    GracePeriod.createWithoutBillingEvent(
                        GracePeriodStatus.REDEMPTION,
                        domain.getRepoId(),
                        plusDays(clock.now(), 4),
                        "TheRegistrar")))
            .build());
    assertAboutJson()
        .that(generateActualJson("redemption.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder().load("rdap_domain_pending_delete_redemption_grace_period.json")));
  }

  @Test
  void testRenewGracePeriod() {
    Domain domain =
        persistActiveDomainWithHost(
            "renew", "lol", minusYears(clock.now(), 1), plusYears(clock.now(), 1));
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    plusDays(clock.now(), 1),
                    "TheRegistrar",
                    null))
            .build());
    assertAboutJson()
        .that(generateActualJson("renew.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder().load("rdap_domain_explicit_renew_grace_period.json")));
  }

  @Test
  void testTransferGracePeriod() {
    Domain domain =
        persistActiveDomainWithHost(
            "transfer", "lol", minusMonths(clock.now(), 6), plusYears(clock.now(), 1));
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    domain.getRepoId(),
                    plusDays(clock.now(), 1),
                    "TheRegistrar",
                    null))
            .build());
    assertAboutJson()
        .that(generateActualJson("transfer.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder().load("rdap_domain_transfer_grace_period.json")));
  }

  @Test
  void testMetrics() {
    generateActualJson("cat.lol");
    verify(rdapMetrics)
        .updateMetrics(
            RdapMetrics.RdapMetricInformation.builder()
                .setEndpointType(EndpointType.DOMAIN)
                .setSearchType(SearchType.NONE)
                .setWildcardType(WildcardType.INVALID)
                .setPrefixLength(0)
                .setIncludeDeleted(false)
                .setRegistrarSpecified(false)
                .setRole(RdapAuthorization.Role.PUBLIC)
                .setRequestMethod(Action.Method.GET)
                .setStatusCode(200)
                .setIncompletenessWarningType(IncompletenessWarningType.COMPLETE)
                .setProcessingTime(0L)
                .build());
  }

  @Test
  void testBlockedByBsa() {
    persistResource(
        Tld.get("lol").asBuilder().setBsaEnrollStartTime(Optional.of(START_INSTANT)).build());
    persistBsaLabel("example");
    ImmutableMap<?, ?> expectedBsaNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of("This name has been blocked by a GlobalBlock service"),
            "title",
            "Blocked Domain",
            "links",
            ImmutableList.of(
                ImmutableMap.of(
                    "href",
                    "https://brandsafetyalliance.co",
                    "rel",
                    "alternate",
                    "type",
                    "text/html",
                    "value",
                    "https://example.tld/rdap/domain/example.lol")));
    JsonObject actuaResponse = generateActualJson("example.lol");
    JsonObject expectedErrorResponse = generateExpectedJsonError("example.lol blocked by BSA", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedBsaNotice));
    assertAboutJson().that(actuaResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_deletedOutsideXapWindow_notFound() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(domainDeleted.asBuilder().setDeletionTime(minusDays(clock.now(), 15)).build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_deletedDuringAgp_notFound() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(
        domainDeleted
            .asBuilder()
            .setCreationTimeForTest(minusDays(clock.now(), 2))
            .setDeletionTime(minusDays(clock.now(), 1))
            .build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_loggedInAsAdmin_includeDeleted() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("dodo.lol", "9-LOL")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.dodo.lol", "7-ROID")
                    .addRegistrar("Yes Virginia <script>")
                    .load("rdap_domain_deleted.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDomainInExpiryAccessPeriod_atDeletionTimeExact_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(domainDeleted.asBuilder().setDeletionTime(clock.now()).build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_nearExpiryBoundary_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Duration xapLength = Duration.ofDays(10);
    persistResource(
        domainDeleted
            .asBuilder()
            .setDeletionTime(clock.now().minus(xapLength).plusSeconds(1))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_exactExpiry_returnsStandard404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Duration xapLength = Duration.ofDays(10);
    persistResource(
        domainDeleted.asBuilder().setDeletionTime(clock.now().minus(xapLength)).build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_postExpiry_returnsStandard404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Duration xapLength = Duration.ofDays(10);
    persistResource(
        domainDeleted
            .asBuilder()
            .setDeletionTime(clock.now().minus(xapLength).minusSeconds(1))
            .build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_oneMilliBeforeDeletion_activeReturns200() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(domainDeleted.asBuilder().setDeletionTime(clock.now().plusMillis(1)).build());
    JsonObject actualResponse = generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(actualResponse.get("ldhName").getAsString()).isEqualTo("dodo.lol");
  }

  @Test
  void testDomainInExpiryAccessPeriod_oneMilliBeforeExpiry_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Duration xapLength = Duration.ofDays(10);
    persistResource(
        domainDeleted
            .asBuilder()
            .setDeletionTime(clock.now().minus(xapLength).plusMillis(1))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_oneMilliAfterExpiry_returnsStandard404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Duration xapLength = Duration.ofDays(10);
    persistResource(
        domainDeleted
            .asBuilder()
            .setDeletionTime(clock.now().minus(xapLength).minusMillis(1))
            .build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_idnDomain_returnsXap404() {
    persistResource(
        Tld.get("xn--q9jyb4c")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(domainIdn.asBuilder().setDeletionTime(minusDays(clock.now(), 1)).build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");

    // 1. Query via Punycode (A-label)
    JsonObject actualPunycodeResponse = generateActualJson("cat.xn--q9jyb4c");
    JsonObject expectedPunycodeResponse =
        generateExpectedJsonError("cat.xn--q9jyb4c in Expiry Access Period", 404);
    expectedPunycodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualPunycodeResponse).isEqualTo(expectedPunycodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);

    // 2. Query via Unicode (U-label)
    response = new FakeResponse();
    action.response = response;
    JsonObject actualUnicodeResponse = generateActualJson("cat.みんな");
    JsonObject expectedUnicodeResponse =
        generateExpectedJsonError("cat.xn--q9jyb4c in Expiry Access Period", 404);
    expectedUnicodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualUnicodeResponse).isEqualTo(expectedUnicodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_atExactAgpBoundary_notFound() {
    Tld tld =
        persistResource(
            Tld.get("lol")
                .asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant creationTime = clock.now().minus(Duration.ofDays(5));
    Instant deletionTime = creationTime.plus(tld.getAddGracePeriodLength());
    persistResource(
        domainDeleted
            .asBuilder()
            .setCreationTimeForTest(creationTime)
            .setDeletionTime(deletionTime)
            .build());
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(generateExpectedJsonError("dodo.lol not found", 404));
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_sponsoringRegistrar_includeDeleted() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(true);
    assertAboutJson()
        .that(generateActualJson("dodo.lol"))
        .isEqualTo(
            addDomainBoilerplateNotices(
                jsonFileBuilder()
                    .addDomain("dodo.lol", "9-LOL")
                    .addNameserver("ns1.cat.lol", "2-ROID")
                    .addNameserver("ns2.dodo.lol", "7-ROID")
                    .addRegistrar("Yes Virginia <script>")
                    .load("rdap_domain_deleted.json")));
    assertThat(response.getStatus()).isEqualTo(200);
  }

  @Test
  void testDomainInExpiryAccessPeriod_otherRegistrar_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    login("idnregistrar");
    action.includeDeletedParam = Optional.of(true);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_sponsoringRegistrar_noParam() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    login("evilregistrar");
    action.includeDeletedParam = Optional.empty();
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_deletedJustAfterAgp_returnsXap404() {
    Tld tld =
        persistResource(
            Tld.get("lol")
                .asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant creationTime = clock.now().minus(Duration.ofDays(6));
    Instant deletionTime = creationTime.plus(tld.getAddGracePeriodLength()).plusMillis(1);
    persistResource(
        domainDeleted
            .asBuilder()
            .setCreationTimeForTest(creationTime)
            .setDeletionTime(deletionTime)
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_public_includeDeletedTrue_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    action.includeDeletedParam = Optional.of(true);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_public_includeDeletedFalse_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    action.includeDeletedParam = Optional.of(false);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_otherRegistrar_includeDeletedFalse_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    login("idnregistrar");
    action.includeDeletedParam = Optional.of(false);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_sponsoringRegistrar_includeDeletedFalse_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    login("evilregistrar");
    action.includeDeletedParam = Optional.of(false);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_idnUnicodeSld_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Host hostIdn2 =
        makeAndPersistHost(
            "ns2.xn--q9jyb4c.lol", "bad:f00d:cafe:0:0:0:15:beef", minusYears(clock.now(), 2));
    persistResource(
        makeDomain(
                "xn--q9jyb4c.lol",
                host1,
                hostIdn2,
                Registrar.loadByRegistrarId("evilregistrar").get())
            .asBuilder()
            .setCreationTimeForTest(minusYears(clock.now(), 3))
            .setCreationRegistrarId("TheRegistrar")
            .setDeletionTime(minusDays(clock.now(), 1))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");

    // 1. Query via Punycode (A-label)
    JsonObject actualPunycodeResponse = generateActualJson("xn--q9jyb4c.lol");
    JsonObject expectedPunycodeResponse =
        generateExpectedJsonError("xn--q9jyb4c.lol in Expiry Access Period", 404);
    expectedPunycodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualPunycodeResponse).isEqualTo(expectedPunycodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);

    // 2. Query via Unicode (U-label)
    response = new FakeResponse();
    action.response = response;
    JsonObject actualUnicodeResponse = generateActualJson("みんな.lol");
    JsonObject expectedUnicodeResponse =
        generateExpectedJsonError("xn--q9jyb4c.lol in Expiry Access Period", 404);
    expectedUnicodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualUnicodeResponse).isEqualTo(expectedUnicodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_loggedInAsAdmin_includeDeletedFalse_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    loginAsAdmin();
    action.includeDeletedParam = Optional.of(false);
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_loggedInAsAdmin_noParam_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    loginAsAdmin();
    action.includeDeletedParam = Optional.empty();
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");
    JsonObject actualResponse = generateActualJson("dodo.lol");
    JsonObject expectedErrorResponse =
        generateExpectedJsonError("dodo.lol in Expiry Access Period", 404);
    expectedErrorResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedErrorResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_idnUnicodeSldAndTld_returnsXap404() {
    persistResource(
        Tld.get("xn--q9jyb4c")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Host hostIdnFull =
        makeAndPersistHost(
            "ns1.xn--q9jyb4c.xn--q9jyb4c", "1.2.3.4", null, minusYears(clock.now(), 2));
    persistResource(
        makeDomain(
                "xn--q9jyb4c.xn--q9jyb4c",
                hostIdnFull,
                host1,
                Registrar.loadByRegistrarId("evilregistrar").get())
            .asBuilder()
            .setCreationTimeForTest(minusYears(clock.now(), 3))
            .setCreationRegistrarId("TheRegistrar")
            .setDeletionTime(minusDays(clock.now(), 1))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");

    // 1. Query via Punycode (A-label)
    String punyName = "xn--q9jyb4c.xn--q9jyb4c";
    JsonObject actualPunycodeResponse = generateActualJson(punyName);
    JsonObject expectedPunycodeResponse =
        generateExpectedJsonError(punyName + " in Expiry Access Period", 404);
    expectedPunycodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualPunycodeResponse).isEqualTo(expectedPunycodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);

    // 2. Query via Unicode (U-label)
    response = new FakeResponse();
    action.response = response;
    JsonObject actualUnicodeResponse = generateActualJson("みんな.みんな");
    JsonObject expectedUnicodeResponse =
        generateExpectedJsonError(punyName + " in Expiry Access Period", 404);
    expectedUnicodeResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualUnicodeResponse).isEqualTo(expectedUnicodeResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_mixedCasePunycode_returnsXap404() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    Host hostIdn =
        makeAndPersistHost(
            "ns1.xn--q9jyb4c.lol", "bad:f00d:cafe:0:0:0:15:beef", minusYears(clock.now(), 2));
    persistResource(
        makeDomain(
                "xn--q9jyb4c.lol",
                host1,
                hostIdn,
                Registrar.loadByRegistrarId("evilregistrar").get())
            .asBuilder()
            .setCreationTimeForTest(minusYears(clock.now(), 3))
            .setCreationRegistrarId("TheRegistrar")
            .setDeletionTime(minusDays(clock.now(), 1))
            .build());
    ImmutableMap<?, ?> expectedXapNotice =
        ImmutableMap.of(
            "description",
            ImmutableList.of(
                "This domain is currently available for registration in the Expiry Access Period"),
            "title",
            "Expiry Access Period");

    JsonObject actualResponse = generateActualJson("XN--Q9JYB4C.LOL");
    JsonObject expectedResponse =
        generateExpectedJsonError("xn--q9jyb4c.lol in Expiry Access Period", 404);
    expectedResponse
        .getAsJsonArray("notices")
        .add(RdapTestHelper.GSON.toJsonTree(expectedXapNotice));
    assertAboutJson().that(actualResponse).isEqualTo(expectedResponse);
    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void testDomainInExpiryAccessPeriod_oneMilliBeforeDeletion_pendingDelete() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    persistResource(
        domainDeleted
            .asBuilder()
            .setDeletionTime(clock.now().plusMillis(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    JsonObject actualResponse = generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(actualResponse.get("ldhName").getAsString()).isEqualTo("dodo.lol");
    assertThat(actualResponse.getAsJsonArray("status").toString()).contains("pending delete");
  }

  @Test
  void testDomain_rapidRecreationAndDeletionCycle() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());

    // Step 1: Initial domain in XAP -> returns 404 with XAP notice
    JsonObject xapResponse = generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(xapResponse.toString()).contains("Expiry Access Period");

    // Step 2: Re-register domain (active) -> returns 200 OK
    clock.advanceBy(Duration.ofDays(1));
    Domain activeDomain =
        persistResource(
            domainDeleted
                .asBuilder()
                .setCreationTimeForTest(clock.now())
                .setDeletionTime(END_INSTANT)
                .build());
    response = new FakeResponse();
    action.response = response;
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter(clock);
    JsonObject activeResponse = generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(activeResponse.get("ldhName").getAsString()).isEqualTo("dodo.lol");

    // Step 3: Deleted again outside AGP -> returns 404 with XAP notice
    clock.advanceBy(Duration.ofDays(10));
    Instant secondDeletion = clock.now();
    persistResource(activeDomain.asBuilder().setDeletionTime(secondDeletion).build());
    clock.advanceBy(Duration.ofDays(1));
    response = new FakeResponse();
    action.response = response;
    action.rdapJsonFormatter = RdapTestHelper.getTestRdapJsonFormatter(clock);
    JsonObject secondXapResponse = generateActualJson("dodo.lol");
    assertThat(response.getStatus()).isEqualTo(404);
    assertThat(secondXapResponse.toString()).contains("Expiry Access Period");
  }

  @Test
  void testWorkloadIsolation_zeroPrimaryDbTransactionsDuringRdapExecution() {
    persistResource(
        Tld.get("lol")
            .asBuilder()
            .setExpiryAccessPeriodTransitions(
                ImmutableSortedMap.of(START_INSTANT, ExpiryAccessPeriodMode.ENABLED))
            .build());
    JpaTransactionManager originalTm = TransactionManagerFactory.tm();
    JpaTransactionManager originalReplicaTm = TransactionManagerFactory.replicaTm();
    JpaTransactionManager primaryTmSpy = spy(originalTm);
    JpaTransactionManager replicaTmSpy = spy(originalReplicaTm);
    TransactionManagerFactory.setJpaTm(() -> primaryTmSpy);
    TransactionManagerFactory.setReplicaJpaTm(() -> replicaTmSpy);
    try {
      // 1. Active domain query
      generateActualJson("cat.lol");
      assertThat(response.getStatus()).isEqualTo(200);

      // 2. XAP domain query
      response = new FakeResponse();
      action.response = response;
      generateActualJson("dodo.lol");
      assertThat(response.getStatus()).isEqualTo(404);

      // 3. Nonexistent domain query
      response = new FakeResponse();
      action.response = response;
      generateActualJson("nonexistent.lol");
      assertThat(response.getStatus()).isEqualTo(404);

      // 4. MultilayerDomainCache with Jedis cache miss delegating to replica Cloud SQL
      SimplifiedJedisClient jedisClient = mock(SimplifiedJedisClient.class);
      when(jedisClient.get(any(), any())).thenReturn(Optional.empty());
      action.domainCache = new MultilayerDomainCache(jedisClient, clock, mock(CacheMetrics.class));
      response = new FakeResponse();
      action.response = response;
      generateActualJson("dodo.lol");
      assertThat(response.getStatus()).isEqualTo(404);

      // Verify zero write transactions were initiated on primary database
      verify(primaryTmSpy, never()).transact(any(ThrowingRunnable.class));
      verify(primaryTmSpy, never()).transact(any(Callable.class));

      // Verify database reads for Domain entities route strictly to replicaTm()
      verify(replicaTmSpy, atLeastOnce()).reTransact(any(Callable.class));
    } finally {
      TransactionManagerFactory.setJpaTm(() -> originalTm);
      TransactionManagerFactory.setReplicaJpaTm(() -> originalReplicaTm);
    }
  }

  private Domain persistActiveDomainWithHost(
      String label, String tld, Instant creationTime, Instant expirationTime) {
    return persistResource(
        persistDomainWithDependentResources(label, tld, clock.now(), creationTime, expirationTime)
            .asBuilder()
            .addNameserver(host1.createVKey())
            .build());
  }
}
