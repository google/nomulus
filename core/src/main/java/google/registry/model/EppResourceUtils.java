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

package google.registry.model;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.config.RegistryConfig;
import google.registry.model.EppResource.BuilderWithTransferData;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.TransactionManager;
import jakarta.persistence.Query;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/** Utilities for working with {@link EppResource}. */
public final class EppResourceUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private static final String CONTACT_LINKED_DOMAIN_QUERY =
      "SELECT repoId FROM Domain "
          + "WHERE (adminContact = :fkRepoId "
          + "OR billingContact = :fkRepoId "
          + "OR techContact = :fkRepoId "
          + "OR registrantContact = :fkRepoId) "
          + "AND deletionTime > :now";

  // We have to use the native SQL query here because DomainHost table doesn't have its entity
  // class, so we cannot reference its property like domainHost.hostRepoId in a JPQL query.
  private static final String HOST_LINKED_DOMAIN_QUERY =
      "SELECT d.repo_id FROM \"Domain\" d "
          + "JOIN \"DomainHost\" dh ON dh.domain_repo_id = d.repo_id "
          + "WHERE d.deletion_time > :now "
          + "AND dh.host_repo_id = :fkRepoId";

  /** Returns the full domain repoId in the format HEX-TLD for the specified long id and tld. */
  public static String createDomainRepoId(long repoId, String tld) {
    return createRepoId(repoId, Tld.get(tld).getRoidSuffix());
  }

  /** Returns the full repoId in the format HEX-TLD for the specified long id and ROID suffix. */
  public static String createRepoId(long repoId, String roidSuffix) {
    // %X is uppercase hexadecimal.
    return String.format("%X-%s", repoId, roidSuffix);
  }

  /** Helper to call {@link EppResource#cloneProjectedAtTime} without warnings. */
  @SuppressWarnings("unchecked")
  private static <T extends EppResource> T cloneProjectedAtTime(T resource, DateTime now) {
    return (T) resource.cloneProjectedAtTime(now);
  }

  /**
   * Loads the last created version of an {@link EppResource} from the database by foreign key.
   *
   * <p>Returns empty if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in the database. Rather, the resource
   * must be combined with a timestamp to view its current state. We use a global last updated
   * timestamp on the resource's entity group (which is essentially free since all writes to the
   * entity group must be serialized anyways) to guarantee monotonically increasing write times, and
   * forward our projected time to the greater of this timestamp or "now". This guarantees that
   * we're not projecting into the past.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to project resources at
   */
  public static <T extends EppResource> Optional<T> loadByForeignKey(
      Class<T> clazz, String foreignKey, DateTime now) {
    return loadByForeignKeyHelper(tm(), clazz, foreignKey, now, false);
  }

  /**
   * Loads the last created version of an {@link EppResource} from the database by foreign key,
   * using a cache, if caching is enabled in config settings.
   *
   * <p>Returns null if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in the database. Rather, the resource
   * must be combined with a timestamp to view its current state. We use a global last updated
   * timestamp to guarantee monotonically increasing write times, and forward our projected time to
   * the greater of this timestamp or "now". This guarantees that we're not projecting into the
   * past.
   *
   * <p>Do not call this cached version for anything that needs transactional consistency. It should
   * only be used when it's OK if the data is potentially being out of date, e.g. WHOIS.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to project resources at
   */
  public static <T extends EppResource> Optional<T> loadByForeignKeyByCacheIfEnabled(
      Class<T> clazz, String foreignKey, DateTime now) {
    return loadByForeignKeyHelper(
        tm(), clazz, foreignKey, now, RegistryConfig.isEppResourceCachingEnabled());
  }

  /**
   * Loads the last created version of an {@link EppResource} from the replica database by foreign
   * key, using a cache.
   *
   * <p>This method ignores the config setting for caching, and is reserved for use cases that can
   * tolerate slightly stale data.
   */
  public static <T extends EppResource> Optional<T> loadByForeignKeyByCache(
      Class<T> clazz, String foreignKey, DateTime now) {
    return loadByForeignKeyHelper(replicaTm(), clazz, foreignKey, now, true);
  }

  private static <T extends EppResource> Optional<T> loadByForeignKeyHelper(
      TransactionManager txnManager,
      Class<T> clazz,
      String foreignKey,
      DateTime now,
      boolean useCache) {
    checkArgument(
        ForeignKeyedEppResource.class.isAssignableFrom(clazz),
        "loadByForeignKey may only be called for foreign keyed EPP resources");
    VKey<T> key =
        useCache
            ? ForeignKeyUtils.loadByCache(clazz, ImmutableList.of(foreignKey), now).get(foreignKey)
            : ForeignKeyUtils.load(clazz, foreignKey, now);
    // The returned key is null if the resource is hard deleted or soft deleted by the given time.
    if (key == null) {
      return Optional.empty();
    }
    T resource =
        useCache
            ? EppResource.loadByCache(key)
            // This transaction is buried very deeply inside many outer nested calls, hence merits
            // the use of reTransact() for now pending a substantial refactoring.
            : txnManager.reTransact(() -> txnManager.loadByKeyIfPresent(key).orElse(null));
    if (resource == null || isAtOrAfter(now, resource.getDeletionTime())) {
      return Optional.empty();
    }
    // When setting status values based on a time, choose the greater of "now" and the resource's
    // UpdateAutoTimestamp. For non-mutating uses (info, whois, etc.), this is equivalent to rolling
    // "now" forward to at least the last update on the resource, so that a read right after a write
    // doesn't appear stale. For mutating flows, if we had to roll now forward then the flow will
    // fail when it tries to save anything, since "now" is needed to be > the last update time for
    // writes.
    return Optional.of(
        cloneProjectedAtTime(
            resource, latestOf(now, resource.getUpdateTimestamp().getTimestamp())));
  }

  /**
   * Checks multiple {@link EppResource} objects from the database by unique ids.
   *
   * <p>There are currently no resources that support checks and do not use foreign keys. If we need
   * to support that case in the future, we can loosen the type to allow any {@link EppResource} and
   * add code to do the lookup by id directly.
   *
   * @param clazz the resource type to load
   * @param uniqueIds a list of ids to match
   * @param now the logical time of the check
   */
  public static <T extends EppResource> ImmutableSet<String> checkResourcesExist(
      Class<T> clazz, Collection<String> uniqueIds, final DateTime now) {
    return ForeignKeyUtils.load(clazz, uniqueIds, now).keySet();
  }

  /**
   * Returns a Function that transforms an EppResource to the given DateTime, suitable for use with
   * Iterables.transform() over a collection of EppResources.
   */
  public static <T extends EppResource> Function<T, T> transformAtTime(final DateTime now) {
    return (T resource) -> cloneProjectedAtTime(resource, now);
  }

  /**
   * The lifetime of a resource is from its creation time, inclusive, through its deletion time,
   * exclusive, which happily maps to the behavior of Interval.
   */
  private static Interval getLifetime(EppResource resource) {
    return new Interval(resource.getCreationTime(), resource.getDeletionTime());
  }

  public static boolean isActive(EppResource resource, DateTime time) {
    return getLifetime(resource).contains(time);
  }

  public static boolean isDeleted(EppResource resource, DateTime time) {
    return !isActive(resource, time);
  }

  /** Process an automatic transfer on a resource. */
  public static <
          T extends TransferData,
          B extends EppResource.Builder<?, B> & BuilderWithTransferData<T, B>>
      void setAutomaticTransferSuccessProperties(B builder, TransferData transferData) {
    checkArgument(TransferStatus.PENDING.equals(transferData.getTransferStatus()));
    TransferData.Builder transferDataBuilder = transferData.asBuilder();
    transferDataBuilder.setTransferStatus(TransferStatus.SERVER_APPROVED);
    transferDataBuilder.setServerApproveEntities(null, null, null);
    if (transferData instanceof DomainTransferData) {
      ((DomainTransferData.Builder) transferDataBuilder)
          .setServerApproveBillingEvent(null)
          .setServerApproveAutorenewEvent(null)
          .setServerApproveAutorenewPollMessage(null);
    }
    builder
        .removeStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData((T) transferDataBuilder.build())
        .setLastTransferTime(transferData.getPendingTransferExpirationTime())
        .setPersistedCurrentSponsorRegistrarId(transferData.getGainingRegistrarId());
  }

  /**
   * Perform common operations for projecting an {@link EppResource} at a given time:
   *
   * <ul>
   *   <li>Process an automatic transfer.
   * </ul>
   */
  public static <
          T extends TransferData,
          E extends EppResource & ResourceWithTransferData<T>,
          B extends EppResource.Builder<?, B> & BuilderWithTransferData<T, B>>
      void projectResourceOntoBuilderAtTime(E resource, B builder, DateTime now) {
    T transferData = resource.getTransferData();
    // If there's a pending transfer that has expired, process it.
    DateTime expirationTime = transferData.getPendingTransferExpirationTime();
    if (TransferStatus.PENDING.equals(transferData.getTransferStatus())
        && isBeforeOrAt(expirationTime, now)) {
      setAutomaticTransferSuccessProperties(builder, transferData);
    }
  }

  /**
   * Rewinds an {@link EppResource} object to a given point in time.
   *
   * <p>This method costs nothing if {@code resource} is already current. Otherwise, it needs to
   * perform a single fetch operation.
   *
   * <p><b>Warning:</b> A resource can only be rolled backwards in time, not forwards; therefore
   * {@code resource} should be whatever's currently in SQL.
   *
   * @return the resource at {@code timestamp} or {@code null} if resource is deleted or not yet
   *     created
   */
  public static <T extends EppResource> T loadAtPointInTime(
      final T resource, final DateTime timestamp) {
    // If we're before the resource creation time, don't try to find a "most recent revision".
    if (timestamp.isBefore(resource.getCreationTime())) {
      return null;
    }
    // If the resource was not modified after the requested time, then use it as-is, otherwise find
    // the most recent revision and project it forward to exactly the desired timestamp, or null if
    // the resource is deleted at that timestamp.
    T loadedResource =
        isAtOrAfter(timestamp, resource.getUpdateTimestamp().getTimestamp())
            ? resource
            : loadMostRecentRevisionAtTime(resource, timestamp);
    return (loadedResource == null)
        ? null
        : (isActive(loadedResource, timestamp)
            ? cloneProjectedAtTime(loadedResource, timestamp)
            : null);
  }

  /**
   * Rewinds an {@link EppResource} object to a given point in time.
   *
   * <p>This method costs nothing if {@code resource} is already current. Otherwise, it returns an
   * async operation that performs a single fetch operation.
   *
   * @return an asynchronous operation returning resource at {@code timestamp} or {@code null} if
   *     resource is deleted or not yet created
   * @see #loadAtPointInTime(EppResource, DateTime)
   */
  public static <T extends EppResource> Supplier<T> loadAtPointInTimeAsync(
      final T resource, final DateTime timestamp) {
    return () -> loadAtPointInTime(resource, timestamp);
  }

  /**
   * Returns the most recent revision of a given EppResource before or at the provided timestamp,
   * falling back to using the resource as-is if there are no revisions.
   *
   * @see #loadAtPointInTime(EppResource, DateTime)
   */
  private static <T extends EppResource> T loadMostRecentRevisionAtTime(
      final T resource, final DateTime timestamp) {
    @SuppressWarnings("unchecked")
    T resourceAtPointInTime =
        (T)
            HistoryEntryDao.loadHistoryObjectsForResource(
                    resource.createVKey(), START_OF_TIME, timestamp)
                .stream()
                .max(Comparator.comparing(HistoryEntry::getModificationTime))
                .flatMap(HistoryEntry::getResourceAtPointInTime)
                .orElse(null);
    if (resourceAtPointInTime == null) {
      logger.atSevere().log(
          "Couldn't load resource at %s for key %s, falling back to resource %s.",
          timestamp, resource.createVKey(), resource);
      return resource;
    }
    return resourceAtPointInTime;
  }

  /**
   * Returns a set of {@link VKey} for domains that reference a specified contact or host.
   *
   * <p>This is an eventually consistent query if used for the database.
   *
   * @param key the referent key
   * @param now the logical time of the check
   * @param limit the maximum number of returned keys, unlimited if null
   */
  public static ImmutableSet<VKey<Domain>> getLinkedDomainKeys(
      VKey<? extends EppResource> key, DateTime now, @Nullable Integer limit) {
    checkArgument(
        key.getKind().equals(Contact.class) || key.getKind().equals(Host.class),
        "key must be either VKey<Contact> or VKey<Host>, but it is %s",
        key);
    boolean isContactKey = key.getKind().equals(Contact.class);
    return tm().reTransact(
            () -> {
              Query query;
              if (isContactKey) {
                query =
                    tm().query(CONTACT_LINKED_DOMAIN_QUERY, String.class)
                        .setParameter("fkRepoId", key)
                        .setParameter("now", now);
              } else {
                query =
                    tm().getEntityManager()
                        .createNativeQuery(HOST_LINKED_DOMAIN_QUERY)
                        .setParameter("fkRepoId", key.getKey())
                        .setParameter("now", now.toDate());
              }
              if (limit != null) {
                query.setMaxResults(limit);
              }
              @SuppressWarnings("unchecked")
              ImmutableSet<VKey<Domain>> domainKeySet =
                  (ImmutableSet<VKey<Domain>>)
                      query
                          .getResultStream()
                          .map(repoId -> Domain.createVKey((String) repoId))
                          .collect(toImmutableSet());
              return domainKeySet;
            });
  }

  /**
   * Returns whether the given contact or host is linked to (that is, referenced by) a domain.
   *
   * <p>This is an eventually consistent query.
   *
   * @param key the referent key
   * @param now the logical time of the check
   */
  public static boolean isLinked(VKey<? extends EppResource> key, DateTime now) {
    return getLinkedDomainKeys(key, now, 1).size() > 0;
  }

  private EppResourceUtils() {}
}
