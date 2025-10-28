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

package google.registry.batch;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.model.domain.rgp.GracePeriodStatus.ADD;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntitiesIfPresent;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistDomainAsDeleted;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import google.registry.model.ForeignKeyUtils;
import google.registry.model.ImmutableObject;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldType;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.SystemPropertyExtension;
import google.registry.util.RegistryEnvironment;
import java.util.Optional;
import java.util.Set;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DeleteProberDataAction}. */
class DeleteProberDataActionTest {

  private static final DateTime DELETION_TIME = DateTime.parse("2010-01-01T00:00:00.000Z");

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  @RegisterExtension
  final SystemPropertyExtension systemPropertyExtension = new SystemPropertyExtension();

  private DeleteProberDataAction action;

  @BeforeEach
  void beforeEach() {
    // Entities in these two should not be touched.
    createTld("tld", "TLD");
    // Since "example" doesn't end with .test, its entities won't be deleted even though it is of
    // TEST type.
    createTld("example", "EXAMPLE");
    persistResource(Tld.get("example").asBuilder().setTldType(TldType.TEST).build());

    // Since "not-test.test" isn't of TEST type, its entities won't be deleted even though it ends
    // with .test.
    createTld("not-test.test", "EXTEST");

    // Entities in these two should be deleted.
    createTld("ib-any.test", "IBANYT");
    persistResource(Tld.get("ib-any.test").asBuilder().setTldType(TldType.TEST).build());
    createTld("oa-canary.test", "OACANT");
    persistResource(Tld.get("oa-canary.test").asBuilder().setTldType(TldType.TEST).build());

    resetAction();
  }

  private void resetAction() {
    action = new DeleteProberDataAction(false, ImmutableSet.of(), Optional.empty(), "TheRegistrar");
  }

  @AfterEach
  void afterEach() {
    RegistryEnvironment.UNITTEST.setup(systemPropertyExtension);
  }

  @Test
  void test_deletesAllAndOnlyProberData() throws Exception {
    Set<ImmutableObject> tldEntities = persistLotsOfDomains("tld");
    Set<ImmutableObject> exampleEntities = persistLotsOfDomains("example");
    Set<ImmutableObject> notTestEntities = persistLotsOfDomains("not-test.test");
    Set<ImmutableObject> ibEntities = persistLotsOfDomains("ib-any.test");
    Set<ImmutableObject> oaEntities = persistLotsOfDomains("oa-canary.test");
    action.run();
    assertAllExist(tldEntities);
    assertAllExist(exampleEntities);
    assertAllExist(notTestEntities);
    assertAllAbsent(ibEntities);
    assertAllAbsent(oaEntities);
  }

  @Test
  void test_deletesAllInBatches() throws Exception {
    // Persist 40 domains
    Set<ImmutableObject> ibEntities = persistLotsOfDomains("ib-any.test");
    Set<ImmutableObject> oaEntities = persistLotsOfDomains("oa-canary.test");
    // Create action with batch size of 3
    DeleteProberDataAction batchedAction =
        new DeleteProberDataAction(false, ImmutableSet.of(), Optional.of(3), "TheRegistrar");
    batchedAction.run();
    assertAllAbsent(ibEntities);
    assertAllAbsent(oaEntities);
  }

  @Test
  void testSuccess_deletesAllAndOnlyGivenTlds() throws Exception {
    Set<ImmutableObject> tldEntities = persistLotsOfDomains("tld");
    Set<ImmutableObject> exampleEntities = persistLotsOfDomains("example");
    Set<ImmutableObject> notTestEntities = persistLotsOfDomains("not-test.test");
    Set<ImmutableObject> ibEntities = persistLotsOfDomains("ib-any.test");
    Set<ImmutableObject> oaEntities = persistLotsOfDomains("oa-canary.test");
    action.tlds = ImmutableSet.of("example", "ib-any.test");
    action.run();
    assertAllExist(tldEntities);
    assertAllExist(notTestEntities);
    assertAllExist(oaEntities);
    assertAllAbsent(exampleEntities);
    assertAllAbsent(ibEntities);
  }

