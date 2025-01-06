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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.getHistoryEntriesOfType;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.SqlHelper.getRegistryLockByRevisionId;
import static google.registry.testing.SqlHelper.getRegistryLockByVerificationCode;
import static google.registry.testing.SqlHelper.saveRegistryLock;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;
import static org.joda.time.Duration.standardDays;
import static org.joda.time.Duration.standardHours;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.cloud.tasks.v2.HttpMethod;
import com.google.common.collect.ImmutableList;
import google.registry.batch.RelockDomainAction;
import google.registry.model.billing.BillingBase;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.RegistryLock;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.CloudTasksHelper;
import google.registry.testing.CloudTasksHelper.TaskMatcher;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.FakeClock;
import google.registry.testing.SqlHelper;
import google.registry.util.StringGenerator.Alphabets;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link google.registry.tools.DomainLockUtils}. */
public final class DomainLockUtilsTest {

  private static final String DOMAIN_NAME = "example.tld";
  private static final String POC_ID = "marla.singer@example.com";

  private final FakeClock clock = new FakeClock(DateTime.now(DateTimeZone.UTC));
  private DomainLockUtils domainLockUtils;
  private CloudTasksHelper cloudTasksHelper = new CloudTasksHelper(clock);

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private Domain domain;

  @BeforeEach
  void setup() {
    createTlds("tld", "net");
    Host host = persistActiveHost("ns1.example.net");
    domain = persistResource(DatabaseHelper.newDomain(DOMAIN_NAME, host));

    domainLockUtils =
        new DomainLockUtils(
            new DeterministicStringGenerator(Alphabets.BASE_58),
            "adminreg",
            cloudTasksHelper.getTestCloudTasksUtils());
  }

