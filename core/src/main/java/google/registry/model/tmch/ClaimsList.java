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

package google.registry.model.tmch;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.CreateAutoTimestamp;
import google.registry.model.ImmutableObject;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.annotations.NotBackedUp;
import google.registry.model.annotations.NotBackedUp.Reason;
import google.registry.model.replay.SqlOnlyEntity;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import java.util.Map;
import java.util.Optional;
import javax.persistence.Column;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.PostPersist;
import javax.persistence.PostUpdate;
import javax.persistence.PreRemove;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.joda.time.DateTime;

/**
 * A list of TMCH claims labels and their associated claims keys.
 *
 * <p>Note that the primary key of this entity is {@link #revisionId}, which is auto-generated by
 * the database. So, if a retry of insertion happens after the previous attempt unexpectedly
 * succeeds, we will end up with having two exact same claims list with only different {@link
 * #revisionId}. However, this is not an actual problem because we only use the claims list with
 * highest {@link #revisionId}.
 *
 * <p>TODO(b/162007765): Remove Datastore related fields and methods.
 */
@NotBackedUp(reason = Reason.EXTERNALLY_SOURCED)
@javax.persistence.Entity(name = "ClaimsList")
@Table
@InCrossTld
public class ClaimsList extends ImmutableObject implements SqlOnlyEntity {

  @javax.persistence.Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long revisionId;

  @Column(nullable = false)
  CreateAutoTimestamp creationTimestamp = CreateAutoTimestamp.create(null);

  /**
   * When the claims list was last updated.
   *
   * <p>Note that the value of this field is parsed from the claims list file(See this <a
   * href="https://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">RFC</>), it is
   * the DNL List creation datetime from the rfc. Since this field has been used by Datastore, we
   * cannot change its name until we finish the migration.
   *
   * <p>TODO(b/177567432): Rename this field to tmdbGenerationTime.
   */
  @Column(name = "tmdb_generation_time", nullable = false)
  DateTime creationTime;

  /**
   * A map from labels to claims keys.
   *
   * <p>This field requires special treatment since we want to lazy load it. We have to remove it
   * from the immutability contract so we can modify it after construction and we have to handle the
   * database processing on our own so we can detach it after load.
   */
  @Insignificant @Transient ImmutableMap<String, String> labelsToKeys;

  @PreRemove
  void preRemove() {
    jpaTm()
        .query("DELETE FROM ClaimsEntry WHERE revision_id = :revisionId")
        .setParameter("revisionId", revisionId)
        .executeUpdate();
  }

  /**
   * Hibernate hook called on the insert of a new ReservedList. Stores the associated {@link
   * ReservedListEntry}'s.
   *
   * <p>We need to persist the list entries, but only on the initial insert (not on update) since
   * the entries themselves never get changed, so we only annotate it with {@link PostPersist}, not
   * {@link PostUpdate}.
   */
  @PostPersist
  void postPersist() {
    if (labelsToKeys != null) {
      labelsToKeys.entrySet().stream()
          .forEach(
              entry ->
                  jpaTm().insert(new ClaimsEntry(revisionId, entry.getKey(), entry.getValue())));
    }
  }

  /** Returns the revision id of this claims list, or throws exception if it is null. */
  public Long getRevisionId() {
    checkState(
        revisionId != null, "revisionId is null because it is not persisted in the database");
    return revisionId;
  }

  /**
   * Returns the time when the external TMDB service generated this revision of the claims list.
   *
   * @see <a href="https://tools.ietf.org/html/draft-lozano-tmch-func-spec-08#section-6.1">DNL List
   *     creation datetime</a>
   */
  public DateTime getTmdbGenerationTime() {
    return creationTime;
  }

  /** Returns the creation time of this claims list. */
  public DateTime getCreationTimestamp() {
    return creationTimestamp.getTimestamp();
  }

  /**
   * Returns the claim key for a given domain if there is one, empty otherwise.
   *
   * <p>Note that this may do a database query. For checking multiple keys against the claims list
   * it may be more efficient to use {@link #getLabelsToKeys()} first, as this will prefetch all
   * entries and cache them locally.
   */
  public Optional<String> getClaimKey(String label) {
    if (labelsToKeys != null) {
      return Optional.ofNullable(labelsToKeys.get(label));
    }
    return jpaTm()
        .transact(
            () ->
                jpaTm()
                    .createQueryComposer(ClaimsEntry.class)
                    .where("revisionId", EQ, revisionId)
                    .where("domainLabel", EQ, label)
                    .first()
                    .map(ClaimsEntry::getClaimKey));
  }

  /**
   * Returns an {@link Map} mapping domain label to its lookup key.
   *
   * <p>Note that this involves a database fetch of a potentially large number of elements and
   * should be avoided unless necessary.
   */
  public ImmutableMap<String, String> getLabelsToKeys() {
    if (labelsToKeys == null) {
      labelsToKeys =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .createQueryComposer(ClaimsEntry.class)
                          .where("revisionId", EQ, revisionId)
                          .stream()
                          .collect(
                              toImmutableMap(
                                  ClaimsEntry::getDomainLabel, ClaimsEntry::getClaimKey)));
    }
    return labelsToKeys;
  }

  /**
   * Returns the number of claims.
   *
   * <p>Note that this will perform a database "count" query if the label to key map has not been
   * previously cached by calling {@link #getLabelsToKeys()}.
   */
  public long size() {
    if (labelsToKeys == null) {
      return jpaTm()
          .createQueryComposer(ClaimsEntry.class)
          .where("revisionId", EQ, revisionId)
          .count();
    }
    return labelsToKeys.size();
  }

  public static ClaimsList create(
      DateTime tmdbGenerationTime, ImmutableMap<String, String> labelsToKeys) {
    ClaimsList instance = new ClaimsList();
    instance.creationTime = checkNotNull(tmdbGenerationTime);
    instance.labelsToKeys = checkNotNull(labelsToKeys);
    return instance;
  }

  /** Exception when trying to directly save a {@link ClaimsList} without sharding. */
  public static class UnshardedSaveException extends RuntimeException {}
}