  @Test
  void testFail_givenNonTestTld() {
    action.tlds = ImmutableSet.of("not-test.test");
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .contains("If tlds are given, they must all exist and be TEST tlds");
  }

  @Test
  void testFail_givenNonExistentTld() {
    action.tlds = ImmutableSet.of("non-existent.test");
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .contains("If tlds are given, they must all exist and be TEST tlds");
  }

  @Test
  void testFail_givenNonDotTestTldOnProd() {
    action.tlds = ImmutableSet.of("example");
    RegistryEnvironment.PRODUCTION.setup(systemPropertyExtension);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, action::run);
    assertThat(thrown)
        .hasMessageThat()
        .contains("On production, can only work on TLDs that end with .test");
  }

  @Test
  void testSuccess_doesntDeleteNicDomainForProbers() throws Exception {
    Domain nic = persistActiveDomain("nic.ib-any.test");
    Set<ImmutableObject> ibEntities = persistLotsOfDomains("ib-any.test");
    action.run();
    assertAllAbsent(ibEntities);
    assertAllExist(ImmutableSet.of(nic));
  }

  @Test
  void testDryRun_doesntDeleteData() throws Exception {
    Set<ImmutableObject> tldEntities = persistLotsOfDomains("tld");
    Set<ImmutableObject> oaEntities = persistLotsOfDomains("oa-canary.test");
    action.isDryRun = true;
    action.run();
    assertAllExist(tldEntities);
    assertAllExist(oaEntities);
  }

  @Test
  void testSuccess_activeDomain_isSoftDeleted() throws Exception {
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("blah.ib-any.test")
                .asBuilder()
                .setCreationTimeForTest(DateTime.now(UTC).minusYears(1))
                .build());
    action.run();
    DateTime timeAfterDeletion = DateTime.now(UTC);
    assertThat(ForeignKeyUtils.loadResource(Domain.class, "blah.ib-any.test", timeAfterDeletion))
        .isEmpty();
    assertThat(loadByEntity(domain).getDeletionTime()).isLessThan(timeAfterDeletion);
    assertDomainDnsRequests("blah.ib-any.test");
  }

  @Test
  void testSuccess_activeDomain_doubleMapSoftDeletes() throws Exception {
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("blah.ib-any.test")
                .asBuilder()
                .setCreationTimeForTest(DateTime.now(UTC).minusYears(1))
                .build());
    action.run();
    DateTime timeAfterDeletion = DateTime.now(UTC);
    resetAction();
    action.run();
    assertThat(ForeignKeyUtils.loadResource(Domain.class, "blah.ib-any.test", timeAfterDeletion))
        .isEmpty();
    assertThat(loadByEntity(domain).getDeletionTime()).isLessThan(timeAfterDeletion);
    assertDomainDnsRequests("blah.ib-any.test");
  }

  @Test
  void test_recentlyCreatedDomain_isntDeletedYet() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("blah.ib-any.test")
            .asBuilder()
            .setCreationTimeForTest(DateTime.now(UTC).minusSeconds(1))
            .build());
    action.run();
    Optional<Domain> domain =
        ForeignKeyUtils.loadResource(Domain.class, "blah.ib-any.test", DateTime.now(UTC));
    assertThat(domain).isPresent();
    assertThat(domain.get().getDeletionTime()).isEqualTo(END_OF_TIME);
  }

  @Test
  void testDryRun_doesntSoftDeleteData() throws Exception {
    Domain domain =
        persistResource(
            DatabaseHelper.newDomain("blah.ib-any.test")
                .asBuilder()
                .setCreationTimeForTest(DateTime.now(UTC).minusYears(1))
                .build());
    action.isDryRun = true;
    action.run();
    assertThat(loadByEntity(domain).getDeletionTime()).isEqualTo(END_OF_TIME);
  }

  @Test
  void test_domainWithSubordinateHosts_isSkipped() throws Exception {
    persistActiveHost("ns1.blah.ib-any.test");
    Domain nakedDomain =
        persistDeletedDomain("todelete.ib-any.test", DateTime.now(UTC).minusYears(1));
    Domain domainWithSubord =
        persistDomainAsDeleted(
            DatabaseHelper.newDomain("blah.ib-any.test")
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of("ns1.blah.ib-any.test"))
                .build(),
            DateTime.now(UTC).minusYears(1));
    action.run();

    assertAllExist(ImmutableSet.of(domainWithSubord));
    assertAllAbsent(ImmutableSet.of(nakedDomain));
  }

  @Test
  void testFailure_registryAdminClientId_isRequiredForSoftDeletion() {
    persistResource(
        DatabaseHelper.newDomain("blah.ib-any.test")
            .asBuilder()
            .setCreationTimeForTest(DateTime.now(UTC).minusYears(1))
            .build());
    action.registryAdminRegistrarId = null;
    IllegalStateException thrown = assertThrows(IllegalStateException.class, action::run);
    assertThat(thrown).hasMessageThat().contains("Registry admin client ID must be configured");
  }

  /**
   * Persists and returns a domain and a descendant history entry, billing event, and poll message.
   */
  private static Set<ImmutableObject> persistDomainAndDescendants(String fqdn) {
    Domain domain = persistDeletedDomain(fqdn, DELETION_TIME);
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setRegistrarId("TheRegistrar")
                .setModificationTime(DELETION_TIME.minusYears(3))
                .build());
    BillingEvent billingEvent =
        persistResource(
            new BillingEvent.Builder()
                .setDomainHistory(historyEntry)
                .setBillingTime(DELETION_TIME.plusYears(1))
                .setCost(Money.parse("USD 10"))
                .setPeriodYears(1)
                .setReason(Reason.CREATE)
                .setRegistrarId("TheRegistrar")
                .setEventTime(DELETION_TIME)
                .setTargetId(fqdn)
                .build());
    PollMessage.OneTime pollMessage =
        persistResource(
            new PollMessage.OneTime.Builder()
                .setHistoryEntry(historyEntry)
                .setEventTime(DELETION_TIME)
                .setRegistrarId("TheRegistrar")
                .setMsg("Domain registered")
                .build());
    GracePeriod gracePeriod =
        persistResource(
            GracePeriod.create(
                ADD,
                domain.getRepoId(),
                DELETION_TIME.plusDays(5),
                "TheRegistrar",
                billingEvent.createVKey()));
    domain = persistResource(domain.asBuilder().addGracePeriod(gracePeriod).build());
    ImmutableSet.Builder<ImmutableObject> builder =
        new ImmutableSet.Builder<ImmutableObject>()
            .add(domain)
            .add(historyEntry)
            .add(billingEvent)
            .add(pollMessage)
            .add(gracePeriod);
    return builder.build();
  }

  private static Set<ImmutableObject> persistLotsOfDomains(String tld) {
    ImmutableSet.Builder<ImmutableObject> persistedObjects = new ImmutableSet.Builder<>();
    for (int i = 0; i < 20; i++) {
      persistedObjects.addAll(persistDomainAndDescendants(String.format("domain%d.%s", i, tld)));
    }
    return persistedObjects.build();
  }

  private static void assertAllExist(Iterable<ImmutableObject> entities) {
    assertWithMessage("Expected entities to exist in the DB but they were deleted")
        .that(loadByEntitiesIfPresent(entities))
        .containsExactlyElementsIn(entities);
  }

  private static void assertAllAbsent(Iterable<ImmutableObject> entities) {
    assertWithMessage("Expected entities to not exist in the DB, but they did")
        .that(loadByEntitiesIfPresent(entities))
        .isEmpty();
  }
}
