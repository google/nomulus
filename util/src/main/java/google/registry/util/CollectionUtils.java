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

package google.registry.util;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Iterables.isEmpty;
import static com.google.common.collect.Iterables.partition;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multisets;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.stream.Collector;
import javax.annotation.Nullable;

/** Utility methods related to collections. */
public class CollectionUtils {

  /** Checks if an iterable is null or empty. */
  public static boolean isNullOrEmpty(@Nullable Iterable<?> potentiallyNull) {
    return potentiallyNull == null || isEmpty(potentiallyNull);
  }

  /** Checks if a map is null or empty. */
  public static boolean isNullOrEmpty(@Nullable Map<?, ?> potentiallyNull) {
    return potentiallyNull == null || potentiallyNull.isEmpty();
  }

  /** Turns a null set into an empty set. JAXB leaves lots of null sets lying around. */
  public static <T> Set<T> nullToEmpty(@Nullable Set<T> potentiallyNull) {
    return firstNonNull(potentiallyNull, ImmutableSet.of());
  }

  /** Turns a null list into an empty list. */
  public static <T> List<T> nullToEmpty(@Nullable List<T> potentiallyNull) {
    return firstNonNull(potentiallyNull, ImmutableList.of());
  }

  /** Turns a null map into an empty map. */
  public static <T, U> Map<T, U> nullToEmpty(@Nullable Map<T, U> potentiallyNull) {
    return firstNonNull(potentiallyNull, ImmutableMap.of());
  }

  /** Turns a null multimap into an empty multimap. */
  public static <T, U> Multimap<T, U> nullToEmpty(@Nullable Multimap<T, U> potentiallyNull) {
    return firstNonNull(potentiallyNull, ImmutableMultimap.of());
  }

  /** Turns a null sorted map into an empty sorted map.. */
  public static <T, U> SortedMap<T, U> nullToEmpty(@Nullable SortedMap<T, U> potentiallyNull) {
    return firstNonNull(potentiallyNull, ImmutableSortedMap.of());
  }

  /** Defensive copy helper for {@link Set}. */
  public static <V> ImmutableSet<V> nullSafeImmutableCopy(Set<V> data) {
    return data == null ? null : ImmutableSet.copyOf(data);
  }

  /** Defensive copy helper for {@link List}. */
  public static <V> ImmutableList<V> nullSafeImmutableCopy(List<V> data) {
    return data == null ? null : ImmutableList.copyOf(data);
  }

  /** Defensive copy helper for {@link Set}. */
  public static <V> ImmutableSet<V> nullToEmptyImmutableCopy(Set<V> data) {
    return data == null ? ImmutableSet.of() : ImmutableSet.copyOf(data);
  }

  /** Defensive copy helper for {@link Set}. */
  public static <V extends Comparable<V>>
      ImmutableSortedSet<V> nullToEmptyImmutableSortedCopy(Set<V> data) {
    return data == null ? ImmutableSortedSet.of() : ImmutableSortedSet.copyOf(data);
  }

  /** Defensive copy helper for {@link SortedMap}. */
  public static <K, V> ImmutableSortedMap<K, V> nullToEmptyImmutableCopy(SortedMap<K, V> data) {
    return data == null ? ImmutableSortedMap.of() : ImmutableSortedMap.copyOfSorted(data);
  }

  /** Defensive copy helper for {@link List}. */
  public static <V> ImmutableList<V> nullToEmptyImmutableCopy(List<V> data) {
    return data == null ? ImmutableList.of() : ImmutableList.copyOf(data);
  }

  /** Defensive copy helper for {@link Map}. */
  public static <K, V> ImmutableMap<K, V> nullToEmptyImmutableCopy(Map<K, V> data) {
    return data == null ? ImmutableMap.of() : ImmutableMap.copyOf(data);
  }

  /**
   * Turns an empty collection into a null collection.
   *
   * <p>This is unwise in the general case (nulls are bad; empties are good) but occasionally needed
   * to cause JAXB not to emit a field, or to avoid saving something to Datastore. The method name
   * includes "force" to indicate that you should think twice before using it.
   */
  @Nullable
  public static <T, C extends Collection<T>> C forceEmptyToNull(@Nullable C potentiallyEmpty) {
    return potentiallyEmpty == null || potentiallyEmpty.isEmpty() ? null : potentiallyEmpty;
  }

  /** Copy an {@link ImmutableSet} and add members. */
  @SafeVarargs
  public static <T> ImmutableSet<T> union(Set<T> set, T... newMembers) {
    return Sets.union(set, ImmutableSet.copyOf(newMembers)).immutableCopy();
  }

  /** Copy an {@link ImmutableSet} and remove members. */
  @SafeVarargs
  public static <T> ImmutableSet<T> difference(Set<T> set, T... toRemove) {
    return Sets.difference(set, ImmutableSet.copyOf(toRemove)).immutableCopy();
  }

  /** Returns any duplicates in an iterable. */
  public static <T> Set<T> findDuplicates(Iterable<T> iterable) {
    return Multisets.difference(
        HashMultiset.create(iterable),
        HashMultiset.create(ImmutableSet.copyOf(iterable))).elementSet();
  }

  /** Partitions a Map into a Collection of Maps, each of max size n. */
  public static <K, V> ImmutableList<ImmutableMap<K, V>> partitionMap(Map<K, V> map, int size) {
    ImmutableList.Builder<ImmutableMap<K, V>> shards = new ImmutableList.Builder<>();
    for (Iterable<Map.Entry<K, V>> entriesShard : partition(map.entrySet(), size)) {
      shards.add(ImmutableMap.copyOf(entriesShard));
    }
    return shards.build();
  }

  /**
   * Returns a {@link Collector} that accumulates {@link Map.Entry}s into an {@code ImmutableMap}.
   */
  public static <K, V> Collector<Map.Entry<K, V>, ?, ImmutableMap<K, V>> entriesToImmutableMap() {
    return ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue);
  }
}
