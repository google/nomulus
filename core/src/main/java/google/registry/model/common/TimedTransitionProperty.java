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
import static google.registry.util.DateTimeUtils.ISO_8601_FORMATTER;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.latestOf;
import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toInstant;

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
import org.joda.time.DateTime;

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

  /**
   * Initial value for a property that transitions from this value at {@code START_INSTANT}.
   *
   * <p>Any {@code TimedTransitionProperty} must have a value at {@code START_INSTANT}.
   */
  public abstract static class TimedTransition<V extends Serializable>
      implements UnsafeSerializable {
    /** The value this property will take on at the given transition time. */
    protected V value;

    /** The time at which this value will become the active value for this property. */
    protected Instant transitionTime;

    /** Returns the value this property takes on at this transition time. */
    public V getValue() {
      return value;
    }

    /** Returns the time at which this transition occurs. */
    public Instant getTransitionTime() {
      return transitionTime;
    }
  }

  /** The map of all the transitions that have been defined for this property. */
  private ImmutableSortedMap<Instant, V> backingMap;

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
                Ordering.natural(),
                e -> ISO_8601_FORMATTER.format(e.getKey()),
                Map.Entry::getValue));
  }

  private TimedTransitionProperty(ImmutableSortedMap<Instant, V> backingMap) {
    this.backingMap = backingMap;
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
    return fromValueMapInstant(ImmutableSortedMap.of(START_INSTANT, value));
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_INSTANT}.
   *
   * @deprecated Use {@link #fromValueMapInstant(ImmutableSortedMap)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static <V extends Serializable> TimedTransitionProperty<V> fromValueMap(
      ImmutableSortedMap<DateTime, V> valueMap) {
    return fromValueMapInstant(
        valueMap.entrySet().stream()
            .collect(
                toImmutableSortedMap(
                    Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)));
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_INSTANT}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> fromValueMapInstant(
      ImmutableSortedMap<Instant, V> valueMap) {
    checkArgument(valueMap.containsKey(START_INSTANT), "Value map must contain START_INSTANT");
    return new TimedTransitionProperty<>(valueMap);
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_OF_TIME}. The map is also validated against a
   * set of allowed transitions.
   *
   * @deprecated Use {@link #makeInstant(ImmutableSortedMap, ImmutableMultimap, String,
   *     Serializable, String)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static <V extends Serializable> TimedTransitionProperty<V> make(
      ImmutableSortedMap<DateTime, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName,
      V initialValue,
      String initialValueErrorMessage) {
    return makeInstant(
        valueMap.entrySet().stream()
            .collect(
                toImmutableSortedMap(
                    Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)),
        allowedTransitions,
        mapName,
        initialValue,
        initialValueErrorMessage);
  }

  /**
   * Returns a {@link TimedTransitionProperty} that contains the transition values and times defined
   * in the given map.
   *
   * <p>The map must contain a value for {@code START_INSTANT}. The map is also validated against a
   * set of allowed transitions.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> makeInstant(
      ImmutableSortedMap<Instant, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName,
      V initialValue,
      String initialValueErrorMessage) {
    validateTimedTransitionMapInstant(
        valueMap, allowedTransitions, mapName, initialValue, initialValueErrorMessage);
    return fromValueMapInstant(valueMap);
  }

  /**
   * Validates a timed transition map.
   *
   * @deprecated Use {@link #validateTimedTransitionMapInstant(ImmutableSortedMap,
   *     ImmutableMultimap, String, Serializable, String)}
   */
  @Deprecated
  @SuppressWarnings("InlineMeSuggester")
  public static <V extends Serializable> void validateTimedTransitionMap(
      ImmutableSortedMap<DateTime, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName,
      V initialValue,
      String initialValueErrorMessage) {
    validateTimedTransitionMapInstant(
        valueMap.entrySet().stream()
            .collect(
                toImmutableSortedMap(
                    Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)),
        allowedTransitions,
        mapName,
        initialValue,
        initialValueErrorMessage);
  }

  /** Validates a timed transition map. */
  public static <V extends Serializable> void validateTimedTransitionMapInstant(
      ImmutableSortedMap<Instant, V> valueMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName,
      V initialValue,
      String initialValueErrorMessage) {
    checkArgument(valueMap.containsKey(START_INSTANT), "Value map must contain START_INSTANT");
    checkArgument(
        valueMap.get(START_INSTANT).equals(initialValue),
        "%s: %s",
        mapName,
        initialValueErrorMessage);
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
    checkState(!backingMap.isEmpty(), "Value map is empty");
    checkState(backingMap.containsKey(START_INSTANT), "Value map must contain START_INSTANT");
  }

  /** Returns the value of the property that is active at the given time. */
  public V getValueAtTime(DateTime time) {
    return getValueAtTime(toInstant(time));
  }

  /** Returns the value of the property that is active at the given time. */
  public V getValueAtTime(Instant time) {
    return backingMap.get(backingMap.floorKey(latestOf(START_INSTANT, time)));
  }

  /** Returns the map of all the transitions that have been defined for this property. */
  public ImmutableSortedMap<DateTime, V> toValueMap() {
    return backingMap.entrySet().stream()
        .collect(
            toImmutableSortedMap(
                Ordering.natural(), e -> toDateTime(e.getKey()), Map.Entry::getValue));
  }

  /** Returns the map of all the transitions that have been defined for this property. */
  public ImmutableSortedMap<Instant, V> toValueMapInstant() {
    return backingMap;
  }

  /**
   * Returns the time of the next transition after the given time. Returns null if there is no
   * subsequent transition.
   */
  @Nullable
  public DateTime getNextTransitionAfter(DateTime time) {
    Instant nextTransition = getNextTransitionAfter(toInstant(time));
    return nextTransition == null ? null : toDateTime(nextTransition);
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
        .map(e -> ISO_8601_FORMATTER.format(e.getKey()) + "=" + e.getValue())
        .collect(Collectors.joining(", ", "{", "}"));
  }
}
