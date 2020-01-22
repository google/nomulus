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

package google.registry.model.index;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Maps.filterValues;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.util.TypeUtils.instantiate;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import google.registry.config.RegistryConfig;
import google.registry.model.BackupGroupRoot;
import google.registry.model.EppResource;
import google.registry.model.annotations.ReportedOn;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.util.NonFinalForTesting;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import org.joda.time.DateTime;
import org.joda.time.Duration;

/**
 * Class to map a foreign key to the active instance of {@link EppResource} whose unique id matches
 * the foreign key string. The instance is never deleted, but it is updated if a newer entity
 * becomes the active entity.
 */
public abstract class ForeignKeyIndex<E extends EppResource> extends BackupGroupRoot {

  /** The {@link ForeignKeyIndex} type for {@link ContactResource} entities. */
  @ReportedOn
  @Entity
  public static class ForeignKeyContactIndex extends ForeignKeyIndex<ContactResource> {}

  /** The {@link ForeignKeyIndex} type for {@link DomainBase} entities. */
  @ReportedOn
  @Entity
  public static class ForeignKeyDomainIndex extends ForeignKeyIndex<DomainBase> {}

  /** The {@link ForeignKeyIndex} type for {@link HostResource} entities. */
  @ReportedOn
  @Entity
  public static class ForeignKeyHostIndex extends ForeignKeyIndex<HostResource> {}

  static final ImmutableMap<
          Class<? extends EppResource>, Class<? extends ForeignKeyIndex<?>>>
      RESOURCE_CLASS_TO_FKI_CLASS =
          ImmutableMap.of(
              ContactResource.class, ForeignKeyContactIndex.class,
              DomainBase.class, ForeignKeyDomainIndex.class,
              HostResource.class, ForeignKeyHostIndex.class);

  @Id
  String foreignKey;

  /**
   * The deletion time of this {@link ForeignKeyIndex}.
   *
   * <p>This will generally be equal to the deletion time of {@link #topReference}. However, in the
   * case of a {@link HostResource} that was renamed, this field will hold the time of the rename.
   */
  @Index
  DateTime deletionTime;

  /**
   * The referenced resource.
   *
   * <p>This field holds a key to the only referenced resource. It is named "topReference" for
   * historical reasons.
   */
  Key<E> topReference;

  public String getForeignKey() {
    return foreignKey;
  }

  public DateTime getDeletionTime() {
    return deletionTime;
  }

  public Key<E> getResourceKey() {
    return topReference;
  }

  @SuppressWarnings("unchecked")
  public static <T extends EppResource> Class<ForeignKeyIndex<T>> mapToFkiClass(
      Class<T> resourceClass) {
    return (Class<ForeignKeyIndex<T>>) RESOURCE_CLASS_TO_FKI_CLASS.get(resourceClass);
  }

  /** Create a {@link ForeignKeyIndex} instance for a resource, expiring at a specified time. */
  public static <E extends EppResource> ForeignKeyIndex<E> create(
      E resource, DateTime deletionTime) {
    @SuppressWarnings("unchecked")
    Class<E> resourceClass = (Class<E>) resource.getClass();
    ForeignKeyIndex<E> instance = instantiate(mapToFkiClass(resourceClass));
    instance.topReference = Key.create(resource);
    instance.foreignKey = resource.getForeignKey();
    instance.deletionTime = deletionTime;
    return instance;
  }

  /** Create a {@link ForeignKeyIndex} key for a resource. */
  public static <E extends EppResource> Key<ForeignKeyIndex<E>> createKey(E resource) {
    @SuppressWarnings("unchecked")
    Class<E> resourceClass = (Class<E>) resource.getClass();
    return Key.create(mapToFkiClass(resourceClass), resource.getForeignKey());
  }

  /**
   * Loads a {@link Key} to an {@link EppResource} from Datastore by foreign key.
   *
   * <p>Returns null if no foreign key index with this foreign key was ever created, or if the
   * most recently created foreign key index was deleted before time "now". This method does not
   * actually check that the referenced resource actually exists. However, for normal epp resources,
   * it is safe to assume that the referenced resource exists if the foreign key index does.
   *
   * @param clazz the resource type to load
   * @param foreignKey id to match
   * @param now the current logical time to use when checking for soft deletion of the foreign key
   *        index
   */
  @Nullable
  public static <E extends EppResource> Key<E> loadAndGetKey(
      Class<E> clazz, String foreignKey, DateTime now) {
    ForeignKeyIndex<E> index = load(clazz, foreignKey, now);
    return (index == null) ? null : index.getResourceKey();
  }

  /**
   * Load a {@link ForeignKeyIndex} by class and id string that is active at or after the specified
   * moment in time.
   *
   * <p>This will return null if the {@link ForeignKeyIndex} doesn't exist or has been soft deleted.
   */
  @Nullable
  public static <E extends EppResource> ForeignKeyIndex<E> load(
      Class<E> clazz, String foreignKey, DateTime now) {
    return load(clazz, ImmutableList.of(foreignKey), now).get(foreignKey);
  }

