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

  public void teardown() {
    delegate.teardown();
  }

  public EntityManager getStandaloneEntityManager() {
    return delegate.getStandaloneEntityManager();
  }

  public EntityManager getEntityManager() {
    return delegate.getEntityManager();
  }

  public JpaTransactionManager setDatabaseSnapshot(String snapshotId) {
    return delegate.setDatabaseSnapshot(snapshotId);
  }

  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return delegate.query(sqlString, resultClass);
  }

  public <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery) {
    return delegate.criteriaQuery(criteriaQuery);
  }

  public Query query(String sqlString) {
    return delegate.query(sqlString);
  }

  public boolean inTransaction() {
    return delegate.inTransaction();
  }

  public void assertInTransaction() {
    delegate.assertInTransaction();
  }

  public <T> T transact(Supplier<T> work) {
    return delegate.transact(
        () -> {
          delegate.getEntityManager().createQuery("SET TRANSACTION READ ONLY").executeUpdate();
          return work.get();
        });
  }

  public <T> T transactWithoutBackup(Supplier<T> work) {
    return transact(work);
  }

  public <T> T transactNoRetry(Supplier<T> work) {
    return transact(work);
  }

  public void transact(Runnable work) {
    transact(
        () -> {
          work.run();
          return null;
        });
  }

  public void transactNoRetry(Runnable work) {
    transact(work);
  }

  public <T> T transactNew(Supplier<T> work) {
    return transact(work);
  }

  public void transactNew(Runnable work) {
    transact(work);
  }

  public <T> T transactNewReadOnly(Supplier<T> work) {
    return transact(work);
  }

  public void transactNewReadOnly(Runnable work) {
    transact(work);
  }

  public <T> T doTransactionless(Supplier<T> work) {
    return delegate.doTransactionless(work);
  }

  public DateTime getTransactionTime() {
    return delegate.getTransactionTime();
  }

  public void insert(Object entity) {
    delegate.insert(entity);
  }

  public void insertAll(ImmutableCollection<?> entities) {
    delegate.insertAll(entities);
  }

  public void insertAll(ImmutableObject... entities) {
    delegate.insertAll(entities);
  }

  public void insertWithoutBackup(ImmutableObject entity) {
    delegate.insertWithoutBackup(entity);
  }

  public void insertAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.insertAllWithoutBackup(entities);
  }

  public void put(Object entity) {
    delegate.put(entity);
  }

  public void putAll(ImmutableObject... entities) {
    delegate.putAll(entities);
  }

  public void putAll(ImmutableCollection<?> entities) {
    delegate.putAll(entities);
  }

  public void putWithoutBackup(ImmutableObject entity) {
    delegate.putWithoutBackup(entity);
  }

  public void putAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.putAllWithoutBackup(entities);
  }

  public void update(Object entity) {
    delegate.update(entity);
  }

  public void updateAll(ImmutableCollection<?> entities) {
    delegate.updateAll(entities);
  }

  public void updateAll(ImmutableObject... entities) {
    delegate.updateAll(entities);
  }

  public void updateWithoutBackup(ImmutableObject entity) {
    delegate.updateWithoutBackup(entity);
  }

  public void updateAllWithoutBackup(ImmutableCollection<?> entities) {
    delegate.updateAllWithoutBackup(entities);
  }

  public <T> boolean exists(VKey<T> key) {
    return delegate.exists(key);
  }

  public boolean exists(Object entity) {
    return delegate.exists(entity);
  }

  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return delegate.loadByKeyIfPresent(key);
  }

  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> vKeys) {
    return delegate.loadByKeysIfPresent(vKeys);
  }

  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return delegate.loadByEntitiesIfPresent(entities);
  }

  public <T> T loadByKey(VKey<T> key) {
    return delegate.loadByKey(key);
  }

  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(
      Iterable<? extends VKey<? extends T>> vKeys) {
    return delegate.loadByKeys(vKeys);
  }

  public <T> T loadByEntity(T entity) {
    return delegate.loadByEntity(entity);
  }

  public <T> ImmutableList<T> loadByEntities(Iterable<T> entities) {
    return delegate.loadByEntities(entities);
  }

  public <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return delegate.loadAllOf(clazz);
  }

  public <T> Stream<T> loadAllOfStream(Class<T> clazz) {
    return delegate.loadAllOfStream(clazz);
  }

  public <T> Optional<T> loadSingleton(Class<T> clazz) {
    return delegate.loadSingleton(clazz);
  }

  public void delete(VKey<?> key) {
    delegate.delete(key);
  }

  public void delete(Iterable<? extends VKey<?>> vKeys) {
    delegate.delete(vKeys);
  }

  public <T> T delete(T entity) {
    return delegate.delete(entity);
  }

  public void deleteWithoutBackup(VKey<?> key) {
    delegate.deleteWithoutBackup(key);
  }

  public void deleteWithoutBackup(Iterable<? extends VKey<?>> keys) {
    delegate.deleteWithoutBackup(keys);
  }

  public void deleteWithoutBackup(Object entity) {
    delegate.deleteWithoutBackup(entity);
  }

  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return delegate.createQueryComposer(entity);
  }

  public void clearSessionCache() {
    delegate.clearSessionCache();
  }

  public boolean isOfy() {
    return delegate.isOfy();
  }

  public void putIgnoringReadOnlyWithoutBackup(Object entity) {
    delegate.putIgnoringReadOnlyWithoutBackup(entity);
  }

  public void deleteIgnoringReadOnlyWithoutBackup(VKey<?> key) {
    delegate.deleteIgnoringReadOnlyWithoutBackup(key);
  }

  public <T> void assertDelete(VKey<T> key) {
    delegate.assertDelete(key);
  }
}
