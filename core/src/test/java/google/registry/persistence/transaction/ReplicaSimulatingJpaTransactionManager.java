// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import org.joda.time.DateTime;

/**
 * A {@link JpaTransactionManager} that simulates a read-only replica SQL instance.
 *
 * <p>We accomplish this by delegating all calls to the standard transaction manager except for
 * calls that start transactions. For these, we create a transaction like normal but set it to READ
 * ONLY mode before doing any work. This is similar to how the read-only Postgres replica works; it
 * treats all transactions as read-only transactions.
 */
public class ReplicaSimulatingJpaTransactionManager implements JpaTransactionManager {

  private final JpaTransactionManager delegate;

  public ReplicaSimulatingJpaTransactionManager(JpaTransactionManager delegate) {
    this.delegate = delegate;
  }

  @Override
  public void teardown() {
    delegate.teardown();
  }

  @Override
  public EntityManager getStandaloneEntityManager() {
    return delegate.getStandaloneEntityManager();
  }

  @Override
  public EntityManager getEntityManager() {
    return delegate.getEntityManager();
  }

  @Override
  public JpaTransactionManager setDatabaseSnapshot(String snapshotId) {
    return delegate.setDatabaseSnapshot(snapshotId);
  }

  @Override
  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return delegate.query(sqlString, resultClass);
  }

  @Override
  public <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery) {
    return delegate.criteriaQuery(criteriaQuery);
  }

  @Override
  public Query query(String sqlString) {
    return delegate.query(sqlString);
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
    if (delegate.inTransaction()) {
      return work.get();
    }
    return delegate.transact(
        () -> {
          delegate
              .getEntityManager()
              .createNativeQuery("SET TRANSACTION READ ONLY")
              .executeUpdate();
          return work.get();
        });
  }

  @Override
  public <T> T transactWithoutBackup(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public <T> T transactNoRetry(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public void transact(Runnable work) {
    transact(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public void transactNoRetry(Runnable work) {
    transact(work);
  }

  @Override
  public <T> T transactNew(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public void transactNew(Runnable work) {
    transact(work);
  }

  @Override
  public <T> T transactNewReadOnly(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    transact(work);
  }

  @Override
  public <T> T doTransactionless(Supplier<T> work) {
    return delegate.doTransactionless(work);
  }

  @Override
  public DateTime getTransactionTime() {
    return delegate.getTransactionTime();
  }

  @Override
  public void insert(Object entity) {
    delegate.insert(entity);
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    delegate.insertAll(entities);
  }

  @Override
  public void insertAll(ImmutableObject... entities) {
    delegate.insertAll(entities);
  }

  @Override
  public void insertWithoutBackup(ImmutableObject entity) {
    delegate.insertWithoutBackup(entity);
  }

  @Override
  public void insertAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.insertAllWithoutBackup(entities);
  }

  @Override
  public void put(Object entity) {
    delegate.put(entity);
  }

  @Override
  public void putAll(ImmutableObject... entities) {
    delegate.putAll(entities);
  }

  @Override
  public void putAll(ImmutableCollection<?> entities) {
    delegate.putAll(entities);
  }

  @Override
  public void putWithoutBackup(ImmutableObject entity) {
    delegate.putWithoutBackup(entity);
  }

  @Override
  public void putAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.putAllWithoutBackup(entities);
  }

  @Override
  public void update(Object entity) {
    delegate.update(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    delegate.updateAll(entities);
  }

  @Override
  public void updateAll(ImmutableObject... entities) {
    delegate.updateAll(entities);
  }

  @Override
  public void updateWithoutBackup(ImmutableObject entity) {
    delegate.updateWithoutBackup(entity);
  }

  @Override
  public void updateAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.updateAllWithoutBackup(entities);
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    return delegate.exists(key);
  }

  @Override
  public boolean exists(Object entity) {
    return delegate.exists(entity);
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
    delegate.delete(key);
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    delegate.delete(vKeys);
  }

  @Override
  public <T> T delete(T entity) {
    return delegate.delete(entity);
  }

  @Override
  public void deleteWithoutBackup(VKey<?> key) {
    delegate.deleteWithoutBackup(key);
  }

  @Override
  public void deleteWithoutBackup(Iterable<? extends VKey<?>> keys) {
    delegate.deleteWithoutBackup(keys);
  }

  @Override
  public void deleteWithoutBackup(Object entity) {
    delegate.deleteWithoutBackup(entity);
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

  @Override
  public void putIgnoringReadOnlyWithoutBackup(Object entity) {
    delegate.putIgnoringReadOnlyWithoutBackup(entity);
  }

  @Override
  public void deleteIgnoringReadOnlyWithoutBackup(VKey<?> key) {
    delegate.deleteIgnoringReadOnlyWithoutBackup(key);
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    delegate.assertDelete(key);
  }
}
