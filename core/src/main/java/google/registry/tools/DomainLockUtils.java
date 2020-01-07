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
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.googlecode.objectify.Key;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.registry.Registry;
import google.registry.model.registry.RegistryLockDao;
import google.registry.model.reporting.HistoryEntry;
import google.registry.schema.domain.RegistryLock;
import google.registry.util.Clock;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Utility class for validating and applying {@link RegistryLock}s.
 *
 * <p>For both locks and unlocks, a lock must be requested via the createRegistry*Requst methods
 * then verified through the verifyAndApply* methods. These methods will verify that the domain in
 * question is in a lock/unlockable state and will return the lock object.
 */
public final class DomainLockUtils {

  private DomainLockUtils() {}

  public static RegistryLock createRegistryLockRequest(
      String domainName,
      String registrarId,
      @Nullable String registrarPocId,
      boolean isAdmin,
      Clock clock) {
    DomainBase domainBase = getDomain(domainName, clock);
    verifyDomainNotLocked(domainBase);

    // Multiple pending actions are not allowed
    RegistryLockDao.getMostRecentByRepoId(domainBase.getRepoId())
        .ifPresent(
            previousLock ->
                checkArgument(
                    previousLock.isLockRequestExpired(clock) || !previousLock.isLocked(),
                    "A pending or completed lock action already exists for %s",
                    previousLock.getDomainName()));

    RegistryLock lock =
        new RegistryLock.Builder()
            .setVerificationCode(UUID.randomUUID().toString())
            .setDomainName(domainName)
            .setRepoId(domainBase.getRepoId())
            .setRegistrarId(registrarId)
            .setRegistrarPocId(registrarPocId)
            .isSuperuser(isAdmin)
            .build();
    RegistryLockDao.save(lock);
    return lock;
  }

  public static RegistryLock createRegistryUnlockRequest(
      String domainName, String registrarId, boolean isAdmin, Clock clock) {
    DomainBase domainBase = getDomain(domainName, clock);
    verifyDomainLocked(domainBase);

    RegistryLock lock =
        RegistryLockDao.getMostRecentVerifiedLockByRepoId(domainBase.getRepoId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("No lock object for domain %s", domainName)));
    checkArgument(lock.isLocked(), "Lock object for domain %s is not currently locked", domainName);
    checkArgument(
        !lock.getUnlockRequestTimestamp().isPresent() || lock.isUnlockRequestExpired(clock),
        "A pending unlock action already exists for %s",
        domainName);
    checkArgument(
        lock.getRegistrarId().equals(registrarId) || isAdmin,
        "Lock object does not have registrar ID %s",
        registrarId);
    checkArgument(
        !lock.isSuperuser() || isAdmin,
        "Non-admin user cannot unlock admin-locked domain %s",
        domainName);
    RegistryLock newLock =
        lock.asBuilder()
            .setVerificationCode(UUID.randomUUID().toString())
            .isSuperuser(isAdmin)
            .setUnlockRequestTimestamp(clock.nowUtc())
            .setRegistrarId(registrarId)
            .build();
    RegistryLockDao.save(newLock);
    return newLock;
  }

  public static RegistryLock verifyAndApplyLock(
      String verificationCode, boolean isAdmin, Clock clock) {
    return jpaTm()
        .transact(
            () -> {
              RegistryLock lock = getByVerificationCode(verificationCode);

              checkArgument(
                  !lock.getLockCompletionTimestamp().isPresent(),
                  "Domain %s is already locked",
                  lock.getDomainName());

              checkArgument(
                  !lock.isLockRequestExpired(clock),
                  "The pending lock has expired; please try again");

              checkArgument(
                  !lock.isSuperuser() || isAdmin, "Non-admin user cannot complete admin lock");

              RegistryLock newLock =
                  RegistryLockDao.save(
                      lock.asBuilder().setLockCompletionTimestamp(clock.nowUtc()).build());
              tm().transact(() -> applyLockStatuses(newLock, clock));
              return newLock;
            });
  }

  public static RegistryLock verifyAndApplyUnlock(
      String verificationCode, boolean isAdmin, Clock clock) {
    return jpaTm()
        .transact(
            () -> {
              RegistryLock lock = getByVerificationCode(verificationCode);
              checkArgument(
                  !lock.getUnlockCompletionTimestamp().isPresent(),
                  "Domain %s is already unlocked",
                  lock.getDomainName());

              checkArgument(
                  !lock.isUnlockRequestExpired(clock),
                  "The pending unlock has expired; please try again");

              checkArgument(
                  isAdmin || !lock.isSuperuser(), "Non-admin user cannot complete admin unlock");

              RegistryLock newLock =
                  RegistryLockDao.save(
                      lock.asBuilder().setUnlockCompletionTimestamp(clock.nowUtc()).build());
              tm().transact(() -> removeLockStatuses(newLock, isAdmin, clock));
              return newLock;
            });
  }

  private static void verifyDomainNotLocked(DomainBase domainBase) {
    checkArgument(
        !domainBase.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES),
        "Domain %s is already locked",
        domainBase.getFullyQualifiedDomainName());
  }

  private static void verifyDomainLocked(DomainBase domainBase) {
    checkArgument(
        !Sets.intersection(domainBase.getStatusValues(), REGISTRY_LOCK_STATUSES).isEmpty(),
        "Domain %s is already unlocked",
        domainBase.getFullyQualifiedDomainName());
  }

  private static DomainBase getDomain(String domainName, Clock clock) {
    return loadByForeignKeyCached(DomainBase.class, domainName, clock.nowUtc())
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

  private static void applyLockStatuses(RegistryLock lock, Clock clock) {
    DomainBase domain = getDomain(lock.getDomainName(), clock);
    verifyDomainNotLocked(domain);

    DomainBase newDomain =
        domain
            .asBuilder()
            .setStatusValues(
                ImmutableSet.copyOf(Sets.union(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)))
            .build();
    saveEntities(newDomain, lock, clock);
  }

  private static void removeLockStatuses(RegistryLock lock, boolean isAdmin, Clock clock) {
    DomainBase domain = getDomain(lock.getDomainName(), clock);
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
    saveEntities(newDomain, lock, clock);
  }

  private static void saveEntities(DomainBase domain, RegistryLock lock, Clock clock) {
    String reason = "Lock or unlock of a domain through a RegistryLock operation";
    HistoryEntry historyEntry =
        new HistoryEntry.Builder()
            .setClientId(domain.getCurrentSponsorClientId())
            .setBySuperuser(lock.isSuperuser())
            .setRequestedByRegistrar(!lock.isSuperuser())
            .setType(HistoryEntry.Type.DOMAIN_UPDATE)
            .setModificationTime(clock.nowUtc())
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
              .setEventTime(clock.nowUtc())
              .setBillingTime(clock.nowUtc())
              .setParent(historyEntry)
              .build();
      ofy().save().entity(oneTime);
    }
  }
}
