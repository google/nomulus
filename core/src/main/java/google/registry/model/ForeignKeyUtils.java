// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.config.RegistryConfig.getEppResourceCachingDuration;
import static google.registry.config.RegistryConfig.getEppResourceMaxCachedEntries;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import google.registry.config.RegistryConfig;
import google.registry.model.contact.Contact;
import google.registry.model.domain.Domain;
import google.registry.model.host.Host;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.util.NonFinalForTesting;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import org.joda.time.DateTime;

/**
 * Util class for mapping a foreign key to a {@link VKey} or {@link EppResource}.
 *
 * <p>We return the resource that matches the foreign key at a given time (this may vary over time
 * e.g. if a domain expires and is re-registered). The instance is never deleted, but it is updated
 * if a newer entity becomes the active entity.
 *
 * <p>One can retrieve either the {@link VKey} (the repo ID) in the situations where that's
 * sufficient, or the resource itself, either with or without caching.
 */
public final class ForeignKeyUtils {

  private ForeignKeyUtils() {}

  private static final ImmutableMap<Class<? extends EppResource>, String>
      RESOURCE_TYPE_TO_FK_PROPERTY =
          ImmutableMap.of(
              Contact.class, "contactId",
              Domain.class, "domainName",
              Host.class, "hostName");

  public record MostRecentResource(String repoId, DateTime deletionTime) {}

  /**
   * Loads an optional {@link VKey} to an {@link EppResource} from the database by foreign key.
   *
   * <p>Returns empty if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   */
  public static <E extends EppResource> Optional<VKey<E>> loadKey(
      Class<E> clazz, String foreignKey, DateTime now) {
    return Optional.ofNullable(loadKeys(clazz, ImmutableList.of(foreignKey), now).get(foreignKey));
  }

  /**
   * Loads an {@link EppResource} from the database by foreign key.
   *
   * <p>Returns null if no resource with this foreign key was ever created or if the most recently
   * created resource was deleted before time "now".
   */
  public static <E extends EppResource> Optional<E> loadResource(
      Class<E> clazz, String foreignKey, DateTime now) {
    // Note: no need to project to "now" because loadResources already does
    return Optional.ofNullable(
        loadResources(clazz, ImmutableList.of(foreignKey), now).get(foreignKey));
  }

  /**
   * Load a map of {@link String} foreign keys to {@link VKey}s to {@link EppResource} that are
   * active at or after the specified moment in time.
   *
   * <p>The returned map will omit any foreign keys for which the {@link EppResource} doesn't exist
   * or has been soft-deleted.
   */
  public static <E extends EppResource> ImmutableMap<String, VKey<E>> loadKeys(
      Class<E> clazz, Collection<String> foreignKeys, DateTime now) {
    return loadMostRecentResources(clazz, foreignKeys, false).entrySet().stream()
        .filter(e -> now.isBefore(e.getValue().deletionTime()))
        .collect(toImmutableMap(Entry::getKey, e -> VKey.create(clazz, e.getValue().repoId())));
  }

  /**
   * Load a map of {@link String} foreign keys to the {@link EppResource} that are active at or
   * after the specified moment in time.
   *
   * <p>The returned map will omit any foreign keys for which the {@link EppResource} doesn't exist
   * or has been soft-deleted.
   */
  @SuppressWarnings("unchecked")
  public static <E extends EppResource> ImmutableMap<String, E> loadResources(
      Class<E> clazz, Collection<String> foreignKeys, DateTime now) {
    return loadMostRecentResourceObjects(clazz, foreignKeys, false).entrySet().stream()
        .filter(e -> now.isBefore(e.getValue().getDeletionTime()))
        .collect(toImmutableMap(Entry::getKey, e -> (E) e.getValue().cloneProjectedAtTime(now)));
  }

