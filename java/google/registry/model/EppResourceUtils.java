// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import static com.google.common.collect.Iterables.transform;
import static google.registry.model.RoidSuffixes.getRoidSuffixForTld;
import static google.registry.model.index.ForeignKeyIndex.loadAndGetKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;
import static google.registry.util.DateTimeUtils.latestOf;

import com.google.common.base.Function;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.util.ResultNow;
import google.registry.model.EppResource.Builder;
import google.registry.model.EppResource.BuilderWithTransferData;
import google.registry.model.EppResource.ForeignKeyedEppResource;
import google.registry.model.EppResource.ResourceWithTransferData;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainApplication;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.CommitLogManifest;
import google.registry.model.ofy.CommitLogMutation;
import google.registry.model.transfer.TransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.util.FormattingLogger;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/** Utilities for working with {@link EppResource}. */
public final class EppResourceUtils {

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  /** Returns the full domain repoId in the format HEX-TLD for the specified long id and tld. */
  public static String createDomainRepoId(long repoId, String tld, boolean requireUniqueRoidSuffix) {
    return createRepoId(repoId, getRoidSuffixForTld(tld, requireUniqueRoidSuffix));
  }

  /** Returns the full repoId in the format HEX-TLD for the specified long id and ROID suffix. */
  public static String createRepoId(long repoId, String roidSuffix) {
    // %X is uppercase hexadecimal.
    return String.format("%X-%s", repoId, roidSuffix);
  }

  /** Helper to call {@link EppResource#cloneProjectedAtTime} without warnings. */
  @SuppressWarnings("unchecked")
  private static final <T extends EppResource> T cloneProjectedAtTime(T resource, DateTime now) {
    return (T) resource.cloneProjectedAtTime(now);
  }

  /**
   * Loads the last created version of an {@link EppResource} from the datastore by foreign key.
   *
   * <p>Returns null if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in the datastore. Rather, the resource
   * must be combined with a timestamp to view its current state. We use a global last updated
   * timestamp on the entire entity group (which is essentially free since all writes to the entity
   * group must be serialized anyways) to guarantee monotonically increasing write times, so
   * forwarding our projected time to the greater of "now", and this update timestamp guarantees
   * that we're not projecting into the past.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to project resources at
   */
  @Nullable
  public static <T extends EppResource> T loadByForeignKey(
      Class<T> clazz, String foreignKey, DateTime now) {
    checkArgument(
        ForeignKeyedEppResource.class.isAssignableFrom(clazz),
        "loadByForeignKey may only be called for foreign keyed EPP resources");
    Key<T> resourceKey = loadAndGetKey(clazz, foreignKey, now);
    if (resourceKey == null) {
      return null;
    }
    T resource = ofy().load().key(resourceKey).now();
    if (resource == null || isAtOrAfter(now, resource.getDeletionTime())) {
      return null;
    }
    // When setting status values based on a time, choose the greater of "now" and the resource's
    // UpdateAutoTimestamp. For non-mutating uses (info, whois, etc.), this is equivalent to rolling
    // "now" forward to at least the last update on the resource, so that a read right after a write
    // doesn't appear stale. For mutating flows, if we had to roll now forward then the flow will
    // fail when it tries to save anything via Ofy, since "now" is needed to be > the last update
    // time for writes.
    return cloneProjectedAtTime(
        resource, latestOf(now, resource.getUpdateAutoTimestamp().getTimestamp()));
  }

  /**
   * Returns the domain application with the given application id if it exists, or null if it does
   * not or is soft-deleted as of the given time.
   */
  @Nullable
  public static DomainApplication loadDomainApplication(String applicationId, DateTime now) {
    DomainApplication application =
        ofy().load().key(Key.create(DomainApplication.class, applicationId)).now();
    if (application == null || isAtOrAfter(now, application.getDeletionTime())) {
      return null;
    }
    // Applications don't have any speculative changes that become effective later, so no need to
    // clone forward in time.
    return application;
  }

  /**
   * Checks multiple {@link EppResource} objects from the datastore by unique ids.
   *
   * <p>There are currently no resources that support checks and do not use foreign keys. If we need
   * to support that case in the future, we can loosen the type to allow any {@link EppResource} and
   * add code to do the lookup by id directly.
   *
   * @param clazz the resource type to load
   * @param uniqueIds a list of ids to match
   * @param now the logical time of the check
   */
  public static <T extends EppResource> Set<String> checkResourcesExist(
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
    return transform(
        ofy().load().type(clazz)
            .filter(filterDefinition, filterValue)
            .filter("deletionTime >", now.toDate()),
        EppResourceUtils.<T>transformAtTime(now));
  }

