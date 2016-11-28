// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Iterables.partition;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.ofy.Ofy.RECOMMENDED_MEMCACHE_EXPIRATION;
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.UncheckedExecutionException;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.VoidWork;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.annotation.Cache;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Ignore;
import com.googlecode.objectify.annotation.Mapify;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.cmd.Query;

import google.registry.config.RegistryEnvironment;
import google.registry.model.Buildable;
import google.registry.model.common.TimedTransitionProperty;
import google.registry.model.pricing.PricingCategory;
import google.registry.util.DateTimeUtils;

import org.joda.money.Money;
import org.joda.time.DateTime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

@Entity
@Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
public class CategorizedPremiumList
    extends BasePremiumList<String, CategorizedPremiumList.CategorizedListEntry> {

  @Ignore private boolean entriesWereUpdated;

  private static LoadingCache<String, CategorizedPremiumList> cache = CacheBuilder
      .newBuilder()
      .expireAfterWrite(
          RegistryEnvironment.get().config().getDomainLabelListCacheDuration().getMillis(),
          MILLISECONDS)
      .build(new CacheLoader<String, CategorizedPremiumList>() {
        @Override
        public CategorizedPremiumList load(final String listName) throws Exception {
          return ofy().doTransactionless(new Work<CategorizedPremiumList>() {
            @Override
            public CategorizedPremiumList run() {
              return ofy()
                  .load()
                  .type(CategorizedPremiumList.class)
                  .parent(getCrossTldKey())
                  .id(listName)
                  .now();
            }});
        }});

  public static Optional<CategorizedPremiumList> get(String name) {
    try {
      return Optional.of(cache.get(name));
    } catch (CacheLoader.InvalidCacheLoadException e) {
      return Optional.absent();
    } catch (ExecutionException e) {
      throw new UncheckedExecutionException("Could not retrieve premium list named " + name, e);
    }
  }

  /** Returns a CategorizedPremiumList that has not been locally cached. */
  public static Optional<CategorizedPremiumList> getUncached(final String name) {
    return Optional.fromNullable(ofy().doTransactionless(new Work<CategorizedPremiumList>() {
      @Override
      public CategorizedPremiumList run() {
        return ofy()
                   .load()
                   .type(CategorizedPremiumList.class)
                   .parent(getCrossTldKey())
                   .id(name)
                   .now();
      }}));
  }

  /**
   * A categorized list entry entity, persisted to Datastore. Each instance represents the price
   * category of a single label on a TLD at a given time.
   */
  @Entity
  @Cache(expirationSeconds = RECOMMENDED_MEMCACHE_EXPIRATION)
  public static class CategorizedListEntry extends DomainLabelEntry<String, CategorizedListEntry>
      implements Buildable {

    @Embed
    public static class PricingCategoryTransition
        extends TimedTransitionProperty.TimedTransition<String> {
      private String pricingCategory;

      @Override
      protected String getValue() {
        final Optional<PricingCategory> pc = PricingCategory.get(pricingCategory);
        checkArgument(pc.isPresent(), "Unable to find pricing category [" + pricingCategory + "]");
        return pc.get().getName();
      }

      @Override
      protected void setValue(String value) {
        this.pricingCategory = value;
      }
    }

    @Parent Key<BasePremiumList.PremiumListRevision> parent;

    @Mapify(TimedTransitionProperty.TimeMapper.class)
    TimedTransitionProperty<String, PricingCategoryTransition> categoryTransitions =
        TimedTransitionProperty.forMapify(
            PricingCategory.UNINITIALIZED, PricingCategoryTransition.class);

    /**
     * Returns date transitions over a time period such as (AA -> AA+)
     * @return
     */
    public ImmutableSortedMap<DateTime, String> getCategoryTransitions() {
      return categoryTransitions.toValueMap();
    }

    @Override
    public Builder asBuilder() {
      return new Builder(clone(this));
    }

    @Override
    public String getValue() {
      String name = categoryTransitions.getValueAtTime(DateTime.now());
      return name;
    }

    @Nullable
    public DateTime getNextTransitionDateTime() {
      return categoryTransitions.getNextTransitionAfter(DateTime.now());
    }

    public PricingCategory getValueAtTime(DateTime time) {
      String pc = categoryTransitions.getValueAtTime(time);
      return PricingCategory.get(pc).get();
    }

    /**
     * Method creates and returns a CategorizedListEntry object for given SLD and PriceCategory
     * @param sld second level domain
     * @param priceCategory PriceCategory
     * @return a CategorizedListEntry
     */
    static CategorizedListEntry createEntry(String sld, String priceCategory) {
      return new CategorizedListEntry.Builder()
          .setLabel(sld)
          .setPricingCategoryTransitions(
              ImmutableSortedMap.of(
                  START_OF_TIME,
                  priceCategory))
          .build();
    }

    public static class Builder extends DomainLabelEntry.Builder<CategorizedListEntry, Builder> {
      public Builder() {}

      private Builder(CategorizedListEntry instance) {
        super(instance);
      }

      public Builder setParent(Key<BasePremiumList.PremiumListRevision> parentKey) {
        getInstance().parent = parentKey;
        return this;
      }

      /**
       * Method sets the Pricing Category Transitions
       */
      public Builder setPricingCategoryTransitions(
          ImmutableSortedMap<DateTime, String> pricingCategoryTransitions) {
        getInstance().categoryTransitions =
            TimedTransitionProperty.fromValueMap(
                pricingCategoryTransitions, PricingCategoryTransition.class);
        return this;
      }
    }
  }

  @Nullable
  @Override
  CategorizedListEntry createFromLine(String line) {
    String[] words = line.split(",");
    checkArgument(words.length > 2, "Missing pricing category argument");
    String label = words[1].trim();
    String categoryName = words[2].trim();
    Optional<PricingCategory> pricingCategory = PricingCategory.get(categoryName);
    checkArgument(
        pricingCategory.isPresent(),
        String.format("The pricing category '%s' doesn't exist", categoryName));
    return new CategorizedListEntry.Builder()
        .setLabel(label)
        .setPricingCategoryTransitions(
            ImmutableSortedMap.of(DateTimeUtils.START_OF_TIME, pricingCategory.get().getName()))
        .build();
  }

  /**
   * Persists a PremiumList object to Datastore.
   *
   * <p>The flow here is: save the new premium list entries parented on that revision entity,
   * save/update the PremiumList, and then delete the old premium list entries associated with the
   * old revision.
   */
  public CategorizedPremiumList saveAndUpdateEntries() {
    final Optional<CategorizedPremiumList> oldPremiumList = getUncached(name);

    // Only update entries if there's actually changes to the entries
    boolean entriesToUpdate = !oldPremiumList.isPresent() || entriesWereUpdated;

    // Delete existing child entries before proceeding
    if (entriesToUpdate && oldPremiumList.isPresent()) {
      oldPremiumList.get().deleteEntries();
    }

    if (entriesToUpdate) {
      saveCategorizedListEntry();
    }

    final CategorizedPremiumList updated = saveNewPremiumList();

    CategorizedPremiumList.cache.put(name, updated);

    return updated;
  }


  @VisibleForTesting
  CategorizedPremiumList saveNewPremiumList() {
    final Optional<CategorizedPremiumList> oldPremiumList = getUncached(name);

    return ofy().transactNew(new Work<CategorizedPremiumList>() {
        @Override
        public CategorizedPremiumList run() {
          final DateTime now = ofy().getTransactionTime();
          final CategorizedPremiumList newList = CategorizedPremiumList.this.asBuilder()
              .setLastUpdateTime(now)
              .setCreationTime(
                  oldPremiumList.isPresent() ? oldPremiumList.get().creationTime : now)
              .build();

          saveAll(getPricingCategories(newList));

          ofy().save().entity(newList);
          return newList;
        }});
  }

  @VisibleForTesting
  void saveCategorizedListEntry() {
    for (final List<CategorizedListEntry> batch
        : partition(premiumListMap.values(), TRANSACTION_BATCH_SIZE)) {
      ofy().transactNew(new VoidWork() {
        @Override
        public void vrun() {
          ofy().save().entities(batch).now();
        }});
    }
  }

  @VisibleForTesting
  void saveAll(List<PricingCategory> pricingCategories) {
    for(PricingCategory pricingCategory : pricingCategories) {
      ofy().save().entity(
          pricingCategory.asBuilder()
              .activate()
              .build())
          .now();
    }
  }

  /**
   * Method retrieves the unique list of PriceCategory objects extracted from the
   * CategorizedPremiumList.CategorizedListEntry objects themselves
   * @param categorizedPremiumList a CategorizedPremiumList
   */
  @VisibleForTesting
  static List<PricingCategory> getPricingCategories(CategorizedPremiumList categorizedPremiumList) {
    return FluentIterable.from(
        categorizedPremiumList.getPremiumListEntries().values())
        .transform(new Function<CategorizedListEntry, List<String>>() {
          @Nullable
          @Override
          public List<String> apply(@Nullable CategorizedListEntry entry) {
            return (List<String>) entry.getCategoryTransitions().values();
          }
        })
        .transformAndConcat(new Function<List<String>, Iterable<String>>() {
          @Nullable
          @Override
          public Iterable<String> apply(@Nullable List<String> pricingCategoryNames) {
            return pricingCategoryNames;
          }
        }).transform(new Function<String, PricingCategory>() {
          @Nullable
          @Override
          public PricingCategory apply(@Nullable String pricingCategoryName) {
            return PricingCategory.get(pricingCategoryName).orNull();
          }
        }).toSet().asList(); // .toSet eliminates duplicate entries
  }


  @Override
  public Optional<Money> getPremiumPrice(String label) {
    if (!premiumListMap.containsKey(label)) {
      return Optional.absent();
    }
    Optional<PricingCategory> pc = PricingCategory.get(premiumListMap.get(label).getValue());
    if (!pc.isPresent()) {
      return Optional.absent();
    }
    return Optional.of(pc.get().getPrice());
  }

  @Override
  protected LoadingCache<String, ?> getCache() {
    return cache;
  }

  @Override
  protected Query<CategorizedListEntry> loadEntriesForCurrentRevision() {
    return ofy().load().type(CategorizedListEntry.class).ancestor(revisionKey);
  }

  @Override
  public Builder asBuilder() {
    return new Builder(clone(this));
  }

  public static class Builder extends BaseDomainLabelList.Builder<CategorizedPremiumList, Builder> {

    public Builder() {}

    private Builder(CategorizedPremiumList instance) {
      super(instance);
    }

    /**
     * Method assigns the ImmutableMap of CategorizedListEntry objects into the PremiumListMap
     * for this given instance of CategorizedPremiumList
     * @param premiumListMap an ImmutableMap of CategorizedListEntry objects to be assigned
     * @return a Builder object
     */
    public Builder setPremiumListMap(ImmutableMap<String, CategorizedListEntry> premiumListMap) {
      getInstance().entriesWereUpdated = true;
      getInstance().premiumListMap = premiumListMap;
      return this;
    }

    public Builder setPremiumListFromLines(Iterable<String> lines) {
      return setPremiumListMap(getInstance().parse(lines));
    }

    /**
     * Method accepts a second-level domain name and a price category and is to create a
     * CategorizedListEntry object and then add it to the CategorizedPremiumList map
     * @param sld a second-level domain
     * @param priceCategory a price category
     * @return
     */
    public Builder addEntry(final String sld, final String priceCategory) {
      return addEntry(CategorizedListEntry.createEntry(sld, priceCategory));
    }

    /**
     * Method accepts a CategorizedListEntry and determines if it has a list of PremiumListEntries
     * and if it does then it retrieves then otherwise it creates a new ImmutableMap to store
     * @param entry a CategorizedListEntry to load into the PremiumListMap
     * @return a Builder object
     */
    public Builder addEntry(final CategorizedListEntry entry) {
      // Determines if we have a list of PremiumListEntries or not and if
      // not creates an ImmutableMap
      final Map<String, CategorizedListEntry> existingEntries =
          (getInstance().getPremiumListEntries() != null)
              ? getInstance().getPremiumListEntries()
              : ImmutableMap.<String, CategorizedListEntry>of();

      checkState(!existingEntries.containsKey(entry.getLabel()),
          "Entry [%s] already exists", entry.getLabel());

      final ImmutableMap<String, CategorizedListEntry> newEntries =
          ImmutableMap.<String, CategorizedListEntry>builder()
              .putAll(existingEntries) // Adds entries regardless if it has entries or is empty
              .put(entry.getLabel(), entry)
              .build();

      return setPremiumListMap(newEntries);
    }

    /**
     * Method deletes an CategorizedListEntry from the PremiumListMap based upon the
     * second level domain (sld)
     * @param sld a string value representing a second level domain
     * @return
     */
    public Builder deleteEntry(final String sld) {

      final Map<String, CategorizedListEntry> existingEntries =
          getInstance().getPremiumListEntries();

      checkState(existingEntries.containsKey(sld), "Unable to find entry [%s]", sld);

      // Remove entry from existing PremiumListMap
      Map<String, CategorizedListEntry> tmpMap = new HashMap<>(existingEntries);
      tmpMap.remove(sld);

      final ImmutableMap<String, CategorizedListEntry> newEntries =
          ImmutableMap.<String, CategorizedListEntry>builder()
              .putAll(tmpMap)
              .build();

      return setPremiumListMap(ImmutableMap.copyOf(newEntries));
    }

    /**
     * Method updates a CategorizedListEntry from the PremiumListMap based upon the second
     * level domain
     * @param sld a string value representing a second level domain
     * @return a Builder object
     */
    public Builder updateEntry(final String sld,
                               final String futureCategory,
                               final DateTime effectiveDate) {

      // Determines if we have a list of PremiumListEntries or not and if
      // not creates an ImmutableMap
      final Map<String, CategorizedListEntry> existingEntries =
          (getInstance().getPremiumListEntries() != null)
              ? getInstance().getPremiumListEntries()
              : ImmutableMap.<String, CategorizedListEntry>of();


      checkState(existingEntries.containsKey(sld), "Unable to find entry for [%s]", sld);

      // Retrieve CategorizedListEntry objects within PremiumList map
      final CategorizedListEntry oldEntry = existingEntries.get(sld);

      CategorizedListEntry updatedEntry;

      // Determine if we only have a transition of START_OF_TIME and add new
      // effective date as a new CategoryTransition
      if (oldEntry.getNextTransitionDateTime() == null) {
        updatedEntry = oldEntry.asBuilder()
            .setPricingCategoryTransitions(ImmutableSortedMap.of(
                START_OF_TIME, oldEntry.getValueAtTime(START_OF_TIME).getName(),
                effectiveDate, futureCategory)).build();
      } else {
        // Retrieve existing category transitions
        ImmutableSortedMap<DateTime, String> existingCategoryTransition =
            oldEntry.categoryTransitions.toValueMap();

        // Create a new map in order to inject new CategoryTransitions
        final Map<DateTime, String> newCategoryTransition =
            new HashMap<>(existingCategoryTransition);

        // Returns the DateTime which needs to be removed
        final DateTime keyToRemove = existingCategoryTransition.lastKey();

        // Remove transition that will be replaced
        newCategoryTransition.remove(keyToRemove);
        // Add in new transition
        newCategoryTransition.put(effectiveDate, futureCategory);

        // Add the new map of Category Transitions into a new CategorizedListEntry object
        updatedEntry = oldEntry.asBuilder().setPricingCategoryTransitions(
            ImmutableSortedMap.copyOf(newCategoryTransition)
        ).build();
      }

      // Remove the existing CategorizedListEntry and replace with the updated entry
      final Map<String, CategorizedListEntry> newEntries = new HashMap<>();
      newEntries.putAll(existingEntries); // copy over old entries
      newEntries.remove(oldEntry); // remove old one
      newEntries.put(updatedEntry.getLabel(), updatedEntry); // add new one

      // Returns the PremiumList based upon new entries
      return setPremiumListMap(ImmutableMap.copyOf(newEntries));
    }

    @Override
    public CategorizedPremiumList build() {
      final CategorizedPremiumList instance = getInstance();

      // Only create a new revision if this is a new instance
      if (getInstance().revisionKey == null) {
        getInstance().revisionKey = PremiumListRevision.createKey(instance);
      }

      instance.premiumListMap =
          Maps.transformValues(
              nullToEmpty(instance.premiumListMap),
              new Function<CategorizedListEntry, CategorizedListEntry>() {
                @Override
                public CategorizedListEntry apply(CategorizedListEntry entry) {
                  return entry.asBuilder().setParent(instance.revisionKey).build();
                }
              });
      return super.build();
    }
  }
}
