// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.metamodel.Metamodel;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A {@link JpaTransactionManager} that load-balances across multiple read-only replicas.
 *
 * <p>For each top-level transaction, one replica is chosen and used for the duration of the
 * transaction. For non-transactional methods, a replica is chosen for each call.
 */
public class DelegatingReplicaJpaTransactionManager implements JpaTransactionManager {

  private final ImmutableList<JpaTransactionManager> replicas;
  private final Random random;
  private static final AtomicLong nextId = new AtomicLong(1);

  private static final ThreadLocal<JpaTransactionManager> activeReplica = new ThreadLocal<>();

  public DelegatingReplicaJpaTransactionManager(
      ImmutableList<JpaTransactionManager> replicas, Random random) {
    checkArgument(!replicas.isEmpty(), "At least one replica must be provided");
    this.replicas = replicas;
    this.random = random;
  }

  private JpaTransactionManager getReplica() {
    JpaTransactionManager replica = activeReplica.get();
    if (replica != null) {
      return replica;
    }
    return getRandomReplica();
  }

  private <T> T runMaybeAssigningReplica(Function<JpaTransactionManager, T> work) {
    JpaTransactionManager existing = activeReplica.get();
    if (existing != null) {
      return work.apply(existing);
    }
    JpaTransactionManager replica = getRandomReplica();
    activeReplica.set(replica);
    try {
      return work.apply(replica);
    } finally {
      activeReplica.remove();
    }
  }

  private JpaTransactionManager getRandomReplica() {
    return replicas.get(random.nextInt(replicas.size()));
  }

  @Override
  public boolean inTransaction() {
    var replica = activeReplica.get();
    return replica != null && replica.inTransaction();
  }

  @Override
  public void assertInTransaction() {
    JpaTransactionManager replica = activeReplica.get();
    if (replica == null) {
      throw new IllegalStateException("Not in a transaction");
    }
    replica.assertInTransaction();
  }

  @Override
  public long allocateId() {
    return nextId.getAndIncrement();
  }

  @Override
  public <T> T transact(Callable<T> work) {
    return transact(null, work, false);
  }

  @Override
  public <T> T transact(TransactionIsolationLevel isolationLevel, Callable<T> work) {
    return transact(isolationLevel, work, false);
  }

  @Override
  public <T> T transactNoRetry(Callable<T> work) {
    return transactNoRetry(null, work, false);
  }

  @Override
  public <T> T transactNoRetry(TransactionIsolationLevel isolationLevel, Callable<T> work) {
    return transactNoRetry(isolationLevel, work, false);
  }

  @Override
  public <T> T reTransact(Callable<T> work) {
    return runMaybeAssigningReplica(replica -> replica.reTransact(work));
  }

  @Override
  public void transact(ThrowingRunnable work) {
    transact(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public void transact(TransactionIsolationLevel isolationLevel, ThrowingRunnable work) {
    transact(
        isolationLevel,
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public void reTransact(ThrowingRunnable work) {
    reTransact(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public Instant getTxTime() {
    return getReplica().getTxTime();
  }

  @Override
  public void insert(Object entity) {
    getReplica().insert(entity);
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void insertAll(ImmutableObject... entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void put(Object entity) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void putAll(ImmutableObject... entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void putAll(ImmutableCollection<?> entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void update(Object entity) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void updateAll(ImmutableObject... entities) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public boolean exists(Object entity) {
    return getReplica().exists(entity);
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    return getReplica().exists(key);
  }

  @Override
  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return getReplica().loadByKeyIfPresent(key);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys) {
    return getReplica().loadByKeysIfPresent(keys);
  }

  @Override
  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return getReplica().loadByEntitiesIfPresent(entities);
  }

  @Override
  public <T> T loadByKey(VKey<T> key) {
    return getReplica().loadByKey(key);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(
      Iterable<? extends VKey<? extends T>> keys) {
    return getReplica().loadByKeys(keys);
  }

  @Override
  public <T> T loadByEntity(T entity) {
    return getReplica().loadByEntity(entity);
  }

  @Override
  public <T> ImmutableList<T> loadByEntities(Iterable<T> entities) {
    return getReplica().loadByEntities(entities);
  }

  @Override
  public <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return getReplica().loadAllOf(clazz);
  }

  @Override
  public <T> Stream<T> loadAllOfStream(Class<T> clazz) {
    return getReplica().loadAllOfStream(clazz);
  }

  @Override
  public <T> Optional<T> loadSingleton(Class<T> clazz) {
    return getReplica().loadSingleton(clazz);
  }

  @Override
  public void delete(VKey<?> key) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> keys) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public <T> T delete(T entity) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return getReplica().createQueryComposer(entity);
  }

  @Override
  public EntityManager getStandaloneEntityManager() {
    return getReplica().getStandaloneEntityManager();
  }

  @Override
  public Metamodel getMetaModel() {
    return getReplica().getMetaModel();
  }

  @Override
  public EntityManager getEntityManager() {
    return getReplica().getEntityManager();
  }

  @Override
  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return getReplica().query(sqlString, resultClass);
  }

  @Override
  public <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery) {
    return getReplica().criteriaQuery(criteriaQuery);
  }

  @Override
  public Query query(String sqlString) {
    return getReplica().query(sqlString);
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    throw new UnsupportedOperationException("This is a replica database");
  }

  @Override
  public void teardown() {
    for (JpaTransactionManager replica : replicas) {
      replica.teardown();
    }
  }

  @Override
  public TransactionIsolationLevel getDefaultTransactionIsolationLevel() {
    return replicas.get(0).getDefaultTransactionIsolationLevel();
  }

  @Override
  public TransactionIsolationLevel getCurrentTransactionIsolationLevel() {
    return getReplica().getCurrentTransactionIsolationLevel();
  }

  @Override
  public <T> T transact(
      TransactionIsolationLevel isolationLevel, Callable<T> work, boolean logSqlStatements) {
    return runMaybeAssigningReplica(
        replica -> replica.transact(isolationLevel, work, logSqlStatements));
  }

  @Override
  public <T> T transactNoRetry(
      TransactionIsolationLevel isolationLevel, Callable<T> work, boolean logSqlStatements) {
    return runMaybeAssigningReplica(
        replica -> replica.transactNoRetry(isolationLevel, work, logSqlStatements));
  }
}
