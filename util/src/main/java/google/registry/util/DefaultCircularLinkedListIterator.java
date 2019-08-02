package google.registry.util;

/**
 * Default implementation of {@link AbstractCircularLinkedListIterator} with generic type
 * {@param <T>} that operates only in closed circular loop.
 *
 * @param <T> - Type of elements in iterator.
 *
 */
public class DefaultCircularLinkedListIterator<T> extends AbstractCircularLinkedListIterator<T> {

  public static class Builder<T> extends AbstractCircularLinkedListIterator.Builder<T, Builder<T>, DefaultCircularLinkedListIterator<T>> {

    @Override
    public Builder<T> childBuilder() {
      return this;
    }

    /** On build, close loop by pointing last element to first. */
    @Override
    public DefaultCircularLinkedListIterator<T> build() {
      current.setNext(first);
      return new DefaultCircularLinkedListIterator<>(first, current);
    }
  }
  private DefaultCircularLinkedListIterator(Entry<T> first, Entry<T> last) {
    super(first, last);
  }

}
