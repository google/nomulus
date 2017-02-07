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

package google.registry.model.ofy;

import static com.googlecode.objectify.ObjectifyService.ofy;
import static google.registry.util.ObjectifyUtils.OBJECTS_TO_KEYS;

import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.cmd.DeleteType;
import com.googlecode.objectify.cmd.Deleter;
import java.util.Arrays;

/**
 * A Deleter that forwards to {@code ofy().delete()}, but can be augmented via subclassing to
 * do custom processing on the keys to be deleted prior to their deletion.
 */
abstract class AugmentedDeleter implements Deleter {
  private final Deleter delegate = ofy().delete();

  /** Extension method to allow this Deleter to do extra work prior to the actual delete. */
  protected abstract void handleDeletion(Iterable<Key<?>> keys);

  @Override
  public Result<Void> entities(Iterable<?> entities) {
    handleDeletion(Iterables.transform(entities, OBJECTS_TO_KEYS));
    return delegate.entities(entities);
  }

  @Override
  public Result<Void> entities(Object... entities) {
    handleDeletion(FluentIterable.from(entities).transform(OBJECTS_TO_KEYS));
    return delegate.entities(entities);
  }

  @Override
  public Result<Void> entity(Object entity) {
    handleDeletion(Arrays.<Key<?>>asList(Key.create(entity)));
    return delegate.entity(entity);
  }

  @Override
  public Result<Void> key(Key<?> key) {
    handleDeletion(Arrays.<Key<?>>asList(key));
    return delegate.keys(key);
  }

  @Override
  public Result<Void> keys(Iterable<? extends Key<?>> keys) {
    // Magic to convert the type Iterable<? extends Key<?>> (a family of types which allows for
    // homogeneous iterables of a fixed Key<T> type, e.g. List<Key<Lock>>, and is convenient for
    // callers) into the type Iterable<Key<?>> (a concrete type of heterogeneous keys, which is
    // convenient for users).  We do this by passing each key through the identity function
    // parameterized for Key<?>, which erases any homogeneous typing on the iterable.
    //   See: http://www.angelikalanger.com/GenericsFAQ/FAQSections/TypeArguments.html#FAQ104
    Iterable<Key<?>> retypedKeys = Iterables.transform(keys, Functions.<Key<?>>identity());
    handleDeletion(retypedKeys);
    return delegate.keys(keys);
  }

  @Override
  public Result<Void> keys(Key<?>... keys) {
    handleDeletion(Arrays.asList(keys));
    return delegate.keys(keys);
  }

  /** Augmenting this gets ugly; you can always just use keys(Key.create(...)) instead. */
  @Override
  public DeleteType type(Class<?> clazz) {
    throw new UnsupportedOperationException();
  }
}
