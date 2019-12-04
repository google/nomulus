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
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import google.registry.model.CreateAutoTimestamp;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.registry.Registry;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.schema.domain.RegistryLock;
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.UserInfo;
import google.registry.util.Clock;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link google.registry.tools.DomainLockUtils}. */
@RunWith(JUnit4.class)
public final class DomainLockUtilsTest {

  @Rule
  public final AppEngineRule appEngineRule =
      AppEngineRule.builder()
          .withDatastore()
          .withUserService(UserInfo.create("marla.singer@example.com", "12345"))
          .build();

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  private final String verificationCode = "f1be78a2-2d61-458c-80f0-9dd8f2f8625f";
  private final Clock clock = new FakeClock();

  private DomainBase domain;

  @Before
  public void setup() {
    createTlds("tld", "net");
    HostResource host = persistActiveHost("ns1.example.net");
    domain = persistResource(newDomainBase("example.tld", host));
  }

  @Test
  public void testSuccess_validateNewLock() {
    RegistryLock lock = createLock();
    DomainLockUtils.validateNewLock(lock, clock);
  }

  @Test
  public void testSuccess_validateUnlock() {
    RegistryLockDao.save(createLock().asBuilder().setCompletionTimestamp(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLock unlock =
        createLock()
            .asBuilder()
            .setVerificationCode(UUID.randomUUID().toString())
            .setAction(Action.UNLOCK)
            .build();
    DomainLockUtils.validateNewLock(unlock, clock);
  }

  @Test
  public void testSuccess_validateUnlock_adminUnlockingAdmin() {
    RegistryLockDao.save(
        createLock().asBuilder().isSuperuser(true).setCompletionTimestamp(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLock unlock =
        createLock()
            .asBuilder()
            .setVerificationCode(UUID.randomUUID().toString())
            .setAction(Action.UNLOCK)
            .isSuperuser(true)
            .build();
    DomainLockUtils.validateNewLock(unlock, clock);
  }

  @Test
  public void testSuccess_validateLock_previousLockVerified() {
    RegistryLockDao.save(
        createLock().asBuilder().setCompletionTimestamp(clock.nowUtc().minusMinutes(1)).build());
    DomainLockUtils.validateNewLock(createLock(), clock);
  }

  @Test
  public void testSuccess_validateLock_previousLockExpired() {
    RegistryLockDao.save(
        createLock()
            .asBuilder()
            .setCreationTimestamp(CreateAutoTimestamp.create(clock.nowUtc().minusHours(2)))
            .build());
    DomainLockUtils.validateNewLock(createLock(), clock);
  }

  @Test
  public void testSuccess_applyLockDomain() {
    RegistryLockDao.save(createLock());
    DomainLockUtils.verifyAndApplyLock(
        RegistryLockDao.getByVerificationCode(verificationCode).get(), true, clock);
    assertThat(reloadDomain().getStatusValues()).containsExactlyElementsIn(REGISTRY_LOCK_STATUSES);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason())
        .isEqualTo("Lock of a domain during a RegistryLock operation");
    assertBillingEvent(historyEntry);
  }

  @Test
  public void testSuccess_applyUnlockDomain() {
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLockDao.save(createLock().asBuilder().setAction(Action.UNLOCK).build());
    DomainLockUtils.verifyAndApplyLock(
        RegistryLockDao.getByVerificationCode(verificationCode).get(), true, clock);
    assertThat(reloadDomain().getStatusValues()).containsNoneIn(REGISTRY_LOCK_STATUSES);
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isTrue();
    assertThat(historyEntry.getBySuperuser()).isFalse();
    assertThat(historyEntry.getReason())
        .isEqualTo("Unlock of a domain during a RegistryLock operation");
    assertBillingEvent(historyEntry);
  }

  @Test
  public void testSuccess_applyAdminLock_onlyHistoryEntry() {
    RegistryLockDao.save(createLock().asBuilder().isSuperuser(true).build());
    DomainLockUtils.verifyAndApplyLock(
        RegistryLockDao.getByVerificationCode(verificationCode).get(), true, clock);

    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_UPDATE);
    assertThat(historyEntry.getRequestedByRegistrar()).isFalse();
    assertThat(historyEntry.getBySuperuser()).isTrue();
    DatastoreHelper.assertNoBillingEvents();
  }

  @Test
  public void testFailure_validateUnlock_alreadyPendingUnlock() {
    // no more than one pending unlock
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    RegistryLockDao.save(
        createLock()
            .asBuilder()
            .setAction(Action.UNLOCK)
            .setCompletionTimestamp(clock.nowUtc())
            .build());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    DomainLockUtils.validateNewLock(
                        createLock().asBuilder().setAction(Action.UNLOCK).build(), clock)))
        .hasMessageThat()
        .isEqualTo("Cannot unlock a domain multiple times");
  }

