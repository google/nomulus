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
import javax.annotation.Nullable;
import org.joda.time.DateTime;

/**
 * Util class to map a foreign key to the {@link VKey} to the active instance of {@link EppResource}
 * whose unique repoId matches the foreign key string at a given time. The instance is never
 * deleted, but it is updated if a newer entity becomes the active entity.
 */
public final class ForeignKeyUtils {

  private ForeignKeyUtils() {}

  private static final ImmutableMap<Class<? extends EppResource>, String>
      RESOURCE_TYPE_TO_FK_PROPERTY =
          ImmutableMap.of(
              Contact.class, "contactId",
              Domain.class, "domainName",
              Host.class, "hostName");

  /**
   * Loads a {@link VKey} to an {@link EppResource} from the database by foreign key.
   *
   * <p>Returns null if no resource with this foreign key was ever created, or if the most recently
   * created resource was deleted before time "now".
   *
   * @param clazz the resource type to load
   * @param foreignKey foreign key to match
   * @param now the current logical time to use when checking for soft deletion of the foreign key
   *     index
   */
  @Nullable
  public static <E extends EppResource> VKey<E> load(
      Class<E> clazz, String foreignKey, DateTime now) {
    return load(clazz, ImmutableList.of(foreignKey), now).get(foreignKey);
  }

  /**
   * Load a map of {@link String} foreign keys to {@link VKey}s to {@link EppResource} that are
   * active at or after the specified moment in time.
   *
   * <p>The returned map will omit any foreign keys for which the {@link EppResource} doesn't exist
   * or has been soft-deleted.
   */
  public static <E extends EppResource> ImmutableMap<String, VKey<E>> load(
      Class<E> clazz, Collection<String> foreignKeys, final DateTime now) {
    return load(clazz, foreignKeys, false).entrySet().stream()
        .filter(e -> now.isBefore(e.getValue().deletionTime()))
        .collect(toImmutableMap(Entry::getKey, e -> VKey.create(clazz, e.getValue().repoId())));
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
  public static <E extends EppResource> ImmutableMap<String, MostRecentResource> load(
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
                        row -> MostRecentResource.create((String) row[1], (DateTime) row[2]))));
  }

  private static final CacheLoader<VKey<? extends EppResource>, Optional<MostRecentResource>>
      CACHE_LOADER =
          new CacheLoader<>() {

            @Override
            public Optional<MostRecentResource> load(VKey<? extends EppResource> key) {
              return loadAll(ImmutableSet.of(key)).get(key);
            }

            @Override
            public Map<
                    ? extends VKey<? extends EppResource>, ? extends Optional<MostRecentResource>>
                loadAll(Set<? extends VKey<? extends EppResource>> keys) {
              if (keys.isEmpty()) {
                return ImmutableMap.of();
              }
              // It is safe to use the resource type of first element because when this function is
              // called, it is always passed with a list of VKeys with the same type.
              Class<? extends EppResource> clazz = keys.iterator().next().getKind();
              ImmutableList<String> foreignKeys =
                  keys.stream().map(key -> (String) key.getKey()).collect(toImmutableList());
              ImmutableMap<String, MostRecentResource> existingKeys =
                  ForeignKeyUtils.load(clazz, foreignKeys, true);
              // The above map only contains keys that exist in the database, so we re-add the
              // missing ones with Optional.empty() values for caching.
              return Maps.asMap(
                  ImmutableSet.copyOf(keys),
                  key -> Optional.ofNullable(existingKeys.get((String) key.getKey())));
            }
          };

  /**
   * A limited size, limited time cache for foreign-keyed entities.
   *
   * <p>This is only used to cache foreign-keyed entities for the purposes of checking whether they
   * exist (and if so, what entity they point to) during a few domain flows. Any other operations on
   * foreign keys should not use this cache.
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
   */
  @NonFinalForTesting
  private static LoadingCache<VKey<? extends EppResource>, Optional<MostRecentResource>>
      foreignKeyCache = createForeignKeyMapCache(getEppResourceCachingDuration());

  private static LoadingCache<VKey<? extends EppResource>, Optional<MostRecentResource>>
      createForeignKeyMapCache(Duration expiry) {
    return CacheUtils.newCacheBuilder(expiry)
        .maximumSize(getEppResourceMaxCachedEntries())
        .build(CACHE_LOADER);
  }

  @VisibleForTesting
  public static void setCacheForTest(Optional<Duration> expiry) {
    Duration effectiveExpiry = expiry.orElse(getEppResourceCachingDuration());
    foreignKeyCache = createForeignKeyMapCache(effectiveExpiry);
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
  public static <E extends EppResource> ImmutableMap<String, VKey<E>> loadByCacheIfEnabled(
      Class<E> clazz, Collection<String> foreignKeys, final DateTime now) {
    if (!RegistryConfig.isEppResourceCachingEnabled()) {
      return load(clazz, foreignKeys, now);
    }
    return loadByCache(clazz, foreignKeys, now);
  }

  /**
   * Load a list of {@link VKey} to {@link EppResource} instances by class and foreign key strings
   * that are active at or after the specified moment in time, using the cache.
   *
   * <p>The returned map will omit any keys for which the {@link EppResource} doesn't exist or has
   * been soft-deleted.
   *
   * <p>This method is reserved for use cases that can tolerate slightly stale data.
   */
  public static <E extends EppResource> ImmutableMap<String, VKey<E>> loadByCache(
      Class<E> clazz, Collection<String> foreignKeys, final DateTime now) {
    return foreignKeyCache
        .getAll(foreignKeys.stream().map(fk -> VKey.create(clazz, fk)).collect(toImmutableList()))
        .entrySet()
        .stream()
        .filter(e -> e.getValue().isPresent() && now.isBefore(e.getValue().get().deletionTime()))
        .collect(
            toImmutableMap(
                e -> (String) e.getKey().getKey(),
                e -> VKey.create(clazz, e.getValue().get().repoId())));
  }

  public record MostRecentResource(String repoId, DateTime deletionTime) {

    static MostRecentResource create(String repoId, DateTime deletionTime) {
      return new MostRecentResource(repoId, deletionTime);
    }
  }
}
