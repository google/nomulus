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

package google.registry.model.registry.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.model.ImmutableObject.Insignificant;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.persistence.transaction.QueryComposer.Comparator.EQ;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.mapper.Mapper;
import google.registry.model.Buildable;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.DomainLabelMetrics.MetricsReservedListMatch;
import google.registry.schema.replay.NonReplicatedEntity;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.PostPersist;
import javax.persistence.PreRemove;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.Table;
import org.joda.time.DateTime;

/**
 * A list of reserved domain labels that are blocked from being registered for various reasons.
 *
 * <p>Note that the primary key of this entity is {@link #revisionId}, which is auto-generated by
 * the database. So, if a retry of insertion happens after the previous attempt unexpectedly
 * succeeds, we will end up with having two exact same reserved lists that differ only by
 * revisionId. This is fine though, because we only use the list with the highest revisionId.
 */
@Entity
@ReportedOn
@javax.persistence.Entity
@Table(indexes = {@Index(columnList = "name", name = "reservedlist_name_idx")})
public final class ReservedList
    extends BaseDomainLabelList<ReservationType, ReservedList.ReservedListEntry>
    implements NonReplicatedEntity {

  /**
   * Mapping from domain name to its reserved list info.
   *
   * <p>This field requires special treatment since we want to lazy load it. We have to remove it
   * from the immutability contract so we can modify it after construction and we have to handle the
   * database processing on our own so we can detach it after load.
   */
  @Mapify(ReservedListEntry.LabelMapper.class)
  @Insignificant
  @Transient
  Map<String, ReservedListEntry> reservedListMap;

  @Column(nullable = false)
  boolean shouldPublish = true;

  @PreRemove
  void preRemove() {
    jpaTm()
        .query("DELETE FROM ReservedEntry WHERE revision_id = :revisionId")
        .setParameter("revisionId", revisionId)
        .executeUpdate();
  }

  /**
   * Hibernate hook called on the insert of a new ReservedList. Stores the associated {@link
   * ReservedEntry}'s.
   *
   * <p>We need to persist the list entries, but only on the initial insert (not on update) since
   * the entries themselves never get changed, so we only annotate it with {@link PostPersist}, not
   * {@link PostUpdate}.
   */
  @PostPersist
  void postPersist() {
    if (reservedListMap != null) {
      reservedListMap.values().stream()
          .forEach(
              entry -> {
                // We can safely change the revision id since it's "Insignificant".
                entry.revisionId = revisionId;
                jpaTm().insert(entry);
              });
    }
  }

  /**
   * A reserved list entry entity, persisted to Datastore, that represents a single label and its
   * reservation type.
   */
  @Embed
  @javax.persistence.Entity(name = "ReservedEntry")
  public static class ReservedListEntry extends DomainLabelEntry<ReservationType, ReservedListEntry>
      implements Buildable, NonReplicatedEntity, Serializable {

    @Insignificant @Id Long revisionId;

    @Column(nullable = false)
    ReservationType reservationType;

    /** Mapper for use with @Mapify */
    static class LabelMapper implements Mapper<String, ReservedListEntry> {

      @Override
      public String getKey(ReservedListEntry entry) {
        return entry.getLabel();
      }
    }

    /** Creates a {@link ReservedListEntry} from a label, reservation type, and optional comment. */
    public static ReservedListEntry create(
        String label, ReservationType reservationType, @Nullable String comment) {
      return new ReservedListEntry.Builder()
          .setLabel(label)
          .setReservationType(reservationType)
          .setComment(comment)
          .build();
    }

    @Override
    public ReservationType getValue() {
      return reservationType;
    }

    @Override
    public ReservedListEntry.Builder asBuilder() {
      return new ReservedListEntry.Builder(clone(this));
    }

    @Override
    public String toString() {
      return String.format(
          "%s,%s%s", label, reservationType, isNullOrEmpty(comment) ? "" : " # " + comment);
    }

    /** A builder for constructing {@link ReservedListEntry} objects, since they are immutable. */
    private static class Builder
        extends DomainLabelEntry.Builder<ReservedListEntry, ReservedListEntry.Builder> {

      public Builder() {}

      private Builder(ReservedListEntry instance) {
        super(instance);
      }

      ReservedListEntry.Builder setReservationType(ReservationType reservationType) {
        getInstance().reservationType = reservationType;
        return this;
      }
    }
  }

  @Override
  protected boolean refersToKey(Registry registry, Key<? extends BaseDomainLabelList<?, ?>> key) {
    return registry.getReservedLists().contains(key);
  }

  /** Determines whether the ReservedList is in use on any Registry */
  public boolean isInUse() {
    return !getReferencingTlds().isEmpty();
  }

  /**
   * Returns whether this reserved list is included in the concatenated list of reserved terms
   * published to Google Drive for viewing by registrars.
   */
  public boolean getShouldPublish() {
    return shouldPublish;
  }

  /**
   * Returns a {@link Map} of domain labels to {@link ReservedListEntry}.
   *
   * <p>Note that this involves a database fetch of a potentially large number of elements and
   * should be avoided unless necessary.
   */
  public synchronized ImmutableMap<String, ReservedListEntry> getReservedListEntries() {
    if (reservedListMap == null) {
      reservedListMap =
          jpaTm()
              .transact(
                  () ->
                      jpaTm()
                          .createQueryComposer(ReservedListEntry.class)
                          .where("revisionId", EQ, revisionId)
                          .stream()
                          .collect(toImmutableMap(ReservedListEntry::getLabel, e -> e)));
    }
    return ImmutableMap.copyOf(nullToEmpty(reservedListMap));
  }

  /**
   * Gets a ReservedList by name using the caching layer.
   *
   * @return An Optional&lt;ReservedList&gt; that has a value if a reserved list exists by the given
   *     name, or absent if not.
   * @throws UncheckedExecutionException if some other error occurs while trying to load the
   *     ReservedList from the cache or Datastore.
   */
  public static Optional<ReservedList> get(String listName) {
    return getFromCache(listName, cache);
  }

  /** Loads a ReservedList from its Objectify key. */
  public static Optional<ReservedList> load(Key<ReservedList> key) {
    return get(key.getName());
  }

  /**
   * Queries the set of all reserved lists associated with the specified TLD and returns the
   * reservation types of the label.
   *
   * <p>If the label is in none of the lists, it returns an empty set.
   */
  public static ImmutableSet<ReservationType> getReservationTypes(String label, String tld) {
    checkNotNull(label, "label");
    if (label.length() == 0) {
      return ImmutableSet.of(FULLY_BLOCKED);
    }
    return getReservedListEntries(label, tld)
        .stream()
        .map(ReservedListEntry::getValue)
        .collect(toImmutableSet());
  }

  /**
   * Helper function to retrieve the entries associated with this label and TLD, or an empty set if
   * no such entry exists.
   */
  private static ImmutableSet<ReservedListEntry> getReservedListEntries(String label, String tld) {
    DateTime startTime = DateTime.now(UTC);
    Registry registry = Registry.get(checkNotNull(tld, "tld must not be null"));
    ImmutableSet.Builder<ReservedListEntry> entriesBuilder = new ImmutableSet.Builder<>();
    ImmutableSet.Builder<MetricsReservedListMatch> metricMatchesBuilder =
        new ImmutableSet.Builder<>();

    // Loop through all reservation lists and add each of them.
    for (ReservedList rl : loadReservedLists(registry.getReservedLists())) {
      if (rl.getReservedListEntries().containsKey(label)) {
        ReservedListEntry entry = rl.getReservedListEntries().get(label);
        entriesBuilder.add(entry);
        metricMatchesBuilder.add(
            MetricsReservedListMatch.create(rl.getName(), entry.reservationType));
      }
    }
    ImmutableSet<ReservedListEntry> entries = entriesBuilder.build();
    DomainLabelMetrics.recordReservedListCheckOutcome(
        tld, metricMatchesBuilder.build(), DateTime.now(UTC).getMillis() - startTime.getMillis());
    return entries;
  }

  private static ImmutableSet<ReservedList> loadReservedLists(
      ImmutableSet<Key<ReservedList>> reservedListKeys) {
    return reservedListKeys
        .stream()
        .map(
            (listKey) -> {
              try {
                return cache.get(listKey.getName());
              } catch (ExecutionException e) {
                throw new UncheckedExecutionException(
                    String.format(
                        "Could not load the reserved list '%s' from the cache", listKey.getName()),
                    e);
              }
            })
        .collect(toImmutableSet());
  }

  private static LoadingCache<String, ReservedList> cache =
      CacheBuilder.newBuilder()
          .expireAfterWrite(
              java.time.Duration.ofMillis(getDomainLabelListCacheDuration().getMillis()))
          .build(
              new CacheLoader<String, ReservedList>() {
                @Override
                public ReservedList load(String listName) {
                  return ReservedListDao.getLatestRevision(listName).orElse(null);
                }
              });

  /**
   * Gets the {@link ReservationType} of a label in a single ReservedList, or returns an absent
   * Optional if none exists in the list.
   *
   * <p>Note that this logic is significantly less complicated than the {@link #getReservationTypes}
   * methods, which are applicable to an entire Registry, and need to check across multiple reserved
   * lists.
   */
  public Optional<ReservationType> getReservationInList(String label) {
    ReservedListEntry entry = getReservedListEntries().get(label);
    return Optional.ofNullable(entry == null ? null : entry.reservationType);
  }

  @Override
  @Nullable
  ReservedListEntry createFromLine(String originalLine) {
    List<String> lineAndComment = splitOnComment(originalLine);
    if (lineAndComment.isEmpty()) {
      return null;
    }
    String line = lineAndComment.get(0);
    String comment = lineAndComment.get(1);
    List<String> parts = Splitter.on(',').trimResults().splitToList(line);
    checkArgument(parts.size() == 2 || parts.size() == 3,
        "Could not parse line in reserved list: %s", originalLine);
    String label = parts.get(0);
    ReservationType reservationType = ReservationType.valueOf(parts.get(1));
    return ReservedListEntry.create(label, reservationType, comment);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /**
   * A builder for constructing {@link ReservedList} objects, since they are immutable.
   */
  public static class Builder extends BaseDomainLabelList.Builder<ReservedList, Builder> {
    public Builder() {}

    private Builder(ReservedList instance) {
      super(instance);
    }

    public Builder setReservedListMap(ImmutableMap<String, ReservedListEntry> reservedListMap) {
      getInstance().reservedListMap = reservedListMap;
      return this;
    }

    public Builder setShouldPublish(boolean shouldPublish) {
      getInstance().shouldPublish = shouldPublish;
      return this;
    }

    /**
     * Updates the reservedListMap from input lines.
     *
     * @throws IllegalArgumentException if the lines cannot be parsed correctly.
     */
    public Builder setReservedListMapFromLines(Iterable<String> lines) {
      return setReservedListMap(getInstance().parse(lines));
    }
  }
}
