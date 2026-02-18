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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.util.DateTimeUtils.isAtOrAfter;
import static google.registry.util.DateTimeUtils.isBeforeOrAt;

import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntryDao;
import google.registry.model.tld.Tld;
import google.registry.model.transfer.DomainTransferData;
import google.registry.model.transfer.TransferStatus;
import google.registry.persistence.VKey;
import jakarta.persistence.Query;
import java.util.Comparator;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Interval;

/** Utilities for working with {@link EppResource}. */
public final class EppResourceUtils {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

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

  /** Process an automatic transfer on a domain. */
  public static void setAutomaticTransferSuccessProperties(
      DomainBase.Builder<?, ?> builder, DomainTransferData transferData) {
    checkArgument(TransferStatus.PENDING.equals(transferData.getTransferStatus()));
    DomainTransferData.Builder transferDataBuilder = transferData.asBuilder();
    transferDataBuilder.setTransferStatus(TransferStatus.SERVER_APPROVED);
    transferDataBuilder
        .setServerApproveEntities(null, null, null)
        .setServerApproveBillingEvent(null)
        .setServerApproveAutorenewEvent(null)
        .setServerApproveAutorenewPollMessage(null);
    builder
        .removeStatusValue(StatusValue.PENDING_TRANSFER)
        .setTransferData(transferDataBuilder.build())
        .setLastTransferTime(transferData.getPendingTransferExpirationTime())
        .setPersistedCurrentSponsorRegistrarId(transferData.getGainingRegistrarId());
  }

  /**
   * Perform common operations for projecting a {@link Domain} at a given time:
   *
   * <ul>
   *   <li>Process an automatic transfer.
   * </ul>
   */
  public static void projectResourceOntoBuilderAtTime(
      DomainBase domain, DomainBase.Builder<?, ?> builder, DateTime now) {
    DomainTransferData transferData = domain.getTransferData();
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
   * Returns a set of {@link VKey} for domains that reference a specified host.
   *
   * @param key the referent key
   * @param now the logical time of the check
   * @param limit the maximum number of returned keys, unlimited if null
   */
  public static ImmutableSet<VKey<Domain>> getLinkedDomainKeys(
      VKey<Host> key, DateTime now, @Nullable Integer limit) {
    return tm().reTransact(
            () -> {
              Query query =
                  tm().getEntityManager()
                      .createNativeQuery(HOST_LINKED_DOMAIN_QUERY)
                      .setParameter("fkRepoId", key.getKey())
                      .setParameter("now", now.toDate());
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
   * Returns whether the given host is linked to (that is, referenced by) a domain.
   *
   * @param key the referent key
   * @param now the logical time of the check
   */
  public static boolean isLinked(VKey<Host> key, DateTime now) {
    return !getLinkedDomainKeys(key, now, 1).isEmpty();
  }

  private EppResourceUtils() {}
}
