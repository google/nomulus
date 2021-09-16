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
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.getHistoryEntriesOfType;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getRegistryLockByRevisionId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.TaskQueueHelper.assertTasksEnqueued;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.joda.time.Duration.standardSeconds;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import google.registry.batch.AsyncTaskEnqueuerTest;
import google.registry.batch.RelockDomainAction;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.RegistryLock;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Registry;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.FakeClock;
import google.registry.testing.SqlHelper;
import google.registry.testing.TaskQueueHelper.TaskMatcher;
import google.registry.testing.TestOfyAndSql;
import google.registry.testing.UserInfo;
import google.registry.util.AppEngineServiceUtils;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link google.registry.tools.DomainLockUtils}. */
@DualDatabaseTest
public final class DomainLockUtilsTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeClock clock = new FakeClock(DateTime.now(DateTimeZone.UTC));
  private DomainLockUtils domainLockUtils;

  @RegisterExtension
  public final AppEngineExtension appEngineRule =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withClock(clock)
          .withTaskQueue()
          .withUserService(UserInfo.create(POC_ID, "12345"))
          .build();

  private DomainBase domain;

  @BeforeEach
  void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase(DOMAIN_NAME, host));

    AppEngineServiceUtils appEngineServiceUtils = mock(AppEngineServiceUtils.class);
    when(appEngineServiceUtils.getServiceHostname("backend")).thenReturn("backend.hostname.fake");
    domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(Alphabets.BASE_58),
            "adminreg",
            AsyncTaskEnqueuerTest.createForTesting(
                appEngineServiceUtils, clock, standardSeconds(90)));
  }

  @TestOfyAndSql
  void testSuccess_createLock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    assertNoDomainChanges();
    assertThat(lock.getLockCompletionTime().isPresent()).isFalse();
  }

  @TestOfyAndSql
  void testSuccess_createUnlock() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    assertThat(lock.getUnlockCompletionTime().isPresent()).isFalse();
  }

  @TestOfyAndSql
  void testSuccess_createUnlock_adminUnlockingAdmin() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", null, true);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    assertThat(lock.getUnlockCompletionTime().isPresent()).isFalse();
  }

  @TestOfyAndSql
  void testSuccess_createLock_previousLockExpired() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(standardDays(1));
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    verifyProperlyLockedDomain(false);
  }

  @TestOfyAndSql
  void testSuccess_createUnlock_previousUnlockRequestExpired() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.saveNewRegistryUnlockRequest(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    clock.advanceBy(standardDays(1));
    RegistryLock unlockRequest =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyAndApplyUnlock(unlockRequest.getVerificationCode(), false);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @TestOfyAndSql
  void testSuccess_applyLockDomain() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    verifyProperlyLockedDomain(false);
  }

  @TestOfyAndSql
  void testSuccess_applyUnlockDomain() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false);
    verifyProperlyUnlockedDomain(false);
  }

  @TestOfyAndSql
  void testSuccess_applyAdminLock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    verifyProperlyLockedDomain(true);
  }

  @TestOfyAndSql
  void testSuccess_applyAdminUnlock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), true);
    verifyProperlyUnlockedDomain(true);
  }

  @TestOfyAndSql
  void testSuccess_administrativelyLock_nonAdmin() {
    domainLockUtils.administrativelyApplyLock(
        DOMAIN_NAME, "TheRegistrar", "Marla.Singer@crr.com", false);
    verifyProperlyLockedDomain(false);
  }

  @TestOfyAndSql
  void testSuccess_administrativelyLock_admin() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", null, true);
    verifyProperlyLockedDomain(true);
  }

  @TestOfyAndSql
  void testSuccess_administrativelyUnlock_nonAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domainLockUtils.administrativelyApplyUnlock(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    verifyProperlyUnlockedDomain(false);
  }

  @TestOfyAndSql
  void testSuccess_administrativelyUnlock_admin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    domainLockUtils.administrativelyApplyUnlock(
        DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    verifyProperlyUnlockedDomain(true);
  }

  @TestOfyAndSql
  void testSuccess_regularLock_relockSet() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock oldLock =
        domainLockUtils.administrativelyApplyUnlock(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    RegistryLock newLock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    newLock = domainLockUtils.verifyAndApplyLock(newLock.getVerificationCode(), false);
    assertThat(
            getRegistryLockByRevisionId(oldLock.getRevisionId()).get().getRelock().getRevisionId())
        .isEqualTo(newLock.getRevisionId());
  }

  @TestOfyAndSql
  void testSuccess_administrativelyLock_relockSet() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock oldLock =
        domainLockUtils.administrativelyApplyUnlock(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    RegistryLock newLock =
        domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    assertThat(
            getRegistryLockByRevisionId(oldLock.getRevisionId()).get().getRelock().getRevisionId())
        .isEqualTo(newLock.getRevisionId());
  }

  @TestOfyAndSql
  void testSuccess_createUnlock_relockDuration() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.of(standardDays(1)));
    assertThat(lock.getRelockDuration()).isEqualTo(Optional.of(standardDays(1)));
  }

  @TestOfyAndSql
  void testSuccess_unlock_relockSubmitted() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.of(standardHours(6)));
    domainLockUtils.verifyAndApplyUnlock(lock.getVerificationCode(), false);
    assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .url(RelockDomainAction.PATH)
            .method("POST")
            .header("Host", "backend.hostname.fake")
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lock.getRevisionId()))
            .param(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM, "0")
            .etaDelta(
                standardHours(6).minus(standardSeconds(30)),
                standardDays(6).plus(standardSeconds(30))));
  }

  @TestOfyAndSql
  void testSuccess_adminCanLockLockedDomain_withNoSavedLock() {
    // in the case of inconsistencies / errors, admins should have the ability to override
    // whatever statuses exist on the domain
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLock resultLock =
        domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, true);
    verifyProperlyLockedDomain(true);
    assertThat(resultLock.getLockCompletionTime()).isEqualTo(Optional.of(clock.nowUtc()));
  }

  @TestOfyAndSql
  void testSuccess_adminCanLockUnlockedDomain_withSavedLock() {
    // in the case of inconsistencies / errors, admins should have the ability to override
    // what the RegistryLock table says
    SqlHelper.saveRegistryLock(
        new RegistryLock.Builder()
            .setLockCompletionTime(clock.nowUtc())
            .setDomainName(DOMAIN_NAME)
            .setVerificationCode("hi")
            .setRegistrarId("TheRegistrar")
            .setRepoId(domain.getRepoId())
            .isSuperuser(false)
            .setRegistrarPocId(POC_ID)
            .build());
    clock.advanceOneMilli();
    RegistryLock resultLock =
        domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, true);
    verifyProperlyLockedDomain(true);
    assertThat(resultLock.getLockCompletionTime()).isEqualTo(Optional.of(clock.nowUtc()));
  }

  @TestOfyAndSql
  void testFailure_createUnlock_alreadyPendingUnlock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domainLockUtils.saveNewRegistryUnlockRequest(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false, Optional.empty())))
        .hasMessageThat()
        .isEqualTo("A pending unlock action already exists for example.tld");
  }

  @TestOfyAndSql
  void testFailure_createUnlock_nonAdminUnlockingAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false, Optional.empty())))
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot unlock admin-locked domain example.tld");
  }

  @TestOfyAndSql
  void testFailure_createLock_unknownDomain() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryLockRequest(
                        "asdf.tld", "TheRegistrar", POC_ID, false)))
        .hasMessageThat()
        .isEqualTo("Domain doesn't exist");
  }

  @TestOfyAndSql
  void testFailure_createLock_alreadyPendingLock() {
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

  @TestOfyAndSql
  void testFailure_createLock_alreadyLocked() {
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

  @TestOfyAndSql
  void testFailure_createUnlock_alreadyUnlocked() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    domainLockUtils.saveNewRegistryUnlockRequest(
                        DOMAIN_NAME, "TheRegistrar", false, Optional.empty())))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already unlocked");
  }

  @TestOfyAndSql
  void testFailure_applyLock_alreadyApplied() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    domain = loadByEntity(domain);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already locked");
    assertNoDomainChanges();
  }

  @TestOfyAndSql
  void testFailure_applyLock_expired() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(standardDays(1));
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), true)))
        .hasMessageThat()
        .isEqualTo("The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @TestOfyAndSql
  void testFailure_applyLock_nonAdmin_applyAdminLock() {
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

  @TestOfyAndSql
  void testFailure_applyUnlock_alreadyUnlocked() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyAndApplyLock(lock.getVerificationCode(), false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false);

    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> domainLockUtils.verifyAndApplyUnlock(unlock.getVerificationCode(), false)))
        .hasMessageThat()
        .isEqualTo("Domain example.tld is already unlocked");
    assertNoDomainChanges();
  }

  @TestOfyAndSql
  void testFailure_applyLock_alreadyLocked() {
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
    assertThat(loadByEntity(domain).getStatusValues())
        .containsAtLeastElementsIn(REGISTRY_LOCK_STATUSES);
    DomainHistory historyEntry =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE, DomainHistory.class);
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
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    ImmutableList<DomainHistory> historyEntries =
        getHistoryEntriesOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE, DomainHistory.class);
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

  private void assertNoDomainChanges() {
    assertThat(loadByEntity(domain)).isEqualTo(domain);
  }

  private void assertBillingEvent(DomainHistory historyEntry) {
    assertBillingEvents(ImmutableList.of(historyEntry));
  }

  private void assertBillingEvents(ImmutableList<DomainHistory> historyEntries) {
    Set<BillingEvent> expectedEvents =
        historyEntries.stream()
            .map(
                entry ->
                    new BillingEvent.OneTime.Builder()
                        .setReason(Reason.SERVER_STATUS)
                        .setTargetId(domain.getForeignKey())
                        .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                        .setCost(Registry.get(domain.getTld()).getRegistryLockOrUnlockBillingCost())
                        .setEventTime(clock.nowUtc())
                        .setBillingTime(clock.nowUtc())
                        .setParent(entry)
                        .build())
            .collect(Collectors.toSet());
    DatabaseHelper.assertBillingEvents(expectedEvents);
  }
}
