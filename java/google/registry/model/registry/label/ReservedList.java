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

package google.registry.model.registry.label;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.model.registry.label.ReservationType.RESERVED_FOR_ANCHOR_TENANT;
import static google.registry.model.registry.label.ReservationType.UNRESERVED;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.mapper.Mapper;
import google.registry.config.RegistryEnvironment;
import google.registry.model.registry.Registry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * A reserved list entity, persisted to Datastore, that is used to check domain label reservations.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public final class ReservedList
    extends BaseDomainLabelList<ReservationType, ReservedList.ReservedListEntry> {

  @Mapify(ReservedListEntry.LabelMapper.class)
  Map<String, ReservedListEntry> reservedListMap;

  boolean shouldPublish = true;

  /**
   * A reserved list entry entity, persisted to Datastore, that represents a single label and its
   * reservation type.
   */
  @Embed
  public static class ReservedListEntry
      extends DomainLabelEntry<ReservationType, ReservedListEntry> {

    ReservationType reservationType;

    /**
     * Contains the auth code necessary to register a domain with this label. Note that this field
     * will only ever be populated for entries with type RESERVED_FOR_ANCHOR_TENANT.
     */
    String authCode;

    /** Mapper for use with @Mapify */
    static class LabelMapper implements Mapper<String, ReservedListEntry> {
      @Override
      public String getKey(ReservedListEntry entry) {
        return entry.getLabel();
      }
    }

    public static ReservedListEntry create(
        String label, ReservationType reservationType, @Nullable String authCode, String comment) {
      if (authCode != null) {
        checkArgument(
            reservationType == RESERVED_FOR_ANCHOR_TENANT,
            "Only anchor tenant reservations should have an auth code configured");
      } else {
        checkArgument(
            reservationType != RESERVED_FOR_ANCHOR_TENANT,
            "Anchor tenant reservations must have an auth code configured");
      }
      ReservedListEntry entry = new ReservedListEntry();
      entry.label = label;
      entry.reservationType = reservationType;
      entry.authCode = authCode;
      entry.comment = comment;
      return entry;
    }

    @Override
    public ReservationType getValue() {
      return reservationType;
    }

    public String getAuthCode() {
      return authCode;
    }

    public String getComment() {
      return comment;
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

  public ImmutableMap<String, ReservedListEntry> getReservedListEntries() {
    return ImmutableMap.copyOf(nullToEmpty(reservedListMap));
  }

  /**
   * Gets a ReservedList by name using the caching layer.
   *
   * @return An Optional<ReservedList> that has a value if a reserved list exists by the given name,
   *     or absent if not.
   * @throws UncheckedExecutionException if some other error occurs while trying to load the
   *     ReservedList from the cache or Datastore.
   */
  public static Optional<ReservedList> get(String listName) {
    return getFromCache(listName, cache);
  }

  /**
   * Gets a ReservedList that has not been locally cached by name.
   *
   * @return An Optional<ReservedList> that has a value if a reserved list exists by the given name,
   *     or absent if not.
   */
  public static Optional<ReservedList> getUncached(String listName) {
    return Optional.fromNullable(ofy()
               .load()
               .type(ReservedList.class)
               .parent(getCrossTldKey())
               .id(listName)
               .now());
  }

  /** Loads a ReservedList from its Objectify key. */
  public static Optional<ReservedList> load(Key<ReservedList> key) {
    return get(key.getName());
  }

  /**
   * Queries the set of all reserved lists associated with the specified tld and returns the
   * reservation type of the first occurrence of label seen. If the label is in none of the lists,
   * it returns UNRESERVED.
   */
  public static ReservationType getReservation(String label, String tld) {
    checkNotNull(label, "label");
    if (label.length() == 0) {
      return FULLY_BLOCKED;
    }
    ReservedListEntry entry = getReservedListEntry(label, tld);
    return (entry != null) ? entry.reservationType : UNRESERVED;
  }

  /**
   * Returns true if the given label and TLD is reserved for an anchor tenant, and the given auth
   * code matches the one set on the reservation.
   */
  public static boolean matchesAnchorTenantReservation(
      InternetDomainName domainName, String authCode) {
    ReservedListEntry entry =
        getReservedListEntry(domainName.parts().get(0), domainName.parent().toString());
    return entry != null
        && entry.reservationType == RESERVED_FOR_ANCHOR_TENANT
        && Objects.equals(entry.getAuthCode(), authCode);
  }

  /**
   * Helper function to retrieve the entry associated with this label and TLD, or null if no such
   * entry exists.
   */
  @Nullable
  private static ReservedListEntry getReservedListEntry(String label, String tld) {
    Registry registry = Registry.get(checkNotNull(tld, "tld"));
    ImmutableSet<Key<ReservedList>> reservedLists = registry.getReservedLists();
    ImmutableSet<ReservedList> lists = loadReservedLists(reservedLists);
    ReservedListEntry entry = null;

    // Loop through all reservation lists and check each one for the inputted label, and return
    // the most severe ReservationType found.
    for (ReservedList rl : lists) {
      Map<String, ReservedListEntry> entries = rl.getReservedListEntries();
      ReservedListEntry nextEntry = entries.get(label);
      if (nextEntry != null
          && (entry == null || nextEntry.reservationType.compareTo(entry.reservationType) > 0)) {
        entry = nextEntry;
      }
    }
    return entry;
  }

  private static ImmutableSet<ReservedList> loadReservedLists(
      ImmutableSet<Key<ReservedList>> reservedListKeys) {
    ImmutableSet.Builder<ReservedList> builder = new ImmutableSet.Builder<>();
    for (Key<ReservedList> listKey : reservedListKeys) {
      try {
        builder.add(cache.get(listKey.getName()));
      } catch (ExecutionException e) {
        throw new UncheckedExecutionException(
            String.format(
                "Could not load the reserved list '%s' from the cache", listKey.getName()),
            e);
      }
    }

    return builder.build();
  }

  private static LoadingCache<String, ReservedList> cache =
      CacheBuilder.newBuilder()
          .expireAfterWrite(
              RegistryEnvironment.get().config().getDomainLabelListCacheDuration().getMillis(),
              MILLISECONDS)
          .build(
              new CacheLoader<String, ReservedList>() {
                @Override
                public ReservedList load(String listName) {
                  return ofy()
                      .load()
                      .type(ReservedList.class)
                      .parent(getCrossTldKey())
                      .id(listName)
                      .now();
                }
              });

  /** Deletes the ReservedList with the given name. */
  public static void delete(final String listName) {
    final ReservedList reservedList = ReservedList.get(listName).orNull();
    checkState(
        reservedList != null, "Attempted to delete reserved list %s which doesn't exist", listName);
    ofy()
        .transactNew(
            new VoidWork() {
              @Override
              public void vrun() {
                ofy().delete().entity(reservedList).now();
              }
            });
    cache.invalidate(listName);
  }

  /**
   * Gets the {@link ReservationType} of a label in a single ReservedList, or returns an absent
   * Optional if none exists in the list.
   *
   * <p>
   *
   * <p>Note that this logic is significantly less complicated than the getReservation() methods,
   * which are applicable to an entire Registry, and need to check across multiple reserved lists.
   */
  public Optional<ReservationType> getReservationInList(String label) {
    ReservedListEntry entry = getReservedListEntries().get(label);
    return Optional.fromNullable(entry == null ? null : entry.reservationType);
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
    checkArgument(
        parts.size() == 2 || parts.size() == 3,
        "Could not parse line in reserved list: %s",
        originalLine);
    String label = parts.get(0);
    ReservationType reservationType = ReservationType.valueOf(parts.get(1));
    String authCode = (parts.size() > 2) ? parts.get(2) : null;
    return ReservedListEntry.create(label, reservationType, authCode, comment);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link ReservedList} objects, since they are immutable. */
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
