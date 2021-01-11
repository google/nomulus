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

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.partition;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.BLOOM_FILTER_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_POSITIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_POSITIVE;
import static google.registry.model.registry.label.PremiumList.cachePremiumListEntries;
import static google.registry.model.registry.label.PremiumList.cachePremiumListRevisions;
import static google.registry.model.registry.label.PremiumList.cachePremiumLists;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static org.joda.time.DateTimeZone.UTC;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumList.PremiumListRevision;
import google.registry.schema.tld.PremiumListDao;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.joda.money.Money;
import org.joda.time.DateTime;

/** Static helper methods for working with {@link PremiumList}s. */
public final class PremiumListUtils {

  /** The number of premium list entry entities that are created and deleted per batch. */
  private static final int TRANSACTION_BATCH_SIZE = 200;

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Value type class used by {@link #checkStatus} to return the results of a premiumness check. */
  @AutoValue
  abstract static class CheckResults {
    static CheckResults create(PremiumListCheckOutcome checkOutcome, Optional<Money> premiumPrice) {
      return new AutoValue_PremiumListUtils_CheckResults(checkOutcome, premiumPrice);
    }

    abstract PremiumListCheckOutcome checkOutcome();
    abstract Optional<Money> premiumPrice();
  }

  /**
   * Returns the premium price for the specified label and registry, or absent if the label is not
   * premium.
   */
  public static Optional<Money> getPremiumPrice(String label, Registry registry) {
    // If the registry has no configured premium list, then no labels are premium.
    if (registry.getPremiumList() == null) {
      return Optional.empty();
    }
    DateTime startTime = DateTime.now(UTC);
    String listName = registry.getPremiumList().getName();
    Optional<PremiumList> optionalPremiumList = PremiumList.getCached(listName);
    checkState(optionalPremiumList.isPresent(), "Could not load premium list '%s'", listName);
    PremiumList premiumList = optionalPremiumList.get();
    PremiumListRevision revision;
    try {
      revision = cachePremiumListRevisions.get(premiumList.getRevisionKey());
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
        registry.getTldStr(),
        listName,
        checkResults.checkOutcome(),
        DateTime.now(UTC).getMillis() - startTime.getMillis());

    // Also load the value from Cloud SQL, compare the two results, and log if different.
    try {
      Optional<Money> priceFromSql = PremiumListDao.getPremiumPrice(label, registry);
      if (!priceFromSql.equals(checkResults.premiumPrice())) {
        logger.atWarning().log(
            "Unequal prices for domain %s.%s from Datastore (%s) and Cloud SQL (%s).",
            label, registry.getTldStr(), checkResults.premiumPrice(), priceFromSql);
      }
    } catch (Throwable t) {
      //      logger.atSevere().withCause(t).log(
      //          "Error loading price of domain %s.%s from Cloud SQL.", label,
      // registry.getTldStr());
    }
    return checkResults.premiumPrice();
  }

  private static CheckResults checkStatus(PremiumListRevision premiumListRevision, String label) {
    if (!premiumListRevision.getProbablePremiumLabels().mightContain(label)) {
      return CheckResults.create(BLOOM_FILTER_NEGATIVE, Optional.empty());
    }

    Key<PremiumListEntry> entryKey =
        Key.create(Key.create(premiumListRevision), PremiumListEntry.class, label);
    try {
      // getIfPresent() returns null if the key is not in the cache
      Optional<PremiumListEntry> entry = cachePremiumListEntries.getIfPresent(entryKey);
      if (entry != null) {
        if (entry.isPresent()) {
          return CheckResults.create(CACHED_POSITIVE, Optional.of(entry.get().getValue()));
        } else {
          return CheckResults.create(CACHED_NEGATIVE, Optional.empty());
        }
      }

      entry = cachePremiumListEntries.get(entryKey);
      if (entry.isPresent()) {
        return CheckResults.create(UNCACHED_POSITIVE, Optional.of(entry.get().getValue()));
      } else {
        return CheckResults.create(UNCACHED_NEGATIVE, Optional.empty());
      }
    } catch (InvalidCacheLoadException | ExecutionException e) {
      throw new RuntimeException("Could not load premium list entry " + entryKey, e);
    }
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
  public static PremiumList savePremiumListAndEntries(
      final PremiumList premiumList,
      ImmutableMap<String, PremiumListEntry> premiumListEntries) {
    final Optional<PremiumList> oldPremiumList = PremiumList.getUncached(premiumList.getName());

    // Create the new revision (with its Bloom filter) and parent the entries on it.
    final PremiumListRevision newRevision =
        PremiumListRevision.create(premiumList, premiumListEntries.keySet());
    final Key<PremiumListRevision> newRevisionKey = Key.create(newRevision);
    ImmutableSet<PremiumListEntry> parentedEntries =
        parentPremiumListEntriesOnRevision(premiumListEntries.values(), newRevisionKey);

    // Save the new child entities in a series of transactions.
    for (final List<PremiumListEntry> batch : partition(parentedEntries, TRANSACTION_BATCH_SIZE)) {
      tm().transactNew(() -> ofy().save().entities(batch));
    }

    // Save the new PremiumList and revision itself.
    PremiumList updated = tm().transactNew(() -> {
      DateTime now = tm().getTransactionTime();
      // Assert that the premium list hasn't been changed since we started this process.
      PremiumList existing = ofy().load()
          .type(PremiumList.class)
          .parent(getCrossTldKey())
          .id(premiumList.getName())
          .now();
      checkState(
          Objects.equals(existing, oldPremiumList.orElse(null)),
          "PremiumList was concurrently edited");
      PremiumList newList = premiumList.asBuilder()
          .setLastUpdateTime(now)
          .setCreationTime(oldPremiumList.isPresent() ? oldPremiumList.get().creationTime : now)
          .setRevision(newRevisionKey)
          .build();
      ofy().save().entities(newList, newRevision);
      return newList;
    });

    // Invalidate the cache on this premium list so the change will take effect instantly. This only
    // clears the cache on the same instance that the update was run on, which will typically be the
    // only tools instance.
    PremiumList.cachePremiumLists.invalidate(premiumList.getName());

    // TODO(b/79888775): Enqueue the oldPremiumList for deletion after at least
    // RegistryConfig.getDomainLabelListCacheDuration() has elapsed.
    return updated;
  }

  public static PremiumList savePremiumListAndEntries(
      PremiumList premiumList, Iterable<String> premiumListLines) {
    return savePremiumListAndEntries(premiumList, premiumList.parse(premiumListLines));
  }

  /** Re-parents the given {@link PremiumListEntry}s on the given {@link PremiumListRevision}. */
  @VisibleForTesting
  public static ImmutableSet<PremiumListEntry> parentPremiumListEntriesOnRevision(
      Iterable<PremiumListEntry> entries, final Key<PremiumListRevision> revisionKey) {
    return Streams.stream(entries)
        .map((PremiumListEntry entry) -> entry.asBuilder().setParent(revisionKey).build())
        .collect(toImmutableSet());
  }

  /** Deletes the PremiumList and all of its child entities. */
  public static void deletePremiumList(final PremiumList premiumList) {
    tm().transactNew(() -> ofy().delete().entity(premiumList));
    deleteRevisionAndEntriesOfPremiumList(premiumList);
    cachePremiumLists.invalidate(premiumList.getName());
  }

  static void deleteRevisionAndEntriesOfPremiumList(final PremiumList premiumList) {
    if (premiumList.getRevisionKey() == null) {
      return;
    }
    for (final List<Key<PremiumListEntry>> batch :
        partition(
            ofy().load().type(PremiumListEntry.class).ancestor(premiumList.revisionKey).keys(),
            TRANSACTION_BATCH_SIZE)) {
      tm().transactNew(() -> ofy().delete().keys(batch));
    }
    tm().transactNew(() -> ofy().delete().key(premiumList.getRevisionKey()));
  }

  /**
   * Returns all {@link PremiumListEntry PremiumListEntries} in the given {@code premiumList}.
   *
   * <p>This is an expensive operation and should only be used when the entire list is required.
   */
  public static Iterable<PremiumListEntry> loadPremiumListEntries(PremiumList premiumList) {
    return ofy().load().type(PremiumListEntry.class).ancestor(premiumList.revisionKey).iterable();
  }

  /** Returns whether a PremiumList of the given name exists, bypassing the cache. */
  public static boolean doesPremiumListExist(String name) {
    return ofy().load().key(Key.create(getCrossTldKey(), PremiumList.class, name)).now() != null;
  }

  private PremiumListUtils() {}
}