  /**
   * Helper method to load {@link VKey}s to all the most recent {@link EppResource}s for the given
   * foreign keys, regardless of whether they have been soft-deleted.
   *
   * <p>Used by both the cached (w/o deletion check) and the non-cached (with deletion check) calls.
   *
   * <p>Note that in production, the {@code deletionTime} for entities with the same foreign key
   * should monotonically increase as one cannot create a domain/host/contact with the same foreign
   * key without soft deleting the existing resource first. However, in test, there's no such
   * guarantee and one must make sure that no two resources with the same foreign key exist with the
   * same max {@code deleteTime}, usually {@code END_OF_TIME}, lest this method throws an error due
   * to duplicate keys.
   */
  public static <E extends EppResource>
      ImmutableMap<String, MostRecentResource> loadMostRecentResources(
          Class<E> clazz, Collection<String> foreignKeys, boolean useReplicaTm) {
    String fkProperty = RESOURCE_TYPE_TO_FK_PROPERTY.get(clazz);
    JpaTransactionManager tmToUse = useReplicaTm ? replicaTm() : tm();
    return tmToUse.reTransact(
        () ->
            tmToUse
                .query(
                    ("SELECT %fkProperty%, repoId, deletionTime FROM %entity% WHERE (%fkProperty%,"
                            + " deletionTime) IN (SELECT %fkProperty%, MAX(deletionTime) FROM"
                            + " %entity% WHERE %fkProperty% IN (:fks) GROUP BY %fkProperty%)")
                        .replace("%fkProperty%", fkProperty)
                        .replace("%entity%", clazz.getSimpleName()),
                    Object[].class)
                .setParameter("fks", foreignKeys)
                .getResultStream()
                .collect(
                    toImmutableMap(
                        row -> (String) row[0],
                        row -> new MostRecentResource((String) row[1], (DateTime) row[2]))));
  }

  /** Method to load the most recent {@link EppResource}s for the given foreign keys. */
  private static <E extends EppResource> ImmutableMap<String, E> loadMostRecentResourceObjects(
      Class<E> clazz, Collection<String> foreignKeys, boolean useReplicaTm) {
    String fkProperty = RESOURCE_TYPE_TO_FK_PROPERTY.get(clazz);
    JpaTransactionManager tmToUse = useReplicaTm ? replicaTm() : tm();
    return tmToUse.reTransact(
        () ->
            tmToUse
                .query(
                    ("FROM %entity% WHERE (%fkProperty%, deletionTime) IN (SELECT %fkProperty%, "
                            + "MAX(deletionTime) FROM %entity% WHERE %fkProperty% IN (:fks) "
                            + "GROUP BY %fkProperty%)")
                        .replace("%fkProperty%", fkProperty)
                        .replace("%entity%", clazz.getSimpleName()),
                    clazz)
                .setParameter("fks", foreignKeys)
                .getResultStream()
                .collect(toImmutableMap(EppResource::getForeignKey, e -> e)));
  }

  /**
   * Cache loader for loading repo IDs and deletion times for the given foreign keys.
   *
   * <p>Note: while this is given a {@link VKey}, one cannot use that key to load directly from the
   * database. That key is basically used to signify a foreign-key + resource-type pairing.
   */
  private static final CacheLoader<VKey<? extends EppResource>, Optional<MostRecentResource>>
      REPO_ID_CACHE_LOADER =
          new CacheLoader<>() {
            @Override
            public Optional<MostRecentResource> load(VKey<? extends EppResource> key) {
              String foreignKey = (String) key.getKey();
              return Optional.ofNullable(
                  loadMostRecentResources(key.getKind(), ImmutableList.of(foreignKey), true)
                      .get(foreignKey));
            }

            @Override
            public Map<VKey<? extends EppResource>, ? extends Optional<MostRecentResource>> loadAll(
                Set<? extends VKey<? extends EppResource>> keys) {
              if (keys.isEmpty()) {
                return ImmutableMap.of();
              }
              // It is safe to use the resource type of first element because when this function is
              // called, it is always passed with a list of VKeys with the same type.
              Class<? extends EppResource> clazz = keys.iterator().next().getKind();
              ImmutableList<String> foreignKeys =
                  keys.stream().map(key -> (String) key.getKey()).collect(toImmutableList());
              ImmutableMap<String, MostRecentResource> existingKeys =
                  loadMostRecentResources(clazz, foreignKeys, true);
              // The above map only contains keys that exist in the database, so we re-add the
              // missing ones with Optional.empty() values for caching.
              return Maps.asMap(
                  ImmutableSet.copyOf(keys),
                  key -> Optional.ofNullable(existingKeys.get((String) key.getKey())));
            }
          };