  @Test
  public void testFailure_validateUnlock_nonAdminUnlockingAdmin() {
    RegistryLockDao.save(
        createLock().asBuilder().isSuperuser(true).setCompletionTimestamp(clock.nowUtc()).build());
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    DomainLockUtils.validateNewLock(
                        createLock().asBuilder().setAction(Action.UNLOCK).build(), clock)))
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot unlock an admin-locked domain");
  }

  @Test
  public void testFailure_validateLock_unknownDomain() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    DomainLockUtils.validateNewLock(
                        createLock().asBuilder().setDomainName("nonexistent.domain").build(),
                        clock)))
        .hasMessageThat()
        .isEqualTo("Unknown domain nonexistent.domain");
  }

  @Test
  public void testFailure_validateLock_alreadyPendingLock() {
    RegistryLockDao.save(createLock());
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> DomainLockUtils.validateNewLock(createLock(), clock)))
        .hasMessageThat()
        .isEqualTo("A pending action already exists for example.tld");
  }

  @Test
  public void testFailure_validateLock_alreadyLocked() {
    persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () -> DomainLockUtils.validateNewLock(createLock(), clock)))
        .hasMessageThat()
        .isEqualTo("Domain already locked");
  }

  @Test
  public void testFailure_validateLock_alreadyUnlocked() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    DomainLockUtils.validateNewLock(
                        createLock().asBuilder().setAction(Action.UNLOCK).build(), clock)))
        .hasMessageThat()
        .isEqualTo("Cannot unlock a domain without a previously-verified lock");
  }

  @Test
  public void testFailure_applyLock_alreadyVerified() {
    RegistryLockDao.save(
        createLock().asBuilder().setCompletionTimestamp(jpaTmRule.getTxnClock().nowUtc()).build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DomainLockUtils.verifyAndApplyLock(
                        RegistryLockDao.getByVerificationCode(verificationCode).get(),
                        true,
                        clock)))
        .hasMessageThat()
        .isEqualTo("This lock / unlock has already been verified");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_expired() {
    RegistryLockDao.save(
        createLock()
            .asBuilder()
            .setCreationTimestamp(CreateAutoTimestamp.create(clock.nowUtc().minusHours(2)))
            .build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DomainLockUtils.verifyAndApplyLock(
                        RegistryLockDao.getByVerificationCode(verificationCode).get(),
                        true,
                        clock)))
        .hasMessageThat()
        .isEqualTo("The pending lock has expired; please try again");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_nonAdmin_applyAdminLock() {
    RegistryLockDao.save(createLock().asBuilder().isSuperuser(true).build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DomainLockUtils.verifyAndApplyLock(
                        RegistryLockDao.getByVerificationCode(verificationCode).get(),
                        false,
                        clock)))
        .hasMessageThat()
        .isEqualTo("Non-admin user cannot verify admin lock");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_alreadyUnlocked() {
    RegistryLockDao.save(createLock().asBuilder().setAction(Action.UNLOCK).build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DomainLockUtils.verifyAndApplyLock(
                        RegistryLockDao.getByVerificationCode(verificationCode).get(),
                        true,
                        clock)))
        .hasMessageThat()
        .isEqualTo("Domain already unlocked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_alreadyLocked() {
    RegistryLockDao.save(createLock());
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () ->
                    DomainLockUtils.verifyAndApplyLock(
                        RegistryLockDao.getByVerificationCode(verificationCode).get(),
                        true,
                        clock)))
        .hasMessageThat()
        .isEqualTo("Domain already locked");
    assertNoDomainChanges();
  }

  @Test
  public void testFailure_applyLock_datastoreFailureDoesNotChangeLockObject() {
    // A failure when performing Datastore actions means that no actions should be taken in the
    // Cloud SQL RegistryLock object
    RegistryLock lock = createLock();
    RegistryLockDao.save(lock);
    // reload the lock to pick up creation time
    lock = RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
    jpaTmRule.getTxnClock().advanceOneMilli();
    domain = persistResource(domain.asBuilder().setStatusValues(REGISTRY_LOCK_STATUSES).build());
    // we fail during the Datastore segment of the action
    assertThrows(
        IllegalStateException.class,
        () ->
            DomainLockUtils.verifyAndApplyLock(
                RegistryLockDao.getByVerificationCode(verificationCode).get(), true, clock));

    // verify that the changes to the SQL object were rolled back
    RegistryLock afterAction =
        RegistryLockDao.getByVerificationCode(lock.getVerificationCode()).get();
    assertThat(afterAction).isEqualTo(lock);
    assertNoDomainChanges();
  }

  private DomainBase reloadDomain() {
    return ofy().load().entity(domain).now();
  }

  private void assertNoDomainChanges() {
    assertThat(reloadDomain()).isEqualTo(domain);
  }

  private void assertBillingEvent(HistoryEntry historyEntry) {
    DatastoreHelper.assertBillingEvents(
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.SERVER_STATUS)
            .setTargetId(domain.getForeignKey())
            .setClientId(domain.getCurrentSponsorClientId())
            .setCost(Registry.get(domain.getTld()).getServerStatusChangeCost())
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setParent(historyEntry)
            .build());
  }

  private RegistryLock createLock() {
    return new RegistryLock.Builder()
        .setDomainName(domain.getFullyQualifiedDomainName())
        .setRegistrarId("TheRegistrar")
        .setRepoId(domain.getRepoId())
        .setRegistrarPocId("marla.singer@example.com")
        .isSuperuser(false)
        .setAction(Action.LOCK)
        .setVerificationCode(verificationCode)
        .build();
  }
}
