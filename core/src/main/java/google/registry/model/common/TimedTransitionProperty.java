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

package google.registry.model.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.formatInstant;
import static google.registry.util.DateTimeUtils.latestOf;

import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.model.UnsafeSerializable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * An entity property whose value transitions over time. Each value it takes on becomes active at a
 * corresponding instant, and remains active until the next transition occurs. At least one "start
 * of time" value (corresponding to {@code START_INSTANT}, i.e. the Unix epoch) must be provided so
 * that the property will have a value for all possible times.
 */
// Implementation note: this class used to implement the Guava ForwardingMap. This breaks in
// Hibernate 6, which assumes that any class implementing Map<K, V> would also have <K, V> as its
// first two generic type parameters. If this is fixed, we can add back the ForwardingMap, which
// can simplify the code in a few places.
public class TimedTransitionProperty<V extends Serializable> implements UnsafeSerializable {

  private static final long serialVersionUID = -7274659848856323290L;

  /** The map of all the transitions that have been defined for this property. */
  private final ImmutableSortedMap<Instant, V> backingMap;

  /**
   * Returns a map of the transitions, with the keys formatted as ISO-8601 strings.
   *
   * <p>This is used for JSON/YAML serialization.
   */
  @JsonValue
  public ImmutableSortedMap<String, V> getTransitions() {
    return backingMap.entrySet().stream()
        .collect(
            toImmutableSortedMap(
                Ordering.natural(), e -> formatInstant(e.getKey()), Map.Entry::getValue));
  }

  private TimedTransitionProperty(ImmutableSortedMap<Instant, V> backingMap) {
    checkArgument(
        backingMap.containsKey(START_INSTANT),
        "Must provide transition entry for the start of time (Unix Epoch)");
    checkArgument(
        Ordering.natural().equals(backingMap.comparator()),
        "Timed transition value map must have transition time keys in chronological order");
    this.backingMap = ImmutableSortedMap.copyOfSorted(backingMap);
  }

  /** Returns an empty {@link TimedTransitionProperty}. */
  public static <V extends Serializable> TimedTransitionProperty<V> forEmptyMap() {
    return new TimedTransitionProperty<>(ImmutableSortedMap.of());
  }

  /**
   * Returns a {@link TimedTransitionProperty} that starts with the given value at {@code
   * START_INSTANT}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> withInitialValue(V value) {
    return fromValueMap(ImmutableSortedMap.of(START_INSTANT, value));
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_INSTANT}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> fromValueMap(
      ImmutableSortedMap<Instant, V> valueMap) {
    return new TimedTransitionProperty<>(valueMap);
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_INSTANT}. The map is also validated against a
   * set of allowed transitions.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> make(
      ImmutableSortedMap<Instant, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName,
      V initialValue,
      String initialValueErrorMessage) {
    validateTimedTransitionMap(valueMap, allowedTransitions, mapName);
    checkArgument(valueMap.firstEntry().getValue().equals(initialValue), initialValueErrorMessage);
    return fromValueMap(valueMap);
  }

  /** Validates a timed transition map. */
  public static <V extends Serializable> void validateTimedTransitionMap(
      ImmutableSortedMap<Instant, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName) {
    checkArgument(
        Ordering.natural().equals(valueMap.comparator()),
        "Timed transition value map must have transition time keys in chronological order");
    checkArgument(
        valueMap.firstKey().equals(START_INSTANT), "%s map must start at START_INSTANT.", mapName);

    V lastValue = null;
    for (V value : valueMap.values()) {
      if (lastValue != null && !allowedTransitions.containsEntry(lastValue, value)) {
        if (allowedTransitions.get(lastValue).isEmpty()) {
          throw new IllegalArgumentException(
              String.format("%s map cannot transition from %s.", mapName, lastValue));
        } else {
          throw new IllegalArgumentException(
              String.format("%s map cannot transition from %s to %s.", mapName, lastValue, value));
        }
      }
      lastValue = value;
    }
  }

  /** Checks whether the property is valid. */
  public void checkValidity() {
    checkState(
        backingMap.containsKey(START_INSTANT),
        "Timed transition values missing required entry for the start of time (Unix Epoch)");
  }

  /** Returns the value of the property that is active at the given time. */
  public V getValueAtTime(Instant time) {
    return backingMap.floorEntry(latestOf(START_INSTANT, time)).getValue();
  }

  /** Returns the map of all the transitions that have been defined for this property. */
  public ImmutableSortedMap<Instant, V> toValueMap() {
    return backingMap;
  }

  /** Returns the time of the next transition. Returns null if there is no subsequent transition. */
  @Nullable
  public Instant getNextTransitionAfter(Instant time) {
    return backingMap.higherKey(latestOf(START_INSTANT, time));
  }

  public int size() {
    return backingMap.size();
  }

  @Override
  public boolean equals(@CheckForNull Object object) {
    if (this == object) {
      return true;
    }
    if (object instanceof TimedTransitionProperty<?> other) {
      return this.backingMap.equals(other.backingMap);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return this.backingMap.hashCode();
  }

  @Override
  public String toString() {
    return backingMap.entrySet().stream()
        .map(e -> formatInstant(e.getKey()) + "=" + e.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }
}
