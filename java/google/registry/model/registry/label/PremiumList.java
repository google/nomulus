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

import static com.google.appengine.api.datastore.DatastoreServiceFactory.getDatastoreService;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.partition;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.appengine.api.datastore.EntityNotFoundException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.cmd.Query;
import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.registry.Registry;
import org.joda.money.Money;
import org.joda.time.DateTime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * A premium list entity, persisted to Datastore, that is used to check domain label prices.
 */
@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class PremiumList extends BasePremiumList<Money, PremiumList.PremiumListEntry> {

  /** Stores the revision key for the set of currently used premium list entry entities. */
  private static LoadingCache<String, PremiumList> cache = CacheBuilder
      .newBuilder()
      .expireAfterWrite(
          RegistryEnvironment.get().config().getDomainLabelListCacheDuration().getMillis(),
          MILLISECONDS)
      .build(new CacheLoader<String, PremiumList>() {
        @Override
        public PremiumList load(final String listName) {
          return ofy().doTransactionless(new Work<PremiumList>() {
            @Override
            public PremiumList run() {
              return ofy().load()
                  .type(PremiumList.class)
                  .parent(getCrossTldKey())
                  .id(listName)
                  .now();
            }});
        }});

  /**
   * Gets the premium price for the specified label on the specified tld, or returns Optional.absent
   * if there is no premium price.
   */
  public static Optional<Money> getPremiumPrice(String label, String tld) {
    Registry registry = Registry.get(checkNotNull(tld, "tld"));
    if (registry.getPremiumList() == null) {
      return Optional.<Money> absent();
    }
    String listName = registry.getPremiumList().getName();
    Optional<PremiumList> premiumList = get(listName);
    if (!premiumList.isPresent()) {
      throw new IllegalStateException("Could not load premium list named " + listName);
    }
    return premiumList.get().getPremiumPrice(label);
  }

  /** Returns the PremiumList with the specified name. */
  public static Optional<PremiumList> get(String name) {
    try {
      return Optional.of(cache.get(name));
    } catch (InvalidCacheLoadException e) {
      return Optional.<PremiumList> absent();
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException("Could not retrieve premium list named " + name, e);
    }
  }

  /**
   * Returns whether a PremiumList of the given name exists, without going through the overhead
   * of loading up all of the premium list entities. Also does not hit the cache.
   */
  public static boolean exists(String name) {
    try {
      // Use DatastoreService to bypass the @OnLoad method that loads the premium list entries.
      getDatastoreService().get(Key.create(getCrossTldKey(), PremiumList.class, name).getRaw());
      return true;
    } catch (EntityNotFoundException e) {
      return false;
    }
  }

  /**
   * A premium list entry entity, persisted to Datastore.  Each instance represents the price of a
   * single label on a given TLD.
   */
  @Entity
  @Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
  public static class PremiumListEntry extends DomainLabelEntry<Money, PremiumListEntry>
      implements Buildable {

    @Parent
    Key<PremiumListRevision> parent;

    Money price;

    @Override
    public Money getValue() {
      return price;
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    /**
     * A builder for constructing {@link PremiumList} objects, since they are immutable.
     */
    public static class Builder extends DomainLabelEntry.Builder<PremiumListEntry, Builder> {
      public Builder() {}

      private Builder(PremiumListEntry instance) {
        super(instance);
      }

      public Builder setParent(Key<PremiumListRevision> parentKey) {
        getInstance().parent = parentKey;
        return this;
      }

      public Builder setPrice(Money price) {
        getInstance().price = price;
        return this;
      }
    }
  }

  @Override
  @Nullable
  PremiumListEntry createFromLine(String originalLine) {
    List<String> lineAndComment = splitOnComment(originalLine);
    if (lineAndComment.isEmpty()) {
      return null;
    }
    String line = lineAndComment.get(0);
    String comment = lineAndComment.get(1);
    List<String> parts = Splitter.on(',').trimResults().splitToList(line);
    checkArgument(parts.size() == 2, "Could not parse line in premium list: %s", originalLine);
    return new PremiumListEntry.Builder()
        .setLabel(parts.get(0))
        .setPrice(Money.parse(parts.get(1)))
        .setComment(comment)
        .build();
  }

  /**
   * Persists a PremiumList object to Datastore.
   *
   * <p> The flow here is: save the new premium list entries parented on that revision entity,
   * save/update the PremiumList, and then delete the old premium list entries associated with the
   * old revision.
   */
  public PremiumList saveAndUpdateEntries() {
    final Optional<PremiumList> oldPremiumList = get(name);
    // Only update entries if there's actually a new revision of the list to save (which there will
    // be if the list content changes, vs just the description/metadata).
    boolean entriesToUpdate =
        !oldPremiumList.isPresent()
            || !Objects.equals(oldPremiumList.get().revisionKey, this.revisionKey);
    // If needed, save the new child entities in a series of transactions.
    if (entriesToUpdate) {
      for (final List<PremiumListEntry> batch
          : partition(premiumListMap.values(), TRANSACTION_BATCH_SIZE)) {
        ofy().transactNew(new VoidWork() {
          @Override
          public void vrun() {
            ofy().save().entities(batch);
          }});
      }
    }
    // Save the new PremiumList itself.
    PremiumList updated = ofy().transactNew(new Work<PremiumList>() {
        @Override
        public PremiumList run() {
          DateTime now = ofy().getTransactionTime();
          // Assert that the premium list hasn't been changed since we started this process.
          checkState(
              Objects.equals(
                  ofy().load().type(PremiumList.class).parent(getCrossTldKey()).id(name).now(),
                  oldPremiumList.orNull()),
              "PremiumList was concurrently edited");
          PremiumList newList = PremiumList.this.asBuilder()
              .setLastUpdateTime(now)
              .setCreationTime(
                  oldPremiumList.isPresent() ? oldPremiumList.get().creationTime : now)
              .build();
          ofy().save().entity(newList);
          return newList;
        }});
    // Update the cache.
    PremiumList.cache.put(name, updated);
    // If needed and there are any, delete the entities under the old PremiumList.
    if (entriesToUpdate && oldPremiumList.isPresent()) {
      oldPremiumList.get().deleteEntries();
    }
    return updated;
  }

  @Override
  public Optional<Money> getPremiumPrice(String label) {
    return Optional.fromNullable(
        premiumListMap.containsKey(label) ? premiumListMap.get(label).getValue() : null);
  }

  @Override
  protected LoadingCache<String, PremiumList> getCache() {
    return PremiumList.cache;
  }

  @Override
  protected Query<PremiumListEntry> loadEntriesForCurrentRevision() {
    return ofy().load().type(PremiumListEntry.class).ancestor(revisionKey);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  /** A builder for constructing {@link PremiumList} objects, since they are immutable.  */
  public static class Builder extends BaseDomainLabelList.Builder<PremiumList, Builder> {

    public Builder() {}

    private Builder(PremiumList instance) {
      super(instance);
    }

    private boolean entriesWereUpdated;

    public Builder setPremiumListMap(ImmutableMap<String, PremiumListEntry> premiumListMap) {
      entriesWereUpdated = true;
      getInstance().premiumListMap = premiumListMap;
      return this;
    }

    /** Updates the premiumListMap from input lines. */
    public Builder setPremiumListMapFromLines(Iterable<String> lines) {
      return setPremiumListMap(getInstance().parse(lines));
    }

    @Override
    public PremiumList build() {
      final PremiumList instance = getInstance();
      if (getInstance().revisionKey == null || entriesWereUpdated) {
        getInstance().revisionKey = PremiumListRevision.createKey(instance);
      }
      // When we build an instance, make sure all entries are parented on its revisionKey.
      instance.premiumListMap = Maps.transformValues(
          nullToEmpty(instance.premiumListMap),
          new Function<PremiumListEntry, PremiumListEntry>() {
            @Override
            public PremiumListEntry apply(PremiumListEntry entry) {
              return entry.asBuilder().setParent(instance.revisionKey).build();
            }});
      return super.build();
    }
  }
}
