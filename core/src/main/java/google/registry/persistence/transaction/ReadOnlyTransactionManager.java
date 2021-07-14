// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.persistence.VKey;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/**
 * A transaction manager that only supports read operations.
 *
 * <p>This is used during the Registry 3.0 Datastore-to-SQL migration during the read-only phases so
 * that we can prevent any new writes from occurring -- the only changes during the read-only phases
 * should be the asynchronous transaction replay. See {@link
 * google.registry.model.common.DatabaseMigrationStateSchedule} for more information.
 */
public class ReadOnlyTransactionManager implements TransactionManager {

  private final TransactionManager delegate;

  public ReadOnlyTransactionManager(TransactionManager delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean inTransaction() {
    return delegate.inTransaction();
  }

  @Override
  public void assertInTransaction() {
    delegate.assertInTransaction();
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    return delegate.transact(work);
  }

  @Override
  public void transact(Runnable work) {
    delegate.transact(work);
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return delegate.transactNew(work);
  }

  @Override
  public void transactNew(Runnable work) {
    delegate.transactNew(work);
  }

  @Override
  public <R> R transactNewReadOnly(Supplier<R> work) {
    return delegate.transactNewReadOnly(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    delegate.transactNewReadOnly(work);
  }

  @Override
  public <R> R doTransactionless(Supplier<R> work) {
    return delegate.doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return delegate.getTransactionTime();
  }

  @Override
  public void insert(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public void insertWithoutBackup(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void insertAllWithoutBackup(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public void put(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void putAll(Object... entities) {
    throw readOnlyException();
  }

  @Override
  public void putAll(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public void putWithoutBackup(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void putAllWithoutBackup(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public void update(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public void updateAll(Object... entities) {
    throw readOnlyException();
  }

  @Override
  public void updateWithoutBackup(Object entity) {
    throw readOnlyException();
  }

  @Override
  public void updateAllWithoutBackup(ImmutableCollection<?> entities) {
    throw readOnlyException();
  }

  @Override
  public boolean exists(Object entity) {
    return delegate.exists(entity);
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    return delegate.exists(key);
  }

  @Override
  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return delegate.loadByKeyIfPresent(key);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> vKeys) {
    return delegate.loadByKeysIfPresent(vKeys);
  }

  @Override
  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return delegate.loadByEntitiesIfPresent(entities);
  }

  @Override
  public <T> T loadByKey(VKey<T> key) {
    return delegate.loadByKey(key);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(
      Iterable<? extends VKey<? extends T>> vKeys) {
    return delegate.loadByKeys(vKeys);
  }

  @Override
  public <T> T loadByEntity(T entity) {
    return delegate.loadByEntity(entity);
  }

  @Override
  public <T> ImmutableList<T> loadByEntities(Iterable<T> entities) {
    return delegate.loadByEntities(entities);
  }

  @Override
  public <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return delegate.loadAllOf(clazz);
  }

  @Override
  public <T> Stream<T> loadAllOfStream(Class<T> clazz) {
    return delegate.loadAllOfStream(clazz);
  }

  @Override
  public <T> Optional<T> loadSingleton(Class<T> clazz) {
    return delegate.loadSingleton(clazz);
  }

  @Override
  public void delete(VKey<?> key) {
    throw readOnlyException();
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> keys) {
    throw readOnlyException();
  }

  @Override
  public <T> T delete(T entity) {
    throw readOnlyException();
  }

  @Override
  public void deleteWithoutBackup(VKey<?> key) {
    throw readOnlyException();
  }

  @Override
  public void deleteWithoutBackup(Iterable<? extends VKey<?>> keys) {
    throw readOnlyException();
  }

  @Override
  public void deleteWithoutBackup(Object entity) {
    throw readOnlyException();
  }

  @Override
  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return delegate.createQueryComposer(entity);
  }

  @Override
  public void clearSessionCache() {
    delegate.clearSessionCache();
  }

  @Override
  public boolean isOfy() {
    return delegate.isOfy();
  }

  public static UnsupportedOperationException readOnlyException() {
    return new UnsupportedOperationException("Transaction manager currently in read-only mode");
  }
}
