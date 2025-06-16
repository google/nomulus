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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.batch.AsyncTaskEnqueuer.QUEUE_ASYNC_ACTIONS;
import static google.registry.model.EppResourceUtils.loadByForeignKeyByCacheIfEnabled;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import google.registry.batch.CloudTasksUtils;
import google.registry.batch.RelockDomainAction;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.RegistryLock;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.RegistryLockDao;
import google.registry.model.tld.Tld;
import google.registry.request.Action;
import google.registry.util.StringGenerator;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Utility functions for validating and applying {@link RegistryLock}s.
 *
 * <p>For both locks and unlocks, a lock must be requested via the createRegistry*Request methods
 * then verified through the verifyAndApply* methods. These methods will verify that the domain in
 * question is in a lock/unlockable state and will return the lock object.
 */
public final class DomainLockUtils {

  private static final int VERIFICATION_CODE_LENGTH = 32;

  private final StringGenerator stringGenerator;
  private final String registryAdminRegistrarId;
  private final CloudTasksUtils cloudTasksUtils;

  @Inject
  public DomainLockUtils(
      @Named("base58StringGenerator") StringGenerator stringGenerator,
      @Config("registryAdminClientId") String registryAdminRegistrarId,
      CloudTasksUtils cloudTasksUtils) {
    this.stringGenerator = stringGenerator;
    this.registryAdminRegistrarId = registryAdminRegistrarId;
    this.cloudTasksUtils = cloudTasksUtils;
  }

  /**
   * Creates and persists a lock request when requested by a user.
   *
   * <p>The lock will not be applied until {@link #verifyVerificationCode} is called.
   */
  public RegistryLock saveNewRegistryLockRequest(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    return tm().transact(
            () ->
                RegistryLockDao.save(
                    createLockBuilder(domainName, registrarId, registrarPocId, isAdmin).build()));
  }

  /**
   * Creates and persists an unlock request when requested by a user.
   *
   * <p>The unlock will not be applied until {@link #verifyVerificationCode} is called.
   */
  public RegistryLock saveNewRegistryUnlockRequest(
      String domainName, String registrarId, boolean isAdmin, Optional<Duration> relockDuration) {
    return tm().transact(
            () ->
                RegistryLockDao.save(
                    createUnlockBuilder(domainName, registrarId, isAdmin, relockDuration).build()));
  }

  /**
   * Verifies and applies the lock/unlock request previously requested given a verification code.
   *
   * <p>This assumes that the lock object / domain in question has a pending lock or unlock.
   */
  public RegistryLock verifyVerificationCode(String verificationCode, boolean isAdmin) {
    RegistryLock result =
        tm().transact(
                () -> {
                  RegistryLock lock = getByVerificationCode(verificationCode);
                  if (lock.getLockCompletionTime().isEmpty()) {
                    return verifyAndApplyLock(lock, isAdmin);
                  } else if (lock.getUnlockRequestTime().isPresent()
                      && lock.getUnlockCompletionTime().isEmpty()) {
                    return verifyAndApplyUnlock(lock, isAdmin);
                  } else {
                    throw new IllegalArgumentException(
                        String.format(
                            "Lock/unlock with code %s is already completed", verificationCode));
                  }
                });
    if (result.getUnlockCompletionTime().isPresent()) {
      // Submit relock outside the transaction to make sure that it fully succeeded
      submitRelockIfNecessary(result);
    }
    return result;
  }

  /**
   * Creates and applies a lock in one step.
   *
   * <p>This should only be used for admin actions, e.g. Nomulus tool commands or relocks. Note: in
   * the case of relocks, isAdmin is determined by the previous lock.
   */
  public RegistryLock administrativelyApplyLock(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    return tm().transact(
            () -> {
              DateTime now = tm().getTransactionTime();
              RegistryLock newLock =
                  RegistryLockDao.save(
                      createLockBuilder(domainName, registrarId, registrarPocId, isAdmin)
                          .setLockCompletionTime(now)
                          .build());
              applyLockStatuses(newLock, now, isAdmin);
              setAsRelock(newLock);
              return newLock;
            });
  }

  /**
   * Creates and applies an unlock in one step.
   *
   * <p>This should only be used for admin actions, e.g. Nomulus tool commands.
   */
  public RegistryLock administrativelyApplyUnlock(
      String domainName, String registrarId, boolean isAdmin, Optional<Duration> relockDuration) {
    RegistryLock lock =
        tm().transact(
                () -> {
                  DateTime now = tm().getTransactionTime();
                  RegistryLock result =
                      RegistryLockDao.save(
                          createUnlockBuilder(domainName, registrarId, isAdmin, relockDuration)
                              .setUnlockCompletionTime(now)
                              .build());
                  removeLockStatuses(result, isAdmin, now);
                  return result;
                });
    // Submit relock outside the transaction to make sure that it fully succeeded
    submitRelockIfNecessary(lock);
    return lock;
  }

