// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.partition;
import static google.registry.config.RegistryConfig.getDomainLabelListCacheDuration;
import static google.registry.config.RegistryConfig.getSingletonCachePersistDuration;
import static google.registry.config.RegistryConfig.getStaticPremiumListMaxCachedEntries;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.BLOOM_FILTER_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_POSITIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_POSITIVE;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumList.PremiumListRevision;
import google.registry.persistence.VKey;
import google.registry.util.NonFinalForTesting;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * DAO for {@link PremiumList} objects stored in Datastore.
 *
 * <p>This class handles both the mapping from string to Datastore-level PremiumList objects as well
 * as the mapping from PremiumList objects to the contents of those premium lists in the Datastore
 * world. Specifically, this deals with retrieving the most recent revision for a given list and
 * retrieving (or writing/deleting) all entries associated with that particular revision. The {@link
 * PremiumList} object itself, in the Datastore world, does not store the premium pricing data.
 */
public class PremiumListDatastoreDao {

  /** The number of premium list entry entities that are created and deleted per batch. */
  private static final int TRANSACTION_BATCH_SIZE = 200;

  /**
   * In-memory cache for premium lists.
   *
   * <p>This is cached for a shorter duration because we need to periodically reload this entity to
   * check if a new revision has been published, and if so, then use that.
   *
   * <p>We also cache the absence of premium lists with a given name to avoid unnecessary pointless
   * lookups. Note that this cache is only applicable to PremiumList objects stored in Datastore.
   */
  @NonFinalForTesting
  static LoadingCache<String, Optional<PremiumList>> premiumListCache =
      createPremiumListCache(getDomainLabelListCacheDuration());

