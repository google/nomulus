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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.googlecode.objectify.ObjectifyService.ofy;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.cmd.DeleteType;
import com.googlecode.objectify.cmd.Deleter;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * A Deleter that forwards to {@code auditedOfy().delete()}, but can be augmented via subclassing to
 * do custom processing on the keys to be deleted prior to their deletion.
 */
abstract class AugmentedDeleter implements Deleter {
  private final Deleter delegate = ofy().delete();

  /** Extension method to allow this Deleter to do extra work prior to the actual delete. */
  protected abstract void handleDeletion(Iterable<Key<?>> keys);

  private void handleDeletionStream(Stream<?> entityStream) {
    handleDeletion(entityStream.map(Key::create).collect(toImmutableList()));
  }

  @Override
  public Result<Void> entities(Iterable<?> entities) {
    handleDeletionStream(Streams.stream(entities));
    return delegate.entities(entities);
  }

  @Override
  public Result<Void> entities(Object... entities) {
    handleDeletionStream(Arrays.stream(entities));
    return delegate.entities(entities);
  }

  @Override
  public Result<Void> entity(Object entity) {
    handleDeletionStream(Stream.of(entity));
    return delegate.entity(entity);
  }

  @Override
  public Result<Void> key(Key<?> key) {
    handleDeletion(ImmutableList.of(key));
    return delegate.keys(key);
  }

  @Override
  public Result<Void> keys(Iterable<? extends Key<?>> keys) {
    // Magic to convert the type Iterable<? extends Key<?>> (a family of types which allows for
    // homogeneous iterables of a fixed Key<T> type, e.g. List<Key<Lock>>, and is convenient for
    // callers) into the type Iterable<Key<?>> (a concrete type of heterogeneous keys, which is
    // convenient for users).
    handleDeletion(ImmutableList.copyOf(keys));
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