  /**
   * Load a list of {@link ForeignKeyIndex} instances by class and id strings that are active at or
   * after the specified moment in time.
   *
   * <p>The returned map will omit any keys for which the {@link ForeignKeyIndex} doesn't exist or
   * has been soft deleted.
   */
  public static <E extends EppResource> Map<String, ForeignKeyIndex<E>> load(
      Class<E> clazz, Iterable<String> foreignKeys, final DateTime now) {
    return filterValues(
        ofy().load().type(mapToFkiClass(clazz)).ids(foreignKeys),
        (ForeignKeyIndex<?> fki) -> now.isBefore(fki.deletionTime));
  }

  static final CacheLoader<Key<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>> CACHE_LOADER =
      new CacheLoader<Key<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>() {

        @Override
        public Optional<ForeignKeyIndex<?>> load(Key<ForeignKeyIndex<?>> key) {
          return Optional.ofNullable(tm().doTransactionless(() -> ofy().load().key(key).now()));
        }

        @Override
        public Map<Key<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>> loadAll(
            Iterable<? extends Key<ForeignKeyIndex<?>>> keys) {
          ImmutableSet<Key<ForeignKeyIndex<?>>> typedKeys = ImmutableSet.copyOf(keys);
          Map<Key<ForeignKeyIndex<?>>, ForeignKeyIndex<?>> existingFkis =
              tm().doTransactionless(() -> ofy().load().keys(typedKeys));
          // ofy() omits keys that don't have values in Datastore, so re-add them in
          // here with Optional.empty() values.
          return Maps.asMap(
              typedKeys,
              (Key<ForeignKeyIndex<?>> key) ->
                  Optional.ofNullable(existingFkis.getOrDefault(key, null)));
        }
      };

  /**
   * A limited size, limited time cache for foreign key entities.
   *
   * <p>This is only used to cache foreign key entities for the purposes of checking whether they
   * exist (and if so, what entity they point to) during a few domain flows. Any other operations on
   * foreign keys should not use this cache.
   *
   * <p>Note that the value type of this cache is Optional because the foreign keys in question are
   * coming from external commands, and thus don't necessarily represent entities in our system that
   * actually exist. So we cache the fact that they *don't* exist by using Optional.empty(), and
   * then several layers up the EPP command will fail with an error message like "The contact with
   * given IDs (blah) don't exist."
   */
  @NonFinalForTesting
  private static LoadingCache<Key<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>
      cacheForeignKeyIndexes = createForeignKeyIndexesCache(getEppResourceCachingDuration());

  private static LoadingCache<Key<ForeignKeyIndex<?>>, Optional<ForeignKeyIndex<?>>>
      createForeignKeyIndexesCache(Duration expiry) {
    return CacheBuilder.newBuilder()
        .expireAfterWrite(expiry.getMillis(), MILLISECONDS)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    cacheForeignKeyIndexes = createForeignKeyIndexesCache(effectiveExpiry);
  }

  /**
   * Load a list of {@link ForeignKeyIndex} instances by class and id strings that are active at or
   * after the specified moment in time, using the cache if enabled.
   *
   * <p>The returned map will omit any keys for which the {@link ForeignKeyIndex} doesn't exist or
   * has been soft deleted.
   *
   * <p>Don't use the cached version of this method unless you really need it for performance
   * reasons, and are OK with the trade-offs in loss of transactional consistency.
   */
  public static <E extends EppResource> Map<String, ForeignKeyIndex<E>> loadCached(
      Class<E> clazz, Iterable<String> foreignKeys, final DateTime now) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return tm().doTransactionless(() -> load(clazz, foreignKeys, now));
    }
    ImmutableList<Key<ForeignKeyIndex<?>>> fkiKeys =
        Streams.stream(foreignKeys)
            .map(fk -> Key.<ForeignKeyIndex<?>>create(mapToFkiClass(clazz), fk))
            .collect(toImmutableList());
    try {
      // This cast is safe because when we loaded ForeignKeyIndexes above we used type clazz, which
      // is scoped to E.
      @SuppressWarnings("unchecked")
      Map<String, ForeignKeyIndex<E>> fkisFromCache = cacheForeignKeyIndexes
          .getAll(fkiKeys)
          .entrySet()
          .stream()
          .filter(entry -> entry.getValue().isPresent())
          .filter(entry -> now.isBefore(entry.getValue().get().getDeletionTime()))
          .collect(
              ImmutableMap.toImmutableMap(
                  entry -> entry.getKey().getName(),
                  entry -> (ForeignKeyIndex<E>) entry.getValue().get()));
      return fkisFromCache;
    } catch (ExecutionException e) {
      throw new RuntimeException("Error loading cached ForeignKeyIndexes", e.getCause());
    }
  }
}
