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
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.batch.AsyncTaskEnqueuer;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.StringGenerator;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Utility functions for validating and applying {@link RegistryLock}s.
 *
 * <p>For both locks and unlocks, a lock must be requested via the createRegistry*Requst methods
 * then verified through the verifyAndApply* methods. These methods will verify that the domain in
 * question is in a lock/unlockable state and will return the lock object.
 */
public final class DomainLockUtils {

  private static final int VERIFICATION_CODE_LENGTH = 32;

  private final StringGenerator stringGenerator;
  private final AsyncTaskEnqueuer asyncTaskEnqueuer;

  @Inject
  public DomainLockUtils(
      @Named("base58StringGenerator") StringGenerator stringGenerator,
      AsyncTaskEnqueuer asyncTaskEnqueuer) {
    this.stringGenerator = stringGenerator;
    this.asyncTaskEnqueuer = asyncTaskEnqueuer;
  }

  /**
   * Creates and persists a lock request when requested by a user.
   *
   * <p>The lock will not be applied until {@link #verifyAndApplyLock} is called.
   */
  public RegistryLock saveNewRegistryLockRequest(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    return jpaTm()
        .transact(
            () ->
                RegistryLockDao.save(
                    createLockBuilder(domainName, registrarId, registrarPocId, isAdmin).build()));
  }

  /**
   * Creates and persists an unlock request when requested by a user.
   *
   * <p>The unlock will not be applied until {@link #verifyAndApplyUnlock} is called.
   */
  public RegistryLock saveNewRegistryUnlockRequest(
      String domainName, String registrarId, boolean isAdmin, Optional<Duration> relockDuration) {
    return jpaTm()
        .transact(
            () ->
                RegistryLockDao.save(
                    createUnlockBuilder(domainName, registrarId, isAdmin, relockDuration).build()));
  }

  /** Verifies and applies the lock request previously requested by a user. */
  public RegistryLock verifyAndApplyLock(String verificationCode, boolean isAdmin) {
    return jpaTm()
        .transact(
            () -> {
              DateTime now = jpaTm().getTransactionTime();
              RegistryLock lock = getByVerificationCode(verificationCode);

              checkArgument(
                  !lock.getLockCompletionTimestamp().isPresent(),
                  "Domain %s is already locked",
                  lock.getDomainName());

              checkArgument(
                  !lock.isLockRequestExpired(now),
                  "The pending lock has expired; please try again");

              checkArgument(
                  !lock.isSuperuser() || isAdmin, "Non-admin user cannot complete admin lock");

              RegistryLock newLock =
                  RegistryLockDao.save(lock.asBuilder().setLockCompletionTimestamp(now).build());
              setAsRelock(newLock);
              tm().transact(() -> applyLockStatuses(newLock, now));
              return newLock;
            });
  }

  /** Verifies and applies the unlock request previously requested by a user. */
  public RegistryLock verifyAndApplyUnlock(String verificationCode, boolean isAdmin) {
    RegistryLock lock =
        jpaTm()
            .transact(
                () -> {
                  DateTime now = jpaTm().getTransactionTime();
                  RegistryLock previousLock = getByVerificationCode(verificationCode);
                  checkArgument(
                      !previousLock.getUnlockCompletionTimestamp().isPresent(),
                      "Domain %s is already unlocked",
                      previousLock.getDomainName());

                  checkArgument(
                      !previousLock.isUnlockRequestExpired(now),
                      "The pending unlock has expired; please try again");

                  checkArgument(
                      isAdmin || !previousLock.isSuperuser(),
                      "Non-admin user cannot complete admin unlock");

                  RegistryLock newLock =
                      RegistryLockDao.save(
                          previousLock.asBuilder().setUnlockCompletionTimestamp(now).build());
                  tm().transact(() -> removeLockStatuses(newLock, isAdmin, now));
                  return newLock;
                });
    // Submit relock outside of the transaction to make sure that it fully succeeded
    submitRelockIfNecessary(lock);
    return lock;
  }