  private void submitRelockIfNecessary(RegistryLock lock) {
    if (lock.getRelockDuration().isPresent()) {
      enqueueDomainRelock(lock);
    }
  }

  /**
   * Enqueues a task to asynchronously re-lock a registry-locked domain after it was unlocked.
   *
   * <p>Note: the relockDuration must be present on the lock object.
   */
  public void enqueueDomainRelock(RegistryLock lock) {
    checkArgument(
        lock.getRelockDuration().isPresent(),
        "Lock with ID %s not configured for relock",
        lock.getRevisionId());
    enqueueDomainRelock(lock.getRelockDuration().get(), lock.getRevisionId(), 0);
  }

  /** Enqueues a task to asynchronously re-lock a registry-locked domain after it was unlocked. */
  public void enqueueDomainRelock(Duration countdown, long lockRevisionId, int previousAttempts) {
    cloudTasksUtils.enqueue(
        QUEUE_ASYNC_ACTIONS,
        cloudTasksUtils.createTaskWithDelay(
            RelockDomainAction.class,
            Action.Method.POST,
            ImmutableMultimap.of(
                RelockDomainAction.OLD_UNLOCK_REVISION_ID_PARAM,
                String.valueOf(lockRevisionId),
                RelockDomainAction.PREVIOUS_ATTEMPTS_PARAM,
                String.valueOf(previousAttempts)),
            countdown));
  }

  private RegistryLock verifyAndApplyLock(RegistryLock lock, boolean isAdmin) {
    DateTime now = tm().getTransactionTime();
    checkArgument(
        !lock.isLockRequestExpired(now), "The pending lock has expired; please try again");

    checkArgument(!lock.isSuperuser() || isAdmin, "Non-admin user cannot complete admin lock");

    RegistryLock newLock =
        RegistryLockDao.save(lock.asBuilder().setLockCompletionTime(now).build());
    setAsRelock(newLock);
    applyLockStatuses(newLock, now, isAdmin);
    return newLock;
  }

  private RegistryLock verifyAndApplyUnlock(RegistryLock lock, boolean isAdmin) {
    DateTime now = tm().getTransactionTime();
    checkArgument(
        !lock.isUnlockRequestExpired(now), "The pending unlock has expired; please try again");

    checkArgument(isAdmin || !lock.isSuperuser(), "Non-admin user cannot complete admin unlock");

    RegistryLock newLock =
        RegistryLockDao.save(lock.asBuilder().setUnlockCompletionTime(now).build());
    removeLockStatuses(newLock, isAdmin, now);
    return newLock;
  }

  private void setAsRelock(RegistryLock newLock) {
    RegistryLockDao.getMostRecentVerifiedUnlockByRepoId(newLock.getRepoId())
        .ifPresent(oldLock -> RegistryLockDao.save(oldLock.asBuilder().setRelock(newLock).build()));
  }

  private RegistryLock.Builder createLockBuilder(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    DateTime now = tm().getTransactionTime();
    Domain domain = getDomain(domainName, registrarId, now);
    verifyDomainNotLocked(domain, isAdmin);

    // Multiple pending actions are not allowed for non-admins
    RegistryLockDao.getMostRecentByRepoId(domain.getRepoId())
        .ifPresent(
            previousLock ->
                checkArgument(
                    previousLock.isLockRequestExpired(now)
                        || previousLock.getUnlockCompletionTime().isPresent()
                        || isAdmin,
                    "A pending or completed lock action already exists for %s",
                    previousLock.getDomainName()));
    return new RegistryLock.Builder()
        .setVerificationCode(stringGenerator.createString(VERIFICATION_CODE_LENGTH))
        .setDomainName(domainName)
        .setRepoId(domain.getRepoId())
        .setRegistrarId(registrarId)
        .setRegistrarPocId(registrarPocId)
        .isSuperuser(isAdmin);
  }

