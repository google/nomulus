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
// limitations under the License

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
