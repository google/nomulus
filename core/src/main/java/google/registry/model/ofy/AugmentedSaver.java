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

import com.google.appengine.api.datastore.Entity;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.cmd.Saver;
import google.registry.model.annotations.DeleteAfterMigration;
import java.util.Arrays;
import java.util.Map;

/**
 * A Saver that forwards to {@code ofy().save()}, but can be augmented via subclassing to do custom
 * processing on the entities to be saved prior to their saving.
 */
@DeleteAfterMigration
abstract class AugmentedSaver implements Saver {
  private final Saver delegate = ofy().save();

  /** Extension method to allow this Saver to do extra work prior to the actual save. */
  protected abstract void handleSave(Iterable<?> entities);

  @Override
  public <E> Result<Map<Key<E>, E>> entities(Iterable<E> entities) {
    handleSave(entities);
    return delegate.entities(entities);
  }

  @Override
  @SafeVarargs
  public final <E> Result<Map<Key<E>, E>> entities(E... entities) {
    handleSave(Arrays.asList(entities));
    return delegate.entities(entities);
  }

  @Override
  public <E> Result<Key<E>> entity(E entity) {
    handleSave(ImmutableList.of(entity));
    return delegate.entity(entity);
  }

  @Override
  public Entity toEntity(Object pojo) {
    // No call to the extension method, since toEntity() doesn't do any actual saving.
    return delegate.toEntity(pojo);
  }
}