  /**
   * Creates and applies a lock in one step.
   *
   * <p>This should only be used for admin actions, e.g. Nomulus tool commands or relocks. Note: in
   * the case of relocks, isAdmin is determined by the previous lock.
   */
  public RegistryLock administrativelyApplyLock(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    return jpaTm()
        .transact(
            () -> {
              DateTime now = jpaTm().getTransactionTime();
              RegistryLock newLock =
                  RegistryLockDao.save(
                      createLockBuilder(domainName, registrarId, registrarPocId, isAdmin)
                          .setLockCompletionTimestamp(now)
                          .build());
              tm().transact(() -> applyLockStatuses(newLock, now));
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
        jpaTm()
            .transact(
                () -> {
                  DateTime now = jpaTm().getTransactionTime();
                  RegistryLock result =
                      RegistryLockDao.save(
                          createUnlockBuilder(domainName, registrarId, isAdmin, relockDuration)
                              .setUnlockCompletionTimestamp(now)
                              .build());
                  tm().transact(() -> removeLockStatuses(result, isAdmin, now));
                  return result;
                });
    // Submit relock outside of the transaction to make sure that it fully succeeded
    submitRelockIfNecessary(lock);
    return lock;
  }

  private void submitRelockIfNecessary(RegistryLock lock) {
    if (lock.getRelockDuration().isPresent()) {
      asyncTaskEnqueuer.enqueueDomainRelock(lock);
    }
  }

  private void setAsRelock(RegistryLock newLock) {
    jpaTm()
        .transact(
            () ->
                RegistryLockDao.getMostRecentVerifiedUnlockByRepoId(newLock.getRepoId())
                    .ifPresent(
                        oldLock ->
                            RegistryLockDao.save(oldLock.asBuilder().setRelock(newLock).build())));
  }

  private RegistryLock.Builder createLockBuilder(
      String domainName, String registrarId, @Nullable String registrarPocId, boolean isAdmin) {
    DateTime now = jpaTm().getTransactionTime();
    DomainBase domainBase = getDomain(domainName, now);
    verifyDomainNotLocked(domainBase);

    // Multiple pending actions are not allowed
    RegistryLockDao.getMostRecentByRepoId(domainBase.getRepoId())
        .ifPresent(
            previousLock ->
                checkArgument(
                    previousLock.isLockRequestExpired(now)
                        || previousLock.getUnlockCompletionTimestamp().isPresent(),
                    "A pending or completed lock action already exists for %s",
                    previousLock.getDomainName()));

    return new RegistryLock.Builder()
        .setVerificationCode(stringGenerator.createString(VERIFICATION_CODE_LENGTH))
        .setDomainName(domainName)
        .setRepoId(domainBase.getRepoId())
        .setRegistrarId(registrarId)
        .setRegistrarPocId(registrarPocId)
        .isSuperuser(isAdmin);
  }

  private RegistryLock.Builder createUnlockBuilder(
      String domainName, String registrarId, boolean isAdmin, Optional<Duration> relockDuration) {
    DateTime now = jpaTm().getTransactionTime();
    DomainBase domainBase = getDomain(domainName, now);
    Optional<RegistryLock> lockOptional =
        RegistryLockDao.getMostRecentVerifiedLockByRepoId(domainBase.getRepoId());

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
                      .setRepoId(domainBase.getRepoId())
                      .setDomainName(domainName)
                      .setLockCompletionTimestamp(now)
                      .setRegistrarId(registrarId));
    } else {
      verifyDomainLocked(domainBase);
      RegistryLock lock =
          lockOptional.orElseThrow(
              () ->
                  new IllegalArgumentException(
                      String.format("No lock object for domain %s", domainName)));
      checkArgument(
          lock.isLocked(), "Lock object for domain %s is not currently locked", domainName);
      checkArgument(
          !lock.getUnlockRequestTimestamp().isPresent() || lock.isUnlockRequestExpired(now),
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
        .setUnlockRequestTimestamp(now)
        .setRegistrarId(registrarId);
  }

  private static void verifyDomainNotLocked(DomainBase domainBase) {
    checkArgument(
        !domainBase.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES),
        "Domain %s is already locked",
        domainBase.getDomainName());
  }

  private static void verifyDomainLocked(DomainBase domainBase) {
    checkArgument(
        !Sets.intersection(domainBase.getStatusValues(), REGISTRY_LOCK_STATUSES).isEmpty(),
        "Domain %s is already unlocked",
        domainBase.getDomainName());
  }

  private static DomainBase getDomain(String domainName, DateTime now) {
    return loadByForeignKeyCached(DomainBase.class, domainName, now)
        .orElseThrow(
            () -> new IllegalArgumentException(String.format("Unknown domain %s", domainName)));
  }

  private static RegistryLock getByVerificationCode(String verificationCode) {
    return RegistryLockDao.getByVerificationCode(verificationCode)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    String.format("Invalid verification code %s", verificationCode)));
  }

  private static void applyLockStatuses(RegistryLock lock, DateTime lockTime) {
    DomainBase domain = getDomain(lock.getDomainName(), lockTime);
    verifyDomainNotLocked(domain);

    DomainBase newDomain =
        domain
            .asBuilder()
            .setStatusValues(
                ImmutableSet.copyOf(Sets.union(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)))
            .build();
    saveEntities(newDomain, lock, lockTime, true);
  }

  private static void removeLockStatuses(RegistryLock lock, boolean isAdmin, DateTime unlockTime) {
    DomainBase domain = getDomain(lock.getDomainName(), unlockTime);
    if (!isAdmin) {
      verifyDomainLocked(domain);
    }

    DomainBase newDomain =
        domain
            .asBuilder()
            .setStatusValues(
                ImmutableSet.copyOf(
                    Sets.difference(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)))
            .build();
    saveEntities(newDomain, lock, unlockTime, false);
  }

  private static void saveEntities(
      DomainBase domain, RegistryLock lock, DateTime now, boolean isLock) {
    String reason =
        String.format(
            "%s of a domain through a RegistryLock operation", isLock ? "Lock" : "Unlock");
    HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setClientId(domain.getCurrentSponsorClientId())
            .setBySuperuser(lock.isSuperuser())
            .setRequestedByRegistrar(!lock.isSuperuser())
            .setType(HistoryEntry.Type.DOMAIN_UPDATE)
            .setModificationTime(now)
            .setParent(Key.create(domain))
            .setReason(reason)
            .build();
    ofy().save().entities(domain, historyEntry);
    if (!lock.isSuperuser()) { // admin actions shouldn't affect billing
      BillingEvent.OneTime oneTime =
          new BillingEvent.OneTime.Builder()
              .setReason(Reason.SERVER_STATUS)
              .setTargetId(domain.getForeignKey())
              .setClientId(domain.getCurrentSponsorClientId())
              .setCost(Registry.get(domain.getTld()).getServerStatusChangeCost())
              .setEventTime(now)
              .setBillingTime(now)
              .setParent(historyEntry)
              .build();
      ofy().save().entity(oneTime);
    }
  }
}
