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

package google.registry.model.ofy;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.TransactionManager;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import org.joda.time.DateTime;

/** Datastore implementation of {@link TransactionManager}. */
public class DatastoreTransactionManager implements TransactionManager {

  private Ofy injectedOfy;

  /** Constructs an instance. */
  public DatastoreTransactionManager(Ofy injectedOfy) {
    this.injectedOfy = injectedOfy;
  }

  private Ofy getOfy() {
    return injectedOfy == null ? ofy() : injectedOfy;
  }

  @Override
  public boolean inTransaction() {
    return getOfy().inTransaction();
  }

  @Override
  public void assertInTransaction() {
    getOfy().assertInTransaction();
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    return getOfy().transact(work);
  }

  @Override
  public void transact(Runnable work) {
    getOfy().transact(work);
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return getOfy().transactNew(work);
  }

  @Override
  public void transactNew(Runnable work) {
    getOfy().transactNew(work);
  }

  @Override
  public <R> R transactNewReadOnly(Supplier<R> work) {
    return getOfy().transactNewReadOnly(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    getOfy().transactNewReadOnly(work);
  }

  @Override
  public <R> R doTransactionless(Supplier<R> work) {
    return getOfy().doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return getOfy().getTransactionTime();
  }

  @Override
  public void saveNew(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    getOfy().save().entity(entity);
  }

  @Override
  public void saveAllNew(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public void saveNewOrUpdate(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    getOfy().save().entity(entity);
  }

  @Override
  public void saveNewOrUpdateAll(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    getOfy().save().entity(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    getOfy().save().entities(entities);
  }

  @Override
  public boolean checkExists(Object entity) {
    return getOfy().load().key(Key.create(entity)).now() != null;
  }

  @Override
  public <T> boolean checkExists(VKey<T> key) {
    return getOfy().load().key(key.getOfyKey()).now() != null;
  }

  // TODO: add tests for these methods.  They currently have some degree of test coverage because
  // they are used when retrieving the nameservers which require these, as they are now loaded by
  // VKey instead of by ofy Key.  But ideally, there should be one set of TransactionManager
  // interface tests that are applied to both the datastore and SQL implementations.
  @Override
  public <T> Optional<T> maybeLoad(VKey<T> key) {
    return Optional.ofNullable(getOfy().load().key(key.getOfyKey()).now());
  }

  @Override
  public <T> T load(VKey<T> key) {
    T result = getOfy().load().key(key.getOfyKey()).now();
    if (result == null) {
      throw new NoSuchElementException(key.toString());
    }
    return result;
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> load(Iterable<? extends VKey<? extends T>> keys) {
    // Keep track of the Key -> VKey mapping so we can translate them back.
    ImmutableMap<Key<T>, VKey<? extends T>> keyMap =
        StreamSupport.stream(keys.spliterator(), false)
            .distinct()
            .collect(toImmutableMap(key -> (Key<T>) key.getOfyKey(), Functions.identity()));

    // The lambda argument to keys() effectively converts Iterator -> Iterable.
    return getOfy().load().keys(() -> keyMap.keySet().iterator()).entrySet().stream()
        .collect(ImmutableMap.toImmutableMap(entry -> keyMap.get(entry.getKey()), Entry::getValue));
  }

  @Override
  public <T> ImmutableList<T> loadAll(Class<T> clazz) {
    // We can do a ofy().load().type(clazz), but this doesn't work in a transaction.
    throw new UnsupportedOperationException("Not available in the Datastore transaction manager");
  }

  @Override
  public void delete(VKey<?> key) {
    getOfy().delete().key(key.getOfyKey()).now();
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    // We have to create a list to work around the wildcard capture issue here.
    // See https://docs.oracle.com/javase/tutorial/java/generics/capture.html
    ImmutableList<Key<?>> list =
        StreamSupport.stream(vKeys.spliterator(), false)
            .map(VKey::getOfyKey)
            .collect(toImmutableList());
    getOfy().delete().keys(list).now();
  }
}
