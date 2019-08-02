package google.registry.util;

import java.util.Iterator;

/**
 * Custom class that support iteration through a circular linked list
 *
 * @param <T> - Element type stored in the iterator
 *
 * <p>Is an immutable object that, when built, creates a circular pointing
 * group of Entries, that allows for looped iteration. The first and last
 * element in the closed loop are also stored.</p>
 */
public class CircularLinkedListIterator<T> implements Iterator<T> {

  /** First {@link Entry} in the circular iterator. */
  private final Entry<T> first;

  /** Last {@link Entry} in the circular iterator. */
  private final Entry<T> last;

  /** Current {@link Entry} in iteration. */
  private Entry<T> current;

  /** Because this is a circular linked list iterator, there is always a next element. */
  @Override
  public boolean hasNext() {
    return true;
  }

  /** Obtains the next element by calling on the {@link Entry}'s next element. */
  @Override
  public T next() {
    if (current == null)
      //If this is first call of next, return first element
      current = first;
    else
      //Otherwise return element after current element
      current = current.next;

    return current.data;
  }

  /** Returns stored first element. */
  public T getFirst() {
    return first.data;
  }

  /** Returns stored last element. */
  public T getLast() {
    return last.data;
  }

  /**
   * Node class for {@link CircularLinkedListIterator} that stores value of element
   * and points to next {@link Entry}.
   *
   * @param <T> - Matching element type of iterator.
   */
  private static class Entry<T> {
    /** Stores the T instance. */
    T data;

    /** Saves reference to next {@link Entry} in iterator. */
    Entry<T> next;

    /** Only needs T instance for initialization. */
    Entry(T data) {
      this.data = data;
    }
  }

  /**
   * As {@link CircularLinkedListIterator} is an immutable class, it needs
   * a builder for instantiation.
   *
   * @param <T> - Matching element type of iterator
   *
   * <p>Supports adding in element at a time, adding an {@link Iterable}
   * of elements, and adding an variable number of elemetns.</p>
   *
   * <p>Sets first element added to {@code first}, and when built, sets last added
   * element to {@code last} and points it to the {@code first} element.</p>
   */
  public static class Builder<T> {
    /** Matching first entry in the circular iterator to be built. */
    private Entry<T> first;

    /** {@link Entry} corresponding to most recent element added. */
    private Entry<T> current;

    /** Sets current {@link Entry} to element added and points previous {@link Entry} to this one. */
    public Builder<T> addElement(T element) {
      Entry<T> nextEntry = new Entry<>(element);
      if (current == null)
        //If this is first element added, we set it to first
        first = nextEntry;
      else
        //Otherwise point previous Entry to this one
        current.next = nextEntry;

      current = nextEntry;
      return this;
    }

    /** Simply calls {@code addElement}, for each element in {@code elements}. */
    public Builder<T> addElements(Iterable<T> elements) {
      elements.forEach(this::addElement);
      return this;
    }

    /** Simply calls {@code addElement}, for each element in {@code elements}. */
    public Builder<T> addElements(T... elements) {
      for (T element : elements)
        addElement(element);

      return this;
    }

    /** Points last {@link Entry} to first {@link Entry}, and calls private constructor. */
    public CircularLinkedListIterator<T> build() {
      current.next = first;
      return new CircularLinkedListIterator<>(first, current);
    }
  }

  /**
   * After having established pointers between {@link Entry}s, sets
   * first element to the first {@link Entry} added to {@link Builder}
   * and sets last element to last one added to {@link Builder}.
   */
  private CircularLinkedListIterator(Entry<T> first, Entry<T> last) {
      this.first = first;
      this.last = last;
      this.current = first;
  }
}
