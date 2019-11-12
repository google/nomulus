// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

/**
 * Class that stores value {@param <T>}, and points in circle to other {@link CircularList} objects.
 *
 * <p>In its construction, we create a sequence of {@link CircularList} objects, each storing an *
 * instance of T. They each point to each other in a circular manner, such that we can perform *
 * circular iteration on the elements. Once finished building, we return this first {@link *
 * CircularList} object in the sequence.
 *
 * @param <T> - Element type stored in the {@link CircularList}
 */
public class CircularList<T> {

  /** T instance stored in current node of list. */
  private final T value;

  /** Pointer to next node of list. */
  private CircularList<T> next;

  /** Standard constructor for {@link CircularList} that initializes its stored {@code value}. */
  protected CircularList(T value) {
    this.value = value;
  }

  /** Standard get method to retrieve {@code value}. */
  public T get() {
    return value;
  }

  /** Standard method to obtain {@code next} node in list. */
  public CircularList<T> next() {
    return next;
  }

  /** Setter method only used in builder to point one node to the next. */
  public void setNext(CircularList<T> next) {
    this.next = next;
  }

  /** Default Builder to create a standard instance of a {@link CircularList}. */
  public static class Builder<T> extends AbstractBuilder<T, CircularList<T>> {

    /** Simply calls on constructor to {@link CircularList} to create new instance. */
    @Override
    protected CircularList<T> create(T value) {
      return new CircularList<>(value);
    }
  }

  /**
   * As {@link CircularList} represents one component of the entire list, it requires a builder to
   * create the full list.
   *
   * @param <T> - Matching element type of iterator
   *     <p>Supports adding in element at a time, adding an {@link Iterable} of elements, and adding
   *     an variable number of elemetns.
   *     <p>Sets first element added to {@code first}, and when built, points last element to the
   *     {@code first} element.
   */
  public abstract static class AbstractBuilder<T, C extends CircularList<T>> {

    protected C first;
    protected C current;

    /** Necessary to instantiate each {@code C} object from {@code value}. */
    protected abstract C create(T value);

    /** Sets current {@code C} to element added and points previous {@code C} to this one. */
    public AbstractBuilder<T, C> add(T value) {
      C c = create(value);
      if (current == null) {
        first = c;
      } else {
        current.setNext(c);
      }
      current = c;
      return this;
    }

    /** Simply calls {@code addElement}, for each element in {@code elements}. */
    public AbstractBuilder<T, C> add(Iterable<T> values) {
      values.forEach(this::add);
      return this;
    }

    /** Sets the next {@code C} of the list to be the first {@code C} in the list. */
    public C build() {
      current.setNext(first);
      return first;
    }
  }
}
