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
import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.model.EppResourceUtils.loadByForeignKeyCached;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.model.transaction.TransactionManagerFactory.tm;
import static google.registry.tools.LockOrUnlockDomainCommand.REGISTRY_LOCK_STATUSES;

import com.google.common.base.Strings;
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
import google.registry.schema.domain.RegistryLock.Action;
import google.registry.util.Clock;
import java.util.Optional;

public final class DomainLockUtils {

  public static void validateNewLock(RegistryLock newLock, Clock clock) {
    checkArgument(
        !Strings.isNullOrEmpty(newLock.getDomainName()), "Lock cannot have an empty domain name");

    DomainBase domainBase =
        loadByForeignKeyCached(DomainBase.class, newLock.getDomainName(), clock.nowUtc())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Unknown domain %s", newLock.getDomainName())));

    // Multiple pending actions are not allowed
    Optional<RegistryLock> previousLock =
        RegistryLockDao.getMostRecentByRepoId(domainBase.getRepoId());
    previousLock.ifPresent(
        lock ->
            checkArgument(
                lock.isVerified() || lock.isExpired(clock),
                String.format("A pending action already exists for %s", lock.getDomainName())));

    // Unlock actions have restrictions (unless the user is admin)
    if (!newLock.getAction().equals(Action.LOCK) && !newLock.isSuperuser()) {
      RegistryLock previouslyVerifiedLock =
          previousLock
              .flatMap(
                  lock ->
                      lock.isVerified()
                          ? Optional.of(lock)
                          : RegistryLockDao.getMostRecentVerifiedLockByRepoId(
                              domainBase.getRepoId()))
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Cannot unlock a domain without a previously-verified lock"));
      checkArgument(
          previouslyVerifiedLock.getAction().equals(RegistryLock.Action.LOCK),
          "Cannot unlock a domain multiple times");
      checkArgument(
          !previouslyVerifiedLock.isSuperuser(),
          "Non-admin user cannot unlock an admin-locked domain");
    }
    verifyCurrentDomainLockStatuses(domainBase, newLock);
  }

  public static RegistryLock verifyAndApplyLock(RegistryLock lock, boolean isAdmin, Clock clock) {
    return jpaTm()
        .transact(
            () -> {
              verifyLockObject(lock, clock, isAdmin);
              RegistryLockDao.save(lock.asBuilder().setCompletionTimestamp(clock.nowUtc()).build());
              tm().transact(() -> applyLockStatuses(lock, clock));
              return lock;
            });
  }

  private static void verifyLockObject(RegistryLock lock, Clock clock, boolean isAdmin) {
    checkState(!lock.isVerified(), "This lock / unlock has already been verified");
    checkState(!lock.isExpired(clock), "The pending lock has expired; please try again");
    checkState(!lock.isSuperuser() || isAdmin, "Non-admin user cannot verify admin lock");
  }

  private static void applyLockStatuses(RegistryLock lock, Clock clock) {
    DomainBase domain =
        loadByForeignKey(DomainBase.class, lock.getDomainName(), clock.nowUtc())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format("Domain %s does not exist", lock.getDomainName())));
    verifyCurrentDomainLockStatuses(domain, lock);
    DomainBase.Builder domainBuilder = domain.asBuilder();
    if (lock.getAction().equals(RegistryLock.Action.LOCK)) {
      domainBuilder.setStatusValues(
          ImmutableSet.copyOf(Sets.union(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)));
    } else {
      domainBuilder.setStatusValues(
          ImmutableSet.copyOf(Sets.difference(domain.getStatusValues(), REGISTRY_LOCK_STATUSES)));
    }
    saveEntities(domainBuilder.build(), lock, clock);
  }

  private static void saveEntities(DomainBase domain, RegistryLock lock, Clock clock) {
    String reason = lock.getAction().equals(RegistryLock.Action.LOCK) ? "lock" : "unlock";
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

  private static void verifyCurrentDomainLockStatuses(DomainBase domain, RegistryLock lock) {
    if (lock.getAction().equals(RegistryLock.Action.LOCK)
        && domain.getStatusValues().containsAll(REGISTRY_LOCK_STATUSES)) {
      // lock is valid as long as any of the statuses are not there
      throw new IllegalStateException("Domain already locked");
    } else if (lock.getAction().equals(RegistryLock.Action.UNLOCK)
        && Sets.intersection(domain.getStatusValues(), REGISTRY_LOCK_STATUSES).isEmpty()) {
      // unlock is valid as long as any of the statuses are there
      throw new IllegalStateException("Domain already unlocked");
    }
  }
}
