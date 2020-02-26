// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.testing.DatastoreHelper.assertNoBillingEvents;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.getHistoryEntriesOfType;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.UserInfo;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link google.registry.tools.DomainLockUtils}. */
@RunWith(JUnit4.class)
public final class DomainLockUtilsTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeClock clock = new FakeClock();
  private final DomainLockUtils domainLockUtils =
      new DomainLockUtils(new DeterministicStringGenerator(Alphabets.BASE_58));

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  @Rule
  public final JpaIntegrationWithCoverageRule jpaRule =
      new JpaTestRules.Builder().withClock(clock).buildIntegrationWithCoverageRule();

  private DomainBase domain;

  @Before
  public void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase(DOMAIN_NAME, host));
  }

  @Test
  public void testSuccess_createLock() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
  }

  @Test
  public void testSuccess_createUnlock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", false);
  }

  @Test
  public void testSuccess_createUnlock_adminUnlockingAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", true);
  }

  @Test
  public void testSuccess_createLock_previousLockExpired() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(Duration.standardDays(1));
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
  }

  @Test
  public void testSuccess_applyLockDomain() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    verifyProperlyLockedDomain(false);
  }

  @Test
  public void testSuccess_applyUnlockDomain() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", false);
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false);
    verifyProperlyUnlockedDomain(false);
  }

  @Test
  public void testSuccess_applyAdminLock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    verifyProperlyLockedDomain(true);
  }

  @Test
  public void testSuccess_applyAdminUnlock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", true);
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), true);
    verifyProperlyUnlockedDomain(true);
  }

  @Test
  public void testSuccess_administrativelyLock_nonAdmin() {
    domainLockUtils.administrativelyApplyLock(
        DOMAIN_NAME, "TheRegistrar", "Marla.Singer@crr.com", false);
    verifyProperlyLockedDomain(false);
  }

  @Test
  public void testSuccess_administrativelyLock_admin() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", null, true);
    verifyProperlyLockedDomain(true);
  }

  @Test
  public void testSuccess_administrativelyUnlock_nonAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domainLockUtils.administrativelyApplyUnlock(DOMAIN_NAME, "TheRegistrar", false);
    verifyProperlyUnlockedDomain(false);
  }

  @Test
  public void testSuccess_administrativelyUnlock_admin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    domainLockUtils.administrativelyApplyUnlock(DOMAIN_NAME, "TheRegistrar", true);
    verifyProperlyUnlockedDomain(true);
  }

  @Test
  public void testFailure_createUnlock_alreadyPendingUnlock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", false);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false)))
        .hasMessageThat()
        .isEqualTo("A pending unlock action already exists for example.tld");
  }

  @Test
  public void testFailure_createUnlock_nonAdminUnlockingAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false)))
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot unlock admin-locked domain example.tld");
  }

  @Test
  public void testFailure_createLock_unknownDomain() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryLockRequest(
                        "asdf.tld", "TheRegistrar", POC_ID, false)))
        .hasMessageThat()
        .isEqualTo("Unknown domain asdf.tld");
  }

  @Test
  public void testFailure_createLock_alreadyPendingLock() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryLockRequest(
                        DOMAIN_NAME, "TheRegistrar", POC_ID, false)))
        .hasMessageThat()
        .isEqualTo("A pending or completed lock action already exists for example.tld");
  }

  @Test
  public void testFailure_createLock_alreadyLocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryLockRequest(
                        DOMAIN_NAME, "TheRegistrar", POC_ID, false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already locked");
  }

  @Test
  public void testFailure_createUnlock_alreadyUnlocked() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already unlocked");
  }

  @Test
  public void testFailure_applyLock_alreadyApplied() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domain = reloadDomain();
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already locked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_expired() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(Duration.standardDays(1));
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true)))
        .hasMessageThat()
        .isEqualTo("The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_nonAdmin_applyAdminLock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false)))
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot complete admin lock");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyUnlock_alreadyUnlocked() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(DOMAIN_NAME, "TheRegistrar", false);
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already unlocked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_alreadyLocked() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    String verificationCode = lock.getVerificationCode();
    // reload to pick up modification times, etc
    lock = getRegistryLockByVerificationCode(verificationCode).get();
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(verificationCode, false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already locked");

    // Failure during Datastore portion shouldn't affect the SQL object
    RegistryLock afterAction = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    assertThat(afterAction).isEqualTo(lock);
    assertNoDomainChanges();
  }

  private void verifyProperlyLockedDomain(boolean isAdmin) {
    assertThat(reloadDomain().getStatusValues()).containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isEqualTo(!isAdmin);
    assertThat(historyEntry.getBySuperuser()).isEqualTo(isAdmin);
    assertThat(historyEntry.getReason())
        .isEqualTo("Lock of a domain through a RegistryLock operation");
    if (isAdmin) {
      assertNoBillingEvents();
    } else {
      assertBillingEvent(historyEntry);
    }
  }

  private void verifyProperlyUnlockedDomain(boolean isAdmin) {
    assertThat(reloadDomain().getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    ImmutableList<HistoryEntry> historyEntries =
        getHistoryEntriesOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntries.size()).isEqualTo(2);
    historyEntries.forEach(
        entry -> {
          assertThat(entry.getRequestedByRegistrar()).isEqualTo(!isAdmin);
          assertThat(entry.getBySuperuser()).isEqualTo(isAdmin);
          assertThat(entry.getReason())
              .contains("ock of a domain through a RegistryLock operation");
        });
    if (isAdmin) {
      assertNoBillingEvents();
    } else {
      assertBillingEvents(historyEntries);
    }
  }

  private DomainBase reloadDomain() {
    return ofy().load().entity(domain).now();
  }

  private void assertNoDomainChanges() {
    assertThat(reloadDomain()).isEqualTo(domain);
  }

  private void assertBillingEvent(HistoryEntry historyEntry) {
    assertBillingEvents(ImmutableList.of(historyEntry));
  }

  private void assertBillingEvents(ImmutableList<HistoryEntry> historyEntries) {
    Set<BillingEvent> expectedEvents =
        historyEntries.stream()
            .map(
                entry ->
                    new BillingEvent.OneTime.Builder()
                        .setReason(Reason.SERVER_STATUS)
                        .setTargetId(domain.getForeignKey())
                        .setClientId(domain.getCurrentSponsorClientId())
                        .setCost(Registry.get(domain.getTld()).getServerStatusChangeCost())
                        .setEventTime(clock.nowUtc())
                        .setBillingTime(clock.nowUtc())
                        .setParent(entry)
                        .build())
            .collect(Collectors.toSet());
    DatastoreHelper.assertBillingEvents(expectedEvents);
  }
}