  /**
   * A limited size, limited time cache for mapping foreign keys to repo IDs.
   *
   * <p>This is only used to cache foreign-keyed entity keys for the purposes of checking whether
   * they exist (and if so, what entity they point to).
   *
   * <p>Note that here the key of the {@link LoadingCache} is of type {@code VKey<? extends
   * EppResource>}, but they are not legal {@link VKey}s to {@link EppResource}s, whose keys are the
   * SQL primary keys, i.e., the {@code repoId}s. Instead, their keys are the foreign keys used to
   * query the database. We use {@link VKey} here because it is a convenient composite class that
   * contains both the resource type and the foreign key, which are needed to for the query and
   * caching.
   *
   * <p>Also note that the value type of this cache is {@link Optional} because the foreign keys in
   * question are coming from external commands, and thus don't necessarily represent entities in
   * our system that actually exist. So we cache the fact that they *don't* exist by using
   * Optional.empty(), then several layers up the EPP command will fail with an error message like
   * "The contact with given IDs (blah) don't exist."
   *
   * <p>If one wishes to load the entity itself via cache, use the {@link
   * #foreignKeyToResourceCache} instead, as that loads the entity instead. This cache is used for
   * situations where the repo ID, or the existence of the repo ID, is sufficient.
   */
  @NonFinalForTesting
  private static LoadingCache<VKey<? extends EppResource>, Optional<MostRecentResource>>
      foreignKeyToRepoIdCache = createForeignKeyToRepoIdCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<? extends EppResource>, Optional<MostRecentResource>>
      createForeignKeyToRepoIdCache(Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(REPO_ID_CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setRepoIdCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    foreignKeyToRepoIdCache = createForeignKeyToRepoIdCache(effectiveExpiry);
  }

  /**
   * Load a list of {@link VKey} to {@link EppResource} instances by class and foreign key strings
   * that are active at or after the specified moment in time, using the cache if enabled.
   *
   * <p>The returned map will omit any keys for which the {@link EppResource} doesn't exist or has
   * been soft-deleted.
   *
   * <p>Don't use the cached version of this method unless you really need it for performance
   * reasons, and are OK with the trade-offs in loss of transactional consistency.
   */
  public static <E extends EppResource> ImmutableMap<String, VKey<E>> loadKeysByCacheIfEnabled(
      Class<E> clazz, Collection<String> foreignKeys, DateTime now) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return loadKeys(clazz, foreignKeys, now);
    }
    return foreignKeyToRepoIdCache
        .getAll(foreignKeys.stream().map(fk -> VKey.create(clazz, fk)).collect(toImmutableList()))
        .entrySet()
        .stream()
        .filter(e -> e.getValue().isPresent() && now.isBefore(e.getValue().get().deletionTime()))
        .collect(
            toImmutableMap(
                e -> (String) e.getKey().getKey(),
                e -> VKey.create(clazz, e.getValue().get().repoId())));
  }

  /** Loads an optional {@link VKey} to an {@link EppResource} using the cache. */
  public static <E extends EppResource> Optional<VKey<E>> loadKeyByCache(
      Class<E> clazz, String foreignKey, DateTime now) {
    return foreignKeyToRepoIdCache
        .get(VKey.create(clazz, foreignKey))
        .filter(mrr -> now.isBefore(mrr.deletionTime()))
        .map(mrr -> VKey.create(clazz, mrr.repoId()));
  }

  /**
   * Cache loader for loading {@link EppResource}s for the given foreign keys.
   *
   * <p>Note: while this is given a {@link VKey}, one cannot use that key to load directly from the
   * database. That key is basically used to signify a foreign-key + resource-type pairing.
   */
  private static final CacheLoader<VKey<? extends EppResource>, Optional<? extends EppResource>>
      RESOURCE_CACHE_LOADER =
          new CacheLoader<>() {
            @Override
            public Optional<? extends EppResource> load(VKey<? extends EppResource> key) {
              String foreignKey = (String) key.getKey();
              return Optional.ofNullable(
                  loadMostRecentResourceObjects(key.getKind(), ImmutableList.of(foreignKey), true)
                      .get(foreignKey));
            }

            @Override
            public Map<VKey<? extends EppResource>, Optional<? extends EppResource>> loadAll(
                Set<? extends VKey<? extends EppResource>> keys) {
              if (keys.isEmpty()) {
                return ImmutableMap.of();
              }
              // It is safe to use the resource type of first element because when this function is
              // called, it is always passed with a list of VKeys with the same type.
              Class<? extends EppResource> clazz = keys.iterator().next().getKind();
              ImmutableList<String> foreignKeys =
                  keys.stream().map(key -> (String) key.getKey()).collect(toImmutableList());
              ImmutableMap<String, ? extends EppResource> existingResources =
                  loadMostRecentResourceObjects(clazz, foreignKeys, true);
              // The above map only contains resources that exist in the database, so we re-add the
              // missing ones with Optional.empty() values for caching.
              return Maps.asMap(
                  ImmutableSet.copyOf(keys),
                  key -> Optional.ofNullable(existingResources.get((String) key.getKey())));
            }
          };

  /**
   * An additional limited size, limited time cache for foreign-keyed entities.
   *
   * <p>Note that here the key of the {@link LoadingCache} is of type {@code VKey<? extends
   * EppResource>}, but they are not legal {@link VKey}s to {@link EppResource}s, whose keys are the
   * SQL primary keys, i.e., the {@code repoId}s. Instead, their keys are the foreign keys used to
   * query the database. We use {@link VKey} here because it is a convenient composite class that
   * contains both the resource type and the foreign key, which are needed to for the query and
   * caching.
   *
   * <p>Also note that the value type of this cache is {@link Optional} because the foreign keys in
   * question are coming from external commands, and thus don't necessarily represent entities in
   * our system that actually exist. So we cache the fact that they *don't* exist by using
   * Optional.empty(), then several layers up the EPP command will fail with an error message like
   * "The contact with given IDs (blah) don't exist."
   *
   * <p>This cache bypasses the foreign-key-to-repo-ID lookup and maps directly from the foreign key
   * to the entity itself (at least, at this point in time).
   */
  private static LoadingCache<VKey<? extends EppResource>, Optional<? extends EppResource>>
      foreignKeyToResourceCache = createForeignKeyToResourceCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<? extends EppResource>, Optional<? extends EppResource>>
      createForeignKeyToResourceCache(Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(RESOURCE_CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setResourceCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    foreignKeyToResourceCache = createForeignKeyToResourceCache(effectiveExpiry);
  }

  /**
   * Loads the last created version of an {@link EppResource} from the database by foreign key,
   * using a cache, if caching is enabled in config settings.
   *
   * <p>Returns null if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * <p>Loading an {@link EppResource} by itself is not sufficient to know its current state since
   * it may have various expirable conditions and status values that might implicitly change its
   * state as time progresses even if it has not been updated in the database. Rather, the resource
   * must be combined with a timestamp to view its current state. We use a global last updated
   * timestamp to guarantee monotonically increasing write times, and forward our projected time to
   * the greater of this timestamp or "now". This guarantees that we're not projecting into the
   * past.
   *
   * <p>Do not call this cached version for anything that needs transactional consistency. It should
   * only be used when it's OK if the data is potentially being out of date, e.g. RDAP.
   */
  public static <E extends EppResource> Optional<E> loadResourceByCacheIfEnabled(
      Class<E> clazz, String foreignKey, DateTime now) {
    return RegistryConfig.isEppResourceCachingEnabled()
        ? loadResourceByCache(clazz, foreignKey, now)
        : loadResource(clazz, foreignKey, now);
  }

  /**
   * Loads the last created version of an {@link EppResource} from the replica database by foreign
   * key, using a cache.
   *
   * <p>This method ignores the config setting for caching, and is reserved for use cases that can
   * tolerate slightly stale data.
   */
  @SuppressWarnings("unchecked")
  public static <E extends EppResource> Optional<E> loadResourceByCache(
      Class<E> clazz, String foreignKey, DateTime now) {
    return (Optional<E>)
        foreignKeyToResourceCache
            .get(VKey.create(clazz, foreignKey))
            .filter(e -> now.isBefore(e.getDeletionTime()))
            .map(e -> e.cloneProjectedAtTime(now));
  }
}