  /**
   * Returns a Function that transforms an EppResource to the given DateTime, suitable for use with
   * Iterables.transform() over a collection of EppResources.
   */
  public static <T extends EppResource> Function<T, T> transformAtTime(final DateTime now) {
    return new Function<T, T>() {
      @Override
      public T apply(T resource) {
        return cloneProjectedAtTime(resource, now);
      }};
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
  public static <B extends Builder<?, B> & BuilderWithTransferData<B>>
      void setAutomaticTransferSuccessProperties(B builder, TransferData transferData) {
    checkArgument(TransferStatus.PENDING.equals(transferData.getTransferStatus()));
    builder.removeStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(transferData.asBuilder()
            .setTransferStatus(TransferStatus.SERVER_APPROVED)
            .setServerApproveEntities(null)
            .setServerApproveBillingEvent(null)
            .setServerApproveAutorenewEvent(null)
            .setServerApproveAutorenewPollMessage(null)
            .build())
        .setLastTransferTime(transferData.getPendingTransferExpirationTime())
        .setCurrentSponsorClientId(transferData.getGainingClientId());
  }

  /**
   * Perform common operations for projecting an {@link EppResource} at a given time:
   * <ul>
   *   <li>Process an automatic transfer.
   * </ul>
   */
  public static <
      T extends EppResource & ResourceWithTransferData,
      B extends Builder<?, B> & BuilderWithTransferData<B>>
          void projectResourceOntoBuilderAtTime(T resource, B builder, DateTime now) {
    TransferData transferData = resource.getTransferData();
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
   * {@code resource} should be whatever's currently in datastore.
   *
   * <p><b>Warning:</b> Revisions are granular to 24-hour periods. It's recommended that
   * {@code timestamp} be set to midnight. Otherwise you must take into consideration that under
   * certain circumstances, a resource might be restored to a revision on the previous day, even if
   * there were revisions made earlier on the same date as {@code timestamp}; however, a resource
   * will never be restored to a revision occuring after {@code timestamp}. This behavior is due to
   * the way {@link google.registry.model.translators.CommitLogRevisionsTranslatorFactory
   * CommitLogRevisionsTranslatorFactory} manages the {@link EppResource#revisions} field. Please
   * note however that the creation and deletion times of a resource are granular to the
   * millisecond.
   *
   * @return an asynchronous operation returning resource at {@code timestamp} or {@code null} if
   *     if resource is deleted or not yet created
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
        (isAtOrAfter(timestamp, resource.getUpdateAutoTimestamp().getTimestamp()))
            ? new ResultNow<>(resource)
            : loadMostRecentRevisionAtTime(resource, timestamp);
    return new Result<T>() {
      @Override
      public T now() {
        T loadedResource = loadResult.now();
        return loadedResource == null ? null
            : (isActive(loadedResource, timestamp)
                ? cloneProjectedAtTime(loadedResource, timestamp)
                : null);
      }};
  }

  /**
   * Returns an asynchronous result holding the most recent datastore revision of a given
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
      logger.severefmt("No revision found for %s, falling back to resource.", resourceKey);
      return new ResultNow<>(resource);
    }
    final Result<CommitLogMutation> mutationResult =
        ofy().load().key(CommitLogMutation.createKey(revision, resourceKey));
    return new Result<T>() {
      @Override
      public T now() {
        CommitLogMutation mutation = mutationResult.now();
        if (mutation != null) {
          return ofy().load().fromEntity(mutation.getEntity());
        }
        logger.severefmt(
            "Couldn't load mutation for revision at %s for %s, falling back to resource."
                + " Revision: %s",
            timestamp, resourceKey, revision);
        return resource;
      }
    };
  }

  @Nullable
  private static <T extends EppResource> Key<CommitLogManifest>
      findMostRecentRevisionAtTime(final T resource, final DateTime timestamp) {
    final Key<T> resourceKey = Key.create(resource);
    Entry<?, Key<CommitLogManifest>> revision = resource.getRevisions().floorEntry(timestamp);
    if (revision != null) {
      logger.infofmt("Found revision history at %s for %s: %s", timestamp, resourceKey, revision);
      return revision.getValue();
    }
    // Fall back to the earliest revision if we don't have one before the requested timestamp.
    revision = resource.getRevisions().firstEntry();
    if (revision != null) {
      logger.severefmt("Found no revision history at %s for %s, using earliest revision: %s",
          timestamp, resourceKey, revision);
      return revision.getValue();
    }
    // Ultimate fallback: There are no revisions whatsoever, so return null.
    logger.severefmt("Found no revision history at all for %s", resourceKey);
    return null;
  }

  /**
   * Find keys of domains or applications that reference a specified contact or host.
   *
   * <p>This is an eventually consistent query.
   *
   * @param clazz the referent type (contact or host)
   * @param key the referent key
   * @param now the logical time of the check
   * @param limit max number of keys to return
   */
  public static List<Key<DomainBase>> queryDomainsUsingResource(
      Class<? extends EppResource> clazz, Key<? extends EppResource> key, DateTime now, int limit) {
    checkArgument(ContactResource.class.equals(clazz) || HostResource.class.equals(clazz));
    return ofy()
        .load()
        .type(DomainBase.class)
        .filter(clazz.equals(ContactResource.class) ? "allContacts.contact" : "nsHosts", key)
        .filter("deletionTime >", now)
        .limit(limit)
        .keys()
        .list();
  }

  /** Clone a contact or host with an eventually-consistent notion of LINKED. */
  public static EppResource cloneResourceWithLinkedStatus(EppResource resource, DateTime now) {
    Builder<?, ?> builder = resource.asBuilder();
    if (queryDomainsUsingResource(resource.getClass(), Key.create(resource), now, 1).isEmpty()) {
      builder.removeStatusValue(StatusValue.LINKED);
    } else {
      builder.addStatusValue(StatusValue.LINKED);
    }
    return builder.build();
  }

  /** Exception to throw when failing to parse a repo id. */
  public static class InvalidRepoIdException extends Exception {

    public InvalidRepoIdException(String message) {
      super(message);
    }
  }

  private EppResourceUtils() {}
}
