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

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Iterator;

/**
 * Immutable class that support circular iteration through a group of elements of type {@param
 * <T>}.
 *
 * @param <T> - Element type stored in the iterator
 *
 * <p>Is an immutable object that, when built, creates a circular pointing
 * group of Entries, that allows for looped iteration. The first and last element in the closed loop
 * are also stored.</p>
 */
public abstract class AbstractCircularLinkedListIterator<T> implements Iterator<T> {

  /**
   * First {@link Entry} in the circular iterator.
   */
  private final Entry<T> first;

  /**
   * Last {@link Entry} in the circular iterator.
   */
  private final Entry<T> last;

  /**
   * Current {@link Entry} in iterator.
   */
  private Entry<T> current;

  /**
   * After having established pointers between {@link Entry}s, sets first element to the first
   * {@link Entry} added to {@link Builder} and sets last element to last one added to {@link
   * Builder}.
   */
  protected AbstractCircularLinkedListIterator(Entry<T> first, Entry<T> last) {
    checkNotNull(last.next);
    this.first = first;
    this.last = last;
  }

  /**
   * Because this is a circular linked list iterator, there is always a next element.
   */
  @Override
  public boolean hasNext() {
    return true;
  }

  /**
   * Obtains the next element by calling on the {@link Entry}'s next element.
   */
  @Override
  public T next() {
    if (current == null) {
    //If this is first call of next, return first element

      current = first;
    } else {
    //Otherwise return element after current element

      current = current.next;
    }

    return current.data;
  }

  /**
   * Get method for current element in iterator.
   */
  public T get() {
    if (current == null) {
    //if we have not started iterating, there is no current Entry, so return null
      return null;

    } else {
    //otherwise, retrieve the current Entry's data
      return current.data;
    }
  }

  /**
   * Returns stored first element.
   */
  public T getFirst() {
    return first.data;
  }

  /**
   * Returns stored last element.
   */
  public T getLast() {
    return last.data;
  }

  /**
   * Node class for {@link AbstractCircularLinkedListIterator} that stores value of element and
   * points to next {@link Entry}.
   *
   * @param <T> - Matching element type of iterator.
   */
  protected static class Entry<T> {

    /**
     * Stores the {@param <T>} instance.
     */
    T data;

    /**
     * Saves reference to next {@link Entry} in iterator.
     */
    Entry<T> next;

    /**
     * Only needs {@param <T>} instance for initialization.
     */
    Entry(T data) {
      this.data = data;
    }

    /**
     * Necessary for setting next {@link Entry} for subclasses outside of current package.
     */
    public void setNext(Entry<T> next) {
      this.next = next;
    }
  }

  /**
   * As {@link AbstractCircularLinkedListIterator} is an immutable class, it needs a builder for
   * instantiation.
   *
   * @param <T> - Matching element type of iterator
   *
   * <p>Supports adding in element at a time, adding an {@link Iterable}
   * of elements, and adding an variable number of elemetns.</p>
   *
   * <p>Sets first element added to {@code first}, and when built, sets last added
   * element to {@code last} and points it to the {@code first} element.</p>
   */
  public abstract static class Builder<T, B extends Builder<T, B, L>,
      L extends AbstractCircularLinkedListIterator> {

    /**
     * Matching first entry in the circular iterator to be built.
     */
    protected Entry<T> first;

    /**
     * {@link Entry} corresponding to most recent element added.
     */
    protected Entry<T> current;

    /**
     * Sets current {@link Entry} to element added and points previous {@link Entry} to this one.
     */
    public Builder<T, B, L> addElement(T element) {
      Entry<T> nextEntry = new Entry<>(element);
      if (current == null) {
      //If this is first element added, we set it to first
        first = nextEntry;
      } else {
      //Otherwise point previous Entry to this one
        current.next = nextEntry;
      }

      current = nextEntry;
      return this;
    }

    /**
     * Simply calls {@code addElement}, for each element in {@code elements}.
     */
    public Builder<T, B, L> addElements(Iterable<T> elements) {
      elements.forEach(this::addElement);
      return this;
    }

    /**
     * Simply calls {@code addElement}, for each element in {@code elements}.
     */
    public Builder<T, B, L> addElements(T... elements) {
      for (T element : elements) {
        addElement(element);
      }

      return this;
    }

    /**
     * Converts {@link Builder} to input subclass.
     *
     * <p>Necessary for applying custom build methods when constructing
     * subclasses that are inherent to the attributes of those subclasses.</p>
     */
    public abstract B childBuilder();

    /**
     * Points last {@link Entry} to first {@link Entry}, and calls private constructor.
     */
    public abstract L build();
  }
}