  private RegistryLock.Builder createUnlockBuilder(
      String domainName, String registrarId, boolean isAdmin, Optional<Duration> relockDuration) {
    DateTime now = tm().getTransactionTime();
    Domain domain = getDomain(domainName, registrarId, now);
    Optional<RegistryLock> lockOptional =
        RegistryLockDao.getMostRecentVerifiedLockByRepoId(domain.getRepoId());

    verifyDomainLocked(domain, isAdmin);

    RegistryLock.Builder newLockBuilder;
    if (isAdmin) {
      // Admins should always be able to unlock domains in case we get in a bad state
      // TODO(b/147411297): Remove the admin checks / failsafes once we have migrated existing
      // locked domains to have lock objects
      newLockBuilder =
          lockOptional
              .map(RegistryLock::asBuilder)
              .orElse(
                  new RegistryLock.Builder()
                      .setRepoId(domain.getRepoId())
                      .setDomainName(domainName)
                      .setLockCompletionTime(now)
                      .setRegistrarId(registrarId));
    } else {
      RegistryLock lock =
          lockOptional.orElseThrow(
              () ->
                  new IllegalArgumentException(
                      String.format("No lock object for domain %s", domainName)));
      checkArgument(
          lock.isLocked(), "Lock object for domain %s is not currently locked", domainName);
      checkArgument(
          lock.getUnlockRequestTime().isEmpty() || lock.isUnlockRequestExpired(now),
          "A pending unlock action already exists for %s",
          domainName);
      checkArgument(
          lock.getRegistrarId().equals(registrarId),
          "Lock object does not have registrar ID %s",
          registrarId);
      checkArgument(
          !lock.isSuperuser(), "Non-admin user cannot unlock admin-locked domain %s", domainName);
      newLockBuilder = lock.asBuilder();
    }
    relockDuration.ifPresent(newLockBuilder::setRelockDuration);
    return newLockBuilder
        .setVerificationCode(stringGenerator.createString(VERIFICATION_CODE_LENGTH))
        .isSuperuser(isAdmin)
        .setUnlockRequestTime(now)
        .setRegistrarId(registrarId);
  }

  private static void verifyDomainNotLocked(Domain domain, boolean isAdmin) {
    checkArgument(
        isAdmin || !domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES),
        "Domain %s is already locked",
        domain.getDomainName());
  }

  private static void verifyDomainLocked(Domain domain, boolean isAdmin) {
    checkArgument(
        isAdmin || !Sets.intersection(domain.getStatusValues(), REGISTRY_LOCK_STATUSES).isEmpty(),
        "Domain %s is already unlocked",
        domain.getDomainName());
  }

  private Domain getDomain(String domainName, String registrarId, DateTime now) {
    Domain domain =
        loadByForeignKeyByCacheIfEnabled(Domain.class, domainName, now)
            .orElseThrow(() -> new IllegalArgumentException("Domain doesn't exist"));
    // The user must have specified either the correct registrar ID or the admin registrar ID
    checkArgument(
        registryAdminRegistrarId.equals(registrarId)
            || domain.getCurrentSponsorRegistrarId().equals(registrarId),
        "Domain %s is not owned by registrar %s",
        domainName,
        registrarId);
    return domain;
  }

  private static RegistryLock getByVerificationCode(String verificationCode) {
    return RegistryLockDao.getByVerificationCode(verificationCode)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format("Invalid verification code \"%s\"", verificationCode)));
  }

  private void applyLockStatuses(RegistryLock lock, DateTime lockTime, boolean isAdmin) {
    Domain domain = getDomain(lock.getDomainName(), lock.getRegistrarId(), lockTime);
    verifyDomainNotLocked(domain, isAdmin);

    Domain newDomain =
        domain
            .asBuilder()
            .setStatusValues(
                ImmutableSet.copyOf(Sets.union(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)))
            .build();
    saveEntities(newDomain, lock, lockTime, true);
  }

  private void removeLockStatuses(RegistryLock lock, boolean isAdmin, DateTime unlockTime) {
    Domain domain = getDomain(lock.getDomainName(), lock.getRegistrarId(), unlockTime);
    verifyDomainLocked(domain, isAdmin);

    Domain newDomain =
        domain
            .asBuilder()
            .setStatusValues(
                ImmutableSet.copyOf(
                    Sets.difference(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)))
            .build();
    saveEntities(newDomain, lock, unlockTime, false);
  }

  private static void saveEntities(Domain domain, RegistryLock lock, DateTime now, boolean isLock) {
    String reason =
        String.format(
            "%s of a domain through a RegistryLock operation", isLock ? "Lock" : "Unlock");
    DomainHistory domainHistory =
        new DomainHistory.Builder()
            .setRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setBySuperuser(lock.isSuperuser())
            .setRequestedByRegistrar(!lock.isSuperuser())
            .setType(HistoryEntry.Type.DOMAIN_UPDATE)
            .setModificationTime(now)
            .setDomain(domain)
            .setReason(reason)
            .build();
    tm().update(domain);
    tm().insert(domainHistory);
    if (!lock.isSuperuser()) { // admin actions shouldn't affect billing
      BillingEvent billingEvent =
          new BillingEvent.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId(domain.getForeignKey())
              .setRegistrarId(domain.getCurrentSponsorRegistrarId())
              .setCost(Tld.get(domain.getTld()).getRegistryLockOrUnlockBillingCost())
              .setEventTime(now)
              .setBillingTime(now)
              .setDomainHistory(domainHistory)
              .build();
      tm().insert(billingEvent);
    }
  }
}