  @Test
  void testSuccess_createLock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    assertNoDomainChanges();
    assertThat(lock.getLockCompletionTime().isPresent()).isFalse();
  }

  @Test
  void testSuccess_createUnlock() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    assertThat(lock.getUnlockCompletionTime().isPresent()).isFalse();
  }

  @Test
  void testSuccess_createUnlock_adminUnlockingAdmin() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", null, true);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    assertThat(lock.getUnlockCompletionTime().isPresent()).isFalse();
  }

  @Test
  void testSuccess_createLock_previousLockExpired() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(standardDays(1));
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    verifyProperlyLockedDomain(false);
  }

  @Test
  void testSuccess_createUnlock_previousUnlockRequestExpired() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.saveNewRegistryUnlockRequest(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    clock.advanceBy(standardDays(1));
    RegistryLock unlockRequest =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyVerificationCode(unlockRequest.getVerificationCode(), false);
    assertThat(loadByEntity(domain).getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
  }

  @Test
  void testSuccess_applyLockDomain() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    verifyProperlyLockedDomain(false);
  }

  @Test
  void testSuccess_applyUnlockDomain() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyVerificationCode(unlock.getVerificationCode(), false);
    verifyProperlyUnlockedDomain(false);
  }

  @Test
  void testSuccess_applyAdminLock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true);
    verifyProperlyLockedDomain(true);
  }

  @Test
  void testSuccess_applyAdminUnlock_onlyHistoryEntry() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    domainLockUtils.verifyVerificationCode(unlock.getVerificationCode(), true);
    verifyProperlyUnlockedDomain(true);
  }

  @Test
  void testSuccess_administrativelyLock_nonAdmin() {
    domainLockUtils.administrativelyApplyLock(
        DOMAIN_NAME, "TheRegistrar", "Marla.Singer@crr.com", false);
    verifyProperlyLockedDomain(false);
  }

  @Test
  void testSuccess_administrativelyLock_admin() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", null, true);
    verifyProperlyLockedDomain(true);
  }

  @Test
  void testSuccess_administrativelyUnlock_nonAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    domainLockUtils.administrativelyApplyUnlock(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    verifyProperlyUnlockedDomain(false);
  }

  @Test
  void testSuccess_administrativelyUnlock_admin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true);
    domainLockUtils.administrativelyApplyUnlock(
        DOMAIN_NAME, "TheRegistrar", true, Optional.empty());
    verifyProperlyUnlockedDomain(true);
  }

  @Test
  void testSuccess_regularLock_relockSet() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock oldLock =
        domainLockUtils.administrativelyApplyUnlock(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    RegistryLock newLock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    newLock = domainLockUtils.verifyVerificationCode(newLock.getVerificationCode(), false);
    assertThat(
            getRegistryLockByRevisionId(oldLock.getRevisionId()).get().getRelock().getRevisionId())
        .isEqualTo(newLock.getRevisionId());
  }

  @Test
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

  @Test
  void testSuccess_createUnlock_relockDuration() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.of(standardDays(1)));
    assertThat(lock.getRelockDuration()).isEqualTo(Optional.of(standardDays(1)));
  }

  @Test
  void testSuccess_unlock_relockSubmitted() {
    domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    RegistryLock lock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.of(standardHours(6)));
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new TaskMatcher()
            .path(RelockDomainAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lock.getRevisionId()))
            .param(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM, "0")
            .scheduleTime(clock.nowUtc().plus(lock.getRelockDuration().get())));
  }

  @Test
  void testSuccess_adminCanLockLockedDomain_withNoSavedLock() {
    // in the case of inconsistencies / errors, admins should have the ability to override
    // whatever statuses exist on the domain
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLock resultLock =
        domainLockUtils.administrativelyApplyLock(DOMAIN_NAME, "TheRegistrar", POC_ID, true);
    verifyProperlyLockedDomain(true);
    assertThat(resultLock.getLockCompletionTime()).isEqualTo(Optional.of(clock.nowUtc()));
  }

  @Test
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

  @Test
  void testFailure_createUnlock_alreadyPendingUnlock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    domainLockUtils.saveNewRegistryUnlockRequest(
        DOMAIN_NAME, "TheRegistrar", false, Optional.empty());

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryUnlockRequest(
                    DOMAIN_NAME, "TheRegistrar", false, Optional.empty()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("A pending unlock action already exists for example.tld");
  }

  @Test
  void testFailure_createUnlock_nonAdminUnlockingAdmin() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryUnlockRequest(
                    DOMAIN_NAME, "TheRegistrar", false, Optional.empty()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot unlock admin-locked domain example.tld");
  }

  @Test
  void testFailure_createLock_unknownDomain() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryLockRequest(
                    "asdf.tld", "TheRegistrar", POC_ID, false));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain doesn't exist");
  }

  @Test
  void testFailure_createLock_alreadyPendingLock() {
    domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryLockRequest(
                    DOMAIN_NAME, "TheRegistrar", POC_ID, false));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("A pending or completed lock action already exists for example.tld");
  }

  @Test
  void testFailure_createLock_alreadyLocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryLockRequest(
                    DOMAIN_NAME, "TheRegistrar", POC_ID, false));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain example.tld is already locked");
  }

  @Test
  void testFailure_createUnlock_alreadyUnlocked() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainLockUtils.saveNewRegistryUnlockRequest(
                    DOMAIN_NAME, "TheRegistrar", false, Optional.empty()));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain example.tld is already unlocked");
  }

  @Test
  void testFailure_applyLock_alreadyApplied() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    domain = loadByEntity(domain);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Lock/unlock with code 123456789ABCDEFGHJKLMNPQRSTUVWXY is already completed");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_applyLock_expired() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    clock.advanceBy(standardDays(1));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), true));
    assertThat(thrown).hasMessageThat().isEqualTo("The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_applyLock_nonAdmin_applyAdminLock() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", null, true);
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false));
    assertThat(thrown).hasMessageThat().isEqualTo("Non-admin user cannot complete admin lock");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_applyUnlock_alreadyUnlocked() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    domainLockUtils.verifyVerificationCode(lock.getVerificationCode(), false);
    RegistryLock unlock =
        domainLockUtils.saveNewRegistryUnlockRequest(
            DOMAIN_NAME, "TheRegistrar", false, Optional.empty());
    domainLockUtils.verifyVerificationCode(unlock.getVerificationCode(), false);

    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.verifyVerificationCode(unlock.getVerificationCode(), false));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Lock/unlock with code Zabcdefghijkmnopqrstuvwxyz123456 is already completed");
    assertNoDomainChanges();
  }

  @Test
  void testFailure_applyLock_alreadyLocked() {
    RegistryLock lock =
        domainLockUtils.saveNewRegistryLockRequest(DOMAIN_NAME, "TheRegistrar", POC_ID, false);
    String verificationCode = lock.getVerificationCode();
    // reload to pick up modification times, etc
    lock = getRegistryLockByVerificationCode(verificationCode).get();
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.verifyVerificationCode(verificationCode, false));
    assertThat(thrown).hasMessageThat().isEqualTo("Domain example.tld is already locked");

    // Failure during the lock acquisition portion shouldn't affect the SQL object
    RegistryLock afterAction = getRegistryLockByVerificationCode(lock.getVerificationCode()).get();
    assertThat(afterAction).isEqualTo(lock);
    assertNoDomainChanges();
  }

  @Test
  void testEnqueueRelock() {
    RegistryLock lock =
        saveRegistryLock(
            new RegistryLock.Builder()
                .setLockCompletionTime(clock.nowUtc())
                .setUnlockRequestTime(clock.nowUtc())
                .setUnlockCompletionTime(clock.nowUtc())
                .isSuperuser(false)
                .setDomainName("example.tld")
                .setRepoId("repoId")
                .setRelockDuration(standardHours(6))
                .setRegistrarId("TheRegistrar")
                .setRegistrarPocId("someone@example.com")
                .setVerificationCode("hi")
                .build());
    domainLockUtils.enqueueDomainRelock(lock.getRelockDuration().get(), lock.getRevisionId(), 0);
    cloudTasksHelper.assertTasksEnqueued(
        QUEUE_ASYNC_ACTIONS,
        new CloudTasksHelper.TaskMatcher()
            .path(RelockDomainAction.PATH)
            .method(HttpMethod.POST)
            .service("backend")
            .param(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lock.getRevisionId()))
            .param(RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM, "0")
            .scheduleTime(clock.nowUtc().plus(lock.getRelockDuration().get())));
  }

  @MockitoSettings(strictness = Strictness.LENIENT)
  @Test
  void testFailure_enqueueRelock_noDuration() {
    RegistryLock lockWithoutDuration =
        saveRegistryLock(
            new RegistryLock.Builder()
                .isSuperuser(false)
                .setDomainName("example.tld")
                .setRepoId("repoId")
                .setRegistrarId("TheRegistrar")
                .setRegistrarPocId("someone@example.com")
                .setVerificationCode("hi")
                .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> domainLockUtils.enqueueDomainRelock(lockWithoutDuration));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format(
                "Lock with ID %s not configured for relock", lockWithoutDuration.getRevisionId()));
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
    assertThat(historyEntries).hasSize(2);
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
    Set<BillingBase> expectedEvents =
        historyEntries.stream()
            .map(
                entry ->
                    new BillingEvent.Builder()
                        .setReason(Reason.SERVER_STATUS)
                        .setTargetId(domain.getForeignKey())
                        .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                        .setCost(Tld.get(domain.getTld()).getRegistryLockOrUnlockBillingCost())
                        .setEventTime(clock.nowUtc())
                        .setBillingTime(clock.nowUtc())
                        .setDomainHistory(entry)
                        .build())
            .collect(Collectors.toSet());
    DatabaseHelper.assertBillingEvents(expectedEvents);
  }
}
