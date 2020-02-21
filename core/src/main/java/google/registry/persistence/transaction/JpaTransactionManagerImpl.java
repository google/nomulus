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

package google.registry.persistence.transaction;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.flogger.FluentLogger;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Supplier;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import org.joda.time.DateTime;

/** Implementation of {@link JpaTransactionManager} for JPA compatible database. */
public class JpaTransactionManagerImpl implements JpaTransactionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final Clock clock;
  // TODO(shicong): Investigate alternatives for managing transaction information. ThreadLocal adds
  //  an unnecessary restriction that each request has to be processed by one thread synchronously.
  private final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock) {
    this.emf = emf;
    this.clock = clock;
  }

  @Override
  public EntityManager getEntityManager() {
    if (transactionInfo.get().entityManager == null) {
      throw new PersistenceException(
          "No EntityManager has been initialized. getEntityManager() must be invoked in the scope"
              + " of a transaction");
    }
    return transactionInfo.get().entityManager;
  }

  @Override
  public boolean inTransaction() {
    return transactionInfo.get().inTransaction;
  }

  @Override
  public void assertInTransaction() {
    if (!inTransaction()) {
      throw new PersistenceException("Not in a transaction");
    }
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    // TODO(shicong): Investigate removing transactNew functionality after migration as it may
    //  be same as this one.
    if (inTransaction()) {
      return work.get();
    }
    TransactionInfo txnInfo = transactionInfo.get();
    txnInfo.entityManager = emf.createEntityManager();
    EntityTransaction txn = txnInfo.entityManager.getTransaction();
    try {
      txn.begin();
      txnInfo.inTransaction = true;
      txnInfo.transactionTime = clock.nowUtc();
      T result = work.get();
      txn.commit();
      return result;
    } catch (RuntimeException e) {
      try {
        txn.rollback();
        logger.atWarning().log("Error during transaction; transaction rolled back");
      } catch (Throwable rollbackException) {
        logger.atSevere().withCause(rollbackException).log("Rollback failed; suppressing error");
      }
      throw e;
    } finally {
      txnInfo.clear();
    }
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
  public <T> T transactNew(Supplier<T> work) {
    // TODO(shicong): Implements the functionality to start a new transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public void transactNew(Runnable work) {
    // TODO(shicong): Implements the functionality to start a new transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T transactNewReadOnly(Supplier<T> work) {
    // TODO(shicong): Implements read only transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    // TODO(shicong): Implements read only transaction.
    throw new UnsupportedOperationException();
  }

  @Override
  public <T> T doTransactionless(Supplier<T> work) {
    // TODO(shicong): Implements doTransactionless.
    throw new UnsupportedOperationException();
  }

  @Override
  public DateTime getTransactionTime() {
    assertInTransaction();
    TransactionInfo txnInfo = transactionInfo.get();
    if (txnInfo.transactionTime == null) {
      throw new PersistenceException("In a transaction but transactionTime is null");
    }
    return txnInfo.transactionTime;
  }

  @Override
  public void saveNew(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    transact(() -> getEntityManager().persist(entity));
  }

  @Override
  public void saveAllNew(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    transact(() -> entities.forEach(this::saveNew));
  }

  @Override
  public void merge(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    transact(() -> getEntityManager().merge(entity));
  }

  @Override
  public void mergeAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    transact(() -> entities.forEach(this::merge));
  }

  @Override
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    transact(
        () -> {
          checkArgument(checkExists(entity), "Given entity does not exist");
          getEntityManager().merge(entity);
        });
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    transact(() -> entities.forEach(this::update));
  }

  @Override
  public boolean checkExists(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    EntityType<?> entityType = getEntityType(entity.getClass());
    ImmutableSet<String> entityIdFieldNames = getEntityIdFieldNames(entityType);
    return transact(
        () -> {
          TypedQuery<Integer> query =
              getEntityManager()
                  .createQuery(
                      String.format(
                          "SELECT 1 FROM %s WHERE %s",
                          entityType.getName(), getAndClause(entityIdFieldNames)),
                      Integer.class)
                  .setMaxResults(1);
          entityIdFieldNames.forEach(
              idFieldName -> query.setParameter(idFieldName, getFieldValue(entity, idFieldName)));
          return query.getResultList().size() > 0;
        });
  }

  @Override
  public <T> Optional<T> load(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    return Optional.ofNullable(
        transact(() -> getEntityManager().find(key.getKind(), key.getSqlKey())));
  }

  @Override
  public <T> ImmutableList<T> loadAll(Class<T> clazz) {
    checkArgumentNotNull(clazz, "clazz must be specified");
    return ImmutableList.copyOf(
        transact(
            () -> {
              EntityType<T> entityType = getEntityManager().getMetamodel().entity(clazz);
              return getEntityManager()
                  .createQuery(
                      String.format("SELECT entity FROM %s entity", entityType.getName()), clazz)
                  .getResultList();
            }));
  }

  @Override
  public <T> int delete(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<String> entityIdFieldNames = getEntityIdFieldNames(entityType);
    return transact(
        () -> {
          String sql =
              String.format(
                  "DELETE FROM %s WHERE %s",
                  entityType.getName(), getAndClause(entityIdFieldNames));
          Query query = getEntityManager().createQuery(sql);
          entityIdFieldNames.forEach(
              idFieldName -> {
                Object idFieldValue =
                    entityType.hasSingleIdAttribute()
                        ? key.getSqlKey()
                        : getFieldValue(key.getSqlKey(), idFieldName);
                query.setParameter(idFieldName, idFieldValue);
              });
          return query.executeUpdate();
        });
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    transact(
        () -> {
          if (delete(key) != 1) {
            throw new IllegalArgumentException(
                String.format("Error deleting the entity of the key: %s", key.getSqlKey()));
          }
        });
  }

  private String getAndClause(Collection<String> fieldNames) {
    return fieldNames.stream()
        .map(idName -> String.format("%s = :%s", idName, idName))
        .collect(joining(" AND "));
  }

  private <T> EntityType<T> getEntityType(Class<T> clazz) {
    return emf.getMetamodel().entity(clazz);
  }

  private static ImmutableSet<String> getEntityIdFieldNames(EntityType<?> entityType) {
    return entityType.hasSingleIdAttribute()
        ? ImmutableSet.of(entityType.getDeclaredId(entityType.getIdType().getJavaType()).getName())
        : entityType.getIdClassAttributes().stream()
            .map(SingularAttribute::getName)
            .collect(toImmutableSet());
  }

  private static Object getFieldValue(Object object, String fieldName) {
    try {
      Field field = object.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(object);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static class TransactionInfo {
    EntityManager entityManager;
    boolean inTransaction = false;
    DateTime transactionTime;

    private void clear() {
      inTransaction = false;
      transactionTime = null;
      if (entityManager != null) {
        // Close this EntityManager just let the connection pool be able to reuse it, it doesn't
        // close the underlying database connection.
        entityManager.close();
        entityManager = null;
      }
    }
  }
}