  @VisibleForTesting
  public static void setPremiumListCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getSingletonCachePersistDuration());
    premiumListCache = createPremiumListCache(effectiveExpiry);
  }

  @VisibleForTesting
  public static LoadingCache<String, Optional<PremiumList>> createPremiumListCache(
      Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(java.time.Duration.ofMillis(cachePersistDuration.getMillis()))
        .build(
            new CacheLoader<String, Optional<PremiumList>>() {
              @Override
              public Optional<PremiumList> load(final String name) {
                return tm().doTransactionless(() -> getLatestRevisionUncached(name));
              }
            });
  }

  /**
   * In-memory cache for {@link PremiumListRevision}s, used for retrieving Bloom filters quickly.
   *
   * <p>This is cached for a long duration (essentially indefinitely) because a given {@link
   * PremiumListRevision} is immutable and cannot ever be changed once created, so its cache need
   * not ever expire.
   */
  static final LoadingCache<Key<PremiumListRevision>, PremiumListRevision>
      premiumListRevisionsCache =
          CacheBuilder.newBuilder()
              .expireAfterWrite(
                  java.time.Duration.ofMillis(getSingletonCachePersistDuration().getMillis()))
              .build(
                  new CacheLoader<Key<PremiumListRevision>, PremiumListRevision>() {
                    @Override
                    public PremiumListRevision load(final Key<PremiumListRevision> revisionKey) {
                      return ofyTm().doTransactionless(() -> ofy().load().key(revisionKey).now());
                    }
                  });

  /**
   * In-memory cache for {@link PremiumListEntry}s for a given label and {@link PremiumListRevision}
   *
   * <p>Because the PremiumList itself makes up part of the PremiumListRevision's key, this is
   * specific to a given premium list. Premium list entries might not be present, as indicated by
   * the Optional wrapper, and we want to cache that as well.
   *
   * <p>This is cached for a long duration (essentially indefinitely) because a given {@link
   * PremiumListRevision} and its child {@link PremiumListEntry}s are immutable and cannot ever be
   * changed once created, so the cache need not ever expire.
   *
   * <p>A maximum size is set here on the cache because it can potentially grow too big to fit in
   * memory if there are a large number of distinct premium list entries being queried (both those
   * that exist, as well as those that might exist according to the Bloom filter, must be cached).
   * The entries judged least likely to be accessed again will be evicted first.
   */
  @NonFinalForTesting
  static LoadingCache<Key<PremiumListEntry>, Optional<PremiumListEntry>> premiumListEntriesCache =
      createPremiumListEntriesCache(getSingletonCachePersistDuration());

  @VisibleForTesting
  public static void setPremiumListEntriesCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getSingletonCachePersistDuration());
    premiumListEntriesCache = createPremiumListEntriesCache(effectiveExpiry);
  }

  @VisibleForTesting
  static LoadingCache<Key<PremiumListEntry>, Optional<PremiumListEntry>>
      createPremiumListEntriesCache(Duration cachePersistDuration) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(java.time.Duration.ofMillis(cachePersistDuration.getMillis()))
        .maximumSize(getStaticPremiumListMaxCachedEntries())
        .build(
            new CacheLoader<Key<PremiumListEntry>, Optional<PremiumListEntry>>() {
              @Override
              public Optional<PremiumListEntry> load(final Key<PremiumListEntry> entryKey) {
                return ofyTm()
                    .doTransactionless(() -> Optional.ofNullable(ofy().load().key(entryKey).now()));
              }
            });
  }

  public static Optional<PremiumList> getLatestRevision(String name) {
    return premiumListCache.getUnchecked(name);
  }

  /**
   * Returns the premium price for the specified list, label, and TLD, or absent if the label is not
   * premium.
   */
  public static Optional<Money> getPremiumPrice(String premiumListName, String label, String tld) {
    DateTime startTime = DateTime.now(UTC);
    Optional<PremiumList> maybePremumList = getLatestRevision(premiumListName);
    if (!maybePremumList.isPresent()) {
      return Optional.empty();
    }
    PremiumList premiumList = maybePremumList.get();
    // If we're dealing with a list from SQL, reload from Datastore if necessary
    if (premiumList.getRevisionKey() == null) {
      Optional<PremiumList> fromDatastore = getLatestRevision(premiumList.getName());
      if (fromDatastore.isPresent()) {
        premiumList = fromDatastore.get();
      } else {
        return Optional.empty();
      }
    }
    PremiumListRevision revision;
    try {
      revision = premiumListRevisionsCache.get(premiumList.getRevisionKey());
    } catch (InvalidCacheLoadException | ExecutionException e) {
      throw new RuntimeException(
          "Could not load premium list revision " + premiumList.getRevisionKey(), e);
    }
    checkState(
        revision.getProbablePremiumLabels() != null,
        "Probable premium labels Bloom filter is null on revision '%s'",
        premiumList.getRevisionKey());

    CheckResults checkResults = checkStatus(revision, label);
    DomainLabelMetrics.recordPremiumListCheckOutcome(
        tld,
        premiumList.getName(),
        checkResults.checkOutcome(),
        DateTime.now(UTC).getMillis() - startTime.getMillis());

    return checkResults.premiumPrice();
  }

  /**
   * Persists a new or updated PremiumList object and its descendant entities to Datastore.
   *
   * <p>The flow here is: save the new premium list entries parented on that revision entity,
   * save/update the PremiumList, and then delete the old premium list entries associated with the
   * old revision.
   *
   * <p>This is the only valid way to save these kinds of entities!
   */
  public static PremiumList save(String name, List<String> inputData) {
    PremiumList premiumList = new PremiumList.Builder().setName(name).build();
    ImmutableMap<String, PremiumListEntry> premiumListEntries = premiumList.parse(inputData);
    final Optional<PremiumList> oldPremiumList = getLatestRevisionUncached(premiumList.getName());

    // Create the new revision (with its Bloom filter) and parent the entries on it.
    final PremiumListRevision newRevision =
        PremiumListRevision.create(premiumList, premiumListEntries.keySet());
    final Key<PremiumListRevision> newRevisionKey = Key.create(newRevision);
    ImmutableSet<PremiumListEntry> parentedEntries =
        parentPremiumListEntriesOnRevision(premiumListEntries.values(), newRevisionKey);

    // Save the new child entities in a series of transactions.
    for (final List<PremiumListEntry> batch : partition(parentedEntries, TRANSACTION_BATCH_SIZE)) {
      ofyTm().transactNew(() -> ofy().save().entities(batch));
    }

    // Save the new PremiumList and revision itself.
    return ofyTm()
        .transactNew(
            () -> {
              DateTime now = ofyTm().getTransactionTime();
              // Assert that the premium list hasn't been changed since we started this process.
              Key<PremiumList> key =
                  Key.create(getCrossTldKey(), PremiumList.class, premiumList.getName());
              Optional<PremiumList> existing =
                  ofyTm().loadByKeyIfPresent(VKey.createOfy(PremiumList.class, key));
              checkOfyFieldsEqual(existing, oldPremiumList);
              PremiumList newList =
                  premiumList
                      .asBuilder()
                      .setLastUpdateTime(now)
                      .setCreationTime(
                          oldPremiumList.isPresent() ? oldPremiumList.get().creationTime : now)
                      .setRevision(newRevisionKey)
                      .build();
              ofy().save().entities(newList, newRevision);
              premiumListCache.invalidate(premiumList.getName());
              return newList;
            });
  }

  public static void delete(PremiumList premiumList) {
    ofyTm().transactNew(() -> ofy().delete().entity(premiumList));
    if (premiumList.getRevisionKey() == null) {
      return;
    }
    for (final List<Key<PremiumListEntry>> batch :
        partition(
            ofy().load().type(PremiumListEntry.class).ancestor(premiumList.revisionKey).keys(),
            TRANSACTION_BATCH_SIZE)) {
      ofyTm().transactNew(() -> ofy().delete().keys(batch));
      batch.forEach(premiumListEntriesCache::invalidate);
    }
    ofyTm().transactNew(() -> ofy().delete().key(premiumList.getRevisionKey()));
    premiumListCache.invalidate(premiumList.getName());
    premiumListRevisionsCache.invalidate(premiumList.getRevisionKey());
  }

  /** Re-parents the given {@link PremiumListEntry}s on the given {@link PremiumListRevision}. */
  @VisibleForTesting
  public static ImmutableSet<PremiumListEntry> parentPremiumListEntriesOnRevision(
      Iterable<PremiumListEntry> entries, final Key<PremiumListRevision> revisionKey) {
    return Streams.stream(entries)
        .map((PremiumListEntry entry) -> entry.asBuilder().setParent(revisionKey).build())
        .collect(toImmutableSet());
  }

  /**
   * Returns all {@link PremiumListEntry PremiumListEntries} in the given {@code premiumList}.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static Iterable<PremiumListEntry> loadPremiumListEntriesUncached(PremiumList premiumList) {
    return ofy().load().type(PremiumListEntry.class).ancestor(premiumList.revisionKey).iterable();
  }

  private static Optional<PremiumList> getLatestRevisionUncached(String name) {
    return Optional.ofNullable(
        ofy().load().key(Key.create(getCrossTldKey(), PremiumList.class, name)).now());
  }

  private static void checkOfyFieldsEqual(
      Optional<PremiumList> oneOptional, Optional<PremiumList> twoOptional) {
    if (!oneOptional.isPresent()) {
      checkState(!twoOptional.isPresent(), "Premium list concurrently deleted");
      return;
    } else {
      checkState(twoOptional.isPresent(), "Premium list concurrently deleted");
    }
    PremiumList one = oneOptional.get();
    PremiumList two = twoOptional.get();
    checkState(
        Objects.equals(one.revisionKey, two.revisionKey),
        "Premium list revision key concurrently edited");
    checkState(Objects.equals(one.name, two.name), "Premium list name concurrently edited");
    checkState(Objects.equals(one.parent, two.parent), "Premium list parent concurrently edited");
    checkState(
        Objects.equals(one.creationTime, two.creationTime),
        "Premium list creation time concurrently edited");
  }

  private static CheckResults checkStatus(PremiumListRevision premiumListRevision, String label) {
    if (!premiumListRevision.getProbablePremiumLabels().mightContain(label)) {
      return CheckResults.create(BLOOM_FILTER_NEGATIVE, Optional.empty());
    }

    Key<PremiumListEntry> entryKey =
        Key.create(Key.create(premiumListRevision), PremiumListEntry.class, label);
    try {
      // getIfPresent() returns null if the key is not in the cache
      Optional<PremiumListEntry> entry = premiumListEntriesCache.getIfPresent(entryKey);
      if (entry != null) {
        if (entry.isPresent()) {
          return CheckResults.create(CACHED_POSITIVE, Optional.of(entry.get().getValue()));
        } else {
          return CheckResults.create(CACHED_NEGATIVE, Optional.empty());
        }
      }

      entry = premiumListEntriesCache.get(entryKey);
      if (entry.isPresent()) {
        return CheckResults.create(UNCACHED_POSITIVE, Optional.of(entry.get().getValue()));
      } else {
        return CheckResults.create(UNCACHED_NEGATIVE, Optional.empty());
      }
    } catch (InvalidCacheLoadException | ExecutionException e) {
      throw new RuntimeException("Could not load premium list entry " + entryKey, e);
    }
  }

  /** Value type class used by {@link #checkStatus} to return the results of a premiumness check. */
  @AutoValue
  abstract static class CheckResults {
    static CheckResults create(PremiumListCheckOutcome checkOutcome, Optional<Money> premiumPrice) {
      return new AutoValue_PremiumListDatastoreDao_CheckResults(checkOutcome, premiumPrice);
    }

    abstract PremiumListCheckOutcome checkOutcome();

    abstract Optional<Money> premiumPrice();
  }

  private PremiumListDatastoreDao() {}
}
