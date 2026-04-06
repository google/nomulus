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
import static google.registry.util.CollectionUtils.nullToEmpty;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.latestOf;
import static google.registry.util.DateTimeUtils.toDateTime;
import static google.registry.util.DateTimeUtils.toInstant;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.model.UnsafeSerializable;
import java.io.Serializable;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
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

  private static final long serialVersionUID = -7274659848856323290L;

  /**
   * Returns a new immutable {@link TimedTransitionProperty} representing the given map of {@link
   * DateTime} to value {@link V}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> fromValueMap(
      ImmutableSortedMap<DateTime, V> valueMap) {
    checkArgument(
        Ordering.natural().equals(valueMap.comparator()),
        "Timed transition value map must have transition time keys in chronological order");
    return fromValueMapInstant(
        valueMap.entrySet().stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)));
  }

  /**
   * Returns a new immutable {@link TimedTransitionProperty} representing the given map of {@link
   * Instant} to value {@link V}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> fromValueMapInstant(
      ImmutableSortedMap<Instant, V> valueMap) {
    checkArgument(
        Ordering.natural().equals(valueMap.comparator()),
        "Timed transition value map must have transition time keys in chronological order");
    return new TimedTransitionProperty<>(valueMap);
  }

  /**
   * Returns a new immutable {@link TimedTransitionProperty} with an initial value at {@code
   * START_INSTANT}.
   */
  public static <V extends Serializable> TimedTransitionProperty<V> withInitialValue(
      V initialValue) {
    return fromValueMapInstant(ImmutableSortedMap.of(START_INSTANT, initialValue));
  }

  /**
   * Validates a new set of transitions and returns the resulting {@link TimedTransitionProperty}.
   *
   * @param newTransitions map from {@link DateTime} to transition value {@link V}
   * @param allowedTransitions optional map of all possible state-to-state transitions
   * @param allowedTransitionMapName optional transition map description string for error messages
   * @param initialValue optional initial value; if present, the first transition must have this
   *     value
   * @param badInitialValueErrorMessage option error message string if the initial value is wrong
   */
  public static <V extends Serializable> TimedTransitionProperty<V> make(
      ImmutableSortedMap<DateTime, V> newTransitions,
      ImmutableMultimap<V, V> allowedTransitions,
      String allowedTransitionMapName,
      V initialValue,
      String badInitialValueErrorMessage) {
    return makeInstant(
        newTransitions.entrySet().stream()
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)),
        allowedTransitions,
        allowedTransitionMapName,
        initialValue,
        badInitialValueErrorMessage);
  }

  /**
   * Validates a new set of transitions and returns the resulting {@link TimedTransitionProperty}.
   *
   * @param newTransitions map from {@link Instant} to transition value {@link V}
   * @param allowedTransitions optional map of all possible state-to-state transitions
   * @param allowedTransitionMapName optional transition map description string for error messages
   * @param initialValue optional initial value; if present, the first transition must have this
   *     value
   * @param badInitialValueErrorMessage option error message string if the initial value is wrong
   */
  public static <V extends Serializable> TimedTransitionProperty<V> makeInstant(
      ImmutableSortedMap<Instant, V> newTransitions,
      ImmutableMultimap<V, V> allowedTransitions,
      String allowedTransitionMapName,
      V initialValue,
      String badInitialValueErrorMessage) {
    validateTimedTransitionMapInstant(newTransitions, allowedTransitions, allowedTransitionMapName);
    checkArgument(
        newTransitions.firstEntry().getValue() == initialValue, badInitialValueErrorMessage);
    return fromValueMapInstant(newTransitions);
  }

  /**
   * Validates that a transition map is not null or empty, starts at {@code START_INSTANT}, and has
   * transitions which move from one value to another in allowed ways.
   */
  public static <V extends Serializable> void validateTimedTransitionMap(
      @Nullable NavigableMap<DateTime, V> transitionMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName) {
    validateTimedTransitionMapInstant(
        transitionMap == null
            ? null
            : transitionMap.entrySet().stream()
                .collect(
                    ImmutableSortedMap.toImmutableSortedMap(
                        Ordering.natural(), e -> toInstant(e.getKey()), Map.Entry::getValue)),
        allowedTransitions,
        mapName);
  }

  /**
   * Validates that a transition map is not null or empty, starts at {@code START_INSTANT}, and has
   * transitions which move from one value to another in allowed ways.
   */
  public static <V extends Serializable> void validateTimedTransitionMapInstant(
      @Nullable NavigableMap<Instant, V> transitionMap,
      ImmutableMultimap<V, V> allowedTransitions,
      String mapName) {
    checkArgument(
        !nullToEmpty(transitionMap).isEmpty(), "%s map cannot be null or empty.", mapName);
    checkArgument(
        transitionMap.firstKey().equals(START_INSTANT),
        "%s map must start at START_OF_TIME.",
        mapName);

    // Check that all transitions between states are allowed.
    Iterator<V> it = transitionMap.values().iterator();
    V currentState = it.next();
    while (it.hasNext()) {
      checkArgument(
          allowedTransitions.containsKey(currentState),
          "%s map cannot transition from %s.",
          mapName,
          currentState);
      V nextState = it.next();
      checkArgument(
          allowedTransitions.containsEntry(currentState, nextState),
          "%s map cannot transition from %s to %s.",
          mapName,
          currentState,
          nextState);
      currentState = nextState;
    }
  }

  /** The backing map of {@link Instant} to the value {@link V} that transitions over time. */
  private final ImmutableSortedMap<Instant, V> backingMap;

  /** Returns a new {@link TimedTransitionProperty} backed by the provided map instance. */
  private TimedTransitionProperty(NavigableMap<Instant, V> backingMap) {
    checkArgument(
        backingMap.get(START_INSTANT) != null,
        "Must provide transition entry for the start of time (Unix Epoch)");
    this.backingMap = ImmutableSortedMap.copyOfSorted(backingMap);
  }

  /**
   * Checks whether this {@link TimedTransitionProperty} is in a valid state, i.e. whether it has a
   * transition entry for {@code START_INSTANT}, and throws {@link IllegalStateException} if not.
   */
  public void checkValidity() {
    checkState(
        backingMap.get(START_INSTANT) != null,
        "Timed transition values missing required entry for the start of time (Unix Epoch)");
  }

  /** Exposes the underlying {@link ImmutableSortedMap}. */
  public ImmutableSortedMap<DateTime, V> toValueMap() {
    return backingMap.entrySet().stream()
        .collect(
            ImmutableSortedMap.toImmutableSortedMap(
                Ordering.natural(), e -> toDateTime(e.getKey()), Map.Entry::getValue));
  }

  /** Exposes the underlying {@link ImmutableSortedMap}. */
  public ImmutableSortedMap<Instant, V> toValueMapInstant() {
    return backingMap;
  }

  /**
   * Returns the value of the property that is active at the specified time. The active value for a
   * time before {@code START_INSTANT} is extrapolated to be the value that is active at {@code
   * START_INSTANT}.
   */
  public V getValueAtTime(DateTime time) {
    return getValueAtTime(toInstant(time));
  }

  /**
   * Returns the value of the property that is active at the specified time. The active value for a
   * time before {@code START_INSTANT} is extrapolated to be the value that is active at {@code
   * START_INSTANT}.
   */
  public V getValueAtTime(Instant time) {
    // Retrieve the current value by finding the latest transition before or at the given time,
    // where any given time earlier than START_INSTANT is replaced by START_INSTANT.
    return backingMap.floorEntry(latestOf(START_INSTANT, time)).getValue();
  }

  /** Returns the time of the next transition. Returns null if there is no subsequent transition. */
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
    return this.backingMap.toString();
  }
}
