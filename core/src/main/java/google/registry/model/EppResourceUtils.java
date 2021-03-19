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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.util.ResultNow;
import google.registry.config.RegistryConfig;
import google.registry.model.EppResource.BuilderWithTransferData;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.registry.Registry;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.persistence.Query;
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
  // class so we cannot reference its property like domainHost.hostRepoId in a JPQL query.
  private static final String HOST_LINKED_DOMAIN_QUERY =
      "SELECT d.repo_id FROM \"Domain\" d "
          + "JOIN \"DomainHost\" dh ON dh.domain_repo_id = d.repo_id "
          + "WHERE d.deletion_time > :now "
          + "AND dh.host_repo_id = :fkRepoId";

  /** Returns the full domain repoId in the format HEX-TLD for the specified long id and tld. */
  public static String createDomainRepoId(long repoId, String tld) {
    return createRepoId(repoId, Registry.get(tld).getRoidSuffix());
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
   * Loads the last created version of an {@link EppResource} from Datastore by foreign key.
   *
   * <p>Returns empty if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in Datastore. Rather, the resource
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
    return loadByForeignKeyHelper(clazz, foreignKey, now, false);
  }

  /**
   * Loads the last created version of an {@link EppResource} from Datastore by foreign key, using a
   * cache.
   *
   * <p>Returns null if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in Datastore. Rather, the resource
   * must be combined with a timestamp to view its current state. We use a global last updated
   * timestamp on the resource's entity group (which is essentially free since all writes to the
   * entity group must be serialized anyways) to guarantee monotonically increasing write times, and
   * forward our projected time to the greater of this timestamp or "now". This guarantees that
   * we're not projecting into the past.
   *
   * <p>Do not call this cached version for anything that needs transactional consistency. It should
   * only be used when it's OK if the data is potentially being out of date, e.g. WHOIS.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to project resources at
   */
  public static <T extends EppResource> Optional<T> loadByForeignKeyCached(
      Class<T> clazz, String foreignKey, DateTime now) {
    return loadByForeignKeyHelper(
        clazz, foreignKey, now, RegistryConfig.isEppResourceCachingEnabled());
  }

  private static <T extends EppResource> Optional<T> loadByForeignKeyHelper(
      Class<T> clazz, String foreignKey, DateTime now, boolean useCache) {
    checkArgument(
        ForeignKeyedEppResource.class.isAssignableFrom(clazz),
        "loadByForeignKey may only be called for foreign keyed EPP resources");
    ForeignKeyIndex<T> fki =
        useCache
            ? ForeignKeyIndex.loadCached(clazz, ImmutableList.of(foreignKey), now)
                .getOrDefault(foreignKey, null)
            : ForeignKeyIndex.load(clazz, foreignKey, now);
    // The value of fki.getResourceKey() might be null for hard-deleted prober data.
    if (fki == null || isAtOrAfter(now, fki.getDeletionTime()) || fki.getResourceKey() == null) {
      return Optional.empty();
    }
    T resource =
        useCache
            ? EppResource.loadCached(fki.getResourceKey())
            : transactIfJpaTm(() -> tm().loadByKeyIfPresent(fki.getResourceKey()).orElse(null));
    if (resource == null || isAtOrAfter(now, resource.getDeletionTime())) {
      return Optional.empty();
    }
    // When setting status values based on a time, choose the greater of "now" and the resource's
    // UpdateAutoTimestamp. For non-mutating uses (info, whois, etc.), this is equivalent to rolling
    // "now" forward to at least the last update on the resource, so that a read right after a write
    // doesn't appear stale. For mutating flows, if we had to roll now forward then the flow will
    // fail when it tries to save anything via Ofy, since "now" is needed to be > the last update
    // time for writes.
    return Optional.of(
        cloneProjectedAtTime(
            resource, latestOf(now, resource.getUpdateTimestamp().getTimestamp())));
  }

  /**
   * Checks multiple {@link EppResource} objects from Datastore by unique ids.
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
      Class<T> clazz, List<String> uniqueIds, final DateTime now) {
    return ForeignKeyIndex.load(clazz, uniqueIds, now).keySet();
  }

  /**
   * Loads resources that match some filter and that have {@link EppResource#deletionTime} that is
   * not before "now".
   *
   * <p>This is an eventually consistent query.
   *
   * @param clazz the resource type to load
   * @param now the logical time of the check
   * @param filterDefinition the filter to apply when loading resources
   * @param filterValue the acceptable value for the filter
   */
  public static <T extends EppResource> Iterable<T> queryNotDeleted(
      Class<T> clazz, DateTime now, String filterDefinition, Object filterValue) {
    return ofy()
        .load()
        .type(clazz)
        .filter(filterDefinition, filterValue)
        .filter("deletionTime >", now.toDate())
        .list()
        .stream()
        .map(EppResourceUtils.transformAtTime(now))
        .collect(toImmutableSet());
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
    transferDataBuilder.setServerApproveEntities(null);
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
        .setPersistedCurrentSponsorClientId(transferData.getGainingClientId());
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
   * <p>This method costs nothing if {@code resource} is already current. Otherwise it needs to
   * perform a single asynchronous key fetch operation.
   *
   * <p><b>Warning:</b> A resource can only be rolled backwards in time, not forwards; therefore
   * {@code resource} should be whatever's currently in Datastore.
   *
   * <p><b>Warning:</b> Revisions are granular to 24-hour periods. It's recommended that
   * {@code timestamp} be set to midnight. Otherwise you must take into consideration that under
   * certain circumstances, a resource might be restored to a revision on the previous day, even if
   * there were revisions made earlier on the same date as {@code timestamp}; however, a resource
   * will never be restored to a revision occurring after {@code timestamp}. This behavior is due to
   * the way {@link google.registry.model.translators.CommitLogRevisionsTranslatorFactory
   * CommitLogRevisionsTranslatorFactory} manages the {@link EppResource#revisions} field. Please
   * note however that the creation and deletion times of a resource are granular to the
   * millisecond.
   *
   * @return an asynchronous operation returning resource at {@code timestamp} or {@code null} if
   *     resource is deleted or not yet created
   */
  public static <T extends EppResource>
      Result<T> loadAtPointInTime(final T resource, final DateTime timestamp) {
    // If we're before the resource creation time, don't try to find a "most recent revision".
    if (timestamp.isBefore(resource.getCreationTime())) {
      return new ResultNow<>(null);
    }
    // If the resource was not modified after the requested time, then use it as-is, otherwise find
    // the most recent revision asynchronously, and return an async result that wraps that revision
    // and returns it projected forward to exactly the desired timestamp, or null if the resource is
    // deleted at that timestamp.
    final Result<T> loadResult =
        isAtOrAfter(timestamp, resource.getUpdateTimestamp().getTimestamp())
            ? new ResultNow<>(resource)
            : loadMostRecentRevisionAtTime(resource, timestamp);
    return () -> {
      T loadedResource = loadResult.now();
      return (loadedResource == null) ? null
          : (isActive(loadedResource, timestamp)
              ? cloneProjectedAtTime(loadedResource, timestamp)
              : null);
    };
  }

  /**
   * Returns an asynchronous result holding the most recent Datastore revision of a given
   * EppResource before or at the provided timestamp using the EppResource revisions map, falling
   * back to using the earliest revision or the resource as-is if there are no revisions.
   *
   * @see #loadAtPointInTime(EppResource, DateTime)
   */
  private static <T extends EppResource> Result<T> loadMostRecentRevisionAtTime(
      final T resource, final DateTime timestamp) {
    final Key<T> resourceKey = Key.create(resource);
    final Key<CommitLogManifest> revision = findMostRecentRevisionAtTime(resource, timestamp);
    if (revision == null) {
      logger.atSevere().log("No revision found for %s, falling back to resource.", resourceKey);
      return new ResultNow<>(resource);
    }
    final Result<CommitLogMutation> mutationResult =
        ofy().load().key(CommitLogMutation.createKey(revision, resourceKey));
    return () -> {
      CommitLogMutation mutation = mutationResult.now();
      if (mutation != null) {
        return ofy().load().fromEntity(mutation.getEntity());
      }
      logger.atSevere().log(
          "Couldn't load mutation for revision at %s for %s, falling back to resource."
              + " Revision: %s",
          timestamp, resourceKey, revision);
      return resource;
    };
  }

  @Nullable
  private static <T extends EppResource> Key<CommitLogManifest>
      findMostRecentRevisionAtTime(final T resource, final DateTime timestamp) {
    final Key<T> resourceKey = Key.create(resource);
    Entry<?, Key<CommitLogManifest>> revision = resource.getRevisions().floorEntry(timestamp);
    if (revision != null) {
      logger.atInfo().log(
          "Found revision history at %s for %s: %s", timestamp, resourceKey, revision);
      return revision.getValue();
    }
    // Fall back to the earliest revision if we don't have one before the requested timestamp.
    revision = resource.getRevisions().firstEntry();
    if (revision != null) {
      logger.atSevere().log(
          "Found no revision history at %s for %s, using earliest revision: %s",
          timestamp, resourceKey, revision);
      return revision.getValue();
    }
    // Ultimate fallback: There are no revisions whatsoever, so return null.
    logger.atSevere().log("Found no revision history at all for %s", resourceKey);
    return null;
  }

  /**
   * Returns a set of {@link VKey} for domains that reference a specified contact or host.
   *
   * <p>This is an eventually consistent query if used for Datastore.
   *
   * @param key the referent key
   * @param now the logical time of the check
   * @param limit the maximum number of returned keys
   */
  public static ImmutableSet<VKey<DomainBase>> getLinkedDomainKeys(
      VKey<? extends EppResource> key, DateTime now, int limit) {
    checkArgument(
        key.getKind().equals(ContactResource.class) || key.getKind().equals(HostResource.class),
        "key must be either VKey<ContactResource> or VKey<HostResource>, but it is %s",
        key);
    boolean isContactKey = key.getKind().equals(ContactResource.class);
    if (tm().isOfy()) {
      return ofy()
          .load()
          .type(DomainBase.class)
          .filter(isContactKey ? "allContacts.contact" : "nsHosts", key.getOfyKey())
          .filter("deletionTime >", now)
          .limit(limit)
          .keys()
          .list()
          .stream()
          .map(DomainBase::createVKey)
          .collect(toImmutableSet());
    } else {
      return tm().transact(
              () -> {
                Query query;
                if (isContactKey) {
                  query =
                      jpaTm()
                          .query(CONTACT_LINKED_DOMAIN_QUERY, String.class)
                          .setParameter("fkRepoId", key)
                          .setParameter("now", now);
                } else {
                  query =
                      jpaTm()
                          .getEntityManager()
                          .createNativeQuery(HOST_LINKED_DOMAIN_QUERY)
                          .setParameter("fkRepoId", key.getSqlKey())
                          .setParameter("now", now.toDate());
                }
                return (ImmutableSet<VKey<DomainBase>>)
                    query
                        .setMaxResults(limit)
                        .getResultStream()
                        .map(
                            repoId ->
                                DomainBase.createVKey(
                                    Key.create(DomainBase.class, (String) repoId)))
                        .collect(toImmutableSet());
              });
    }
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
