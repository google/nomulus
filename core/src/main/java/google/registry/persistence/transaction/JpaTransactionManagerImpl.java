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
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.config.RegistryConfig.getHibernateAllowNestedTransactions;
import static google.registry.persistence.transaction.DatabaseException.throwIfSqlException;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.util.AbstractMap.SimpleEntry;
import static java.util.stream.Collectors.joining;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.common.flogger.FluentLogger;
import com.google.common.flogger.StackSize;
import google.registry.model.ImmutableObject;
import google.registry.persistence.JpaRetries;
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.RegistryEnvironment;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Parameter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TemporalType;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Environment;
import org.joda.time.DateTime;

/** Implementation of {@link JpaTransactionManager} for JPA compatible database. */
public class JpaTransactionManagerImpl implements JpaTransactionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Retrier retrier = new Retrier(new SystemSleeper(), 6);
  private static final String NESTED_TRANSACTION_MESSAGE =
      "Nested transaction detected. Try refactoring to avoid nested transactions. If unachievable,"
          + " use reTransact() in nested transactions";
  private static final String SQL_STATEMENT_LOG_SENTINEL_FORMAT = "SQL_STATEMENT_LOG: %s";

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final Clock clock;
  private final boolean readOnly;

  private static final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock, boolean readOnly) {
    this.emf = emf;
    this.clock = clock;
    this.readOnly = readOnly;
  }

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock) {
    this(emf, clock, false);
  }

  @Override
  public void teardown() {
    emf.close();
  }

  @Override
  public EntityManager getStandaloneEntityManager() {
    return emf.createEntityManager();
  }

  @Override
  public Metamodel getMetaModel() {
    return this.emf.getMetamodel();
  }

  @Override
  public EntityManager getEntityManager() {
    assertInTransaction();
    return transactionInfo.get().entityManager;
  }

  @Override
  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return new DetachingTypedQuery<>(getEntityManager().createQuery(sqlString, resultClass));
  }

  @Override
  public <T> TypedQuery<T> criteriaQuery(CriteriaQuery<T> criteriaQuery) {
    return new DetachingTypedQuery<>(getEntityManager().createQuery(criteriaQuery));
  }

  @Override
  public Query query(String sqlString) {
    return getEntityManager().createQuery(sqlString);
  }

  @Override
  public boolean inTransaction() {
    return transactionInfo.get().inTransaction;
  }

  @Override
  public long allocateId() {
    assertInTransaction();
    return transactionInfo.get().idProvider.get();
  }

  @Override
  public void assertInTransaction() {
    if (!inTransaction()) {
      throw new IllegalStateException("Not in a transaction");
    }
  }

  @Override
  public <T> T reTransact(Callable<T> work) {
    // This prevents inner transaction from retrying, thus avoiding a cascade retry effect.
    if (inTransaction()) {
      return transactNoRetry(null, work);
    }
    return retrier.callWithRetry(
        () -> transactNoRetry(null, work), JpaRetries::isFailedTxnRetriable);
  }

  @Override
  public <T> T transact(TransactionIsolationLevel isolationLevel, Callable<T> work) {
    return transact(isolationLevel, work, false);
  }

  @Override
  public <T> T transact(
      TransactionIsolationLevel isolationLevel, Callable<T> work, boolean logSqlStatements) {
    if (inTransaction()) {
      if (!getHibernateAllowNestedTransactions()) {
        throw new IllegalStateException(NESTED_TRANSACTION_MESSAGE);
      }
      if (RegistryEnvironment.get() != RegistryEnvironment.UNITTEST) {
        logger.atWarning().withStackTrace(StackSize.MEDIUM).atMostEvery(1, TimeUnit.MINUTES).log(
            NESTED_TRANSACTION_MESSAGE);
      }
      // This prevents inner transaction from retrying, thus avoiding a cascade retry effect.
      return transactNoRetry(isolationLevel, work);
    }
    return retrier.callWithRetry(
        () -> transactNoRetry(isolationLevel, work, logSqlStatements),
        JpaRetries::isFailedTxnRetriable);
  }

  @Override
  public <T> T transact(Callable<T> work) {
    return transact(null, work);
  }

  @Override
  public <T> T transactNoRetry(Callable<T> work) {
    return transactNoRetry(null, work);
  }

  @Override
  public <T> T transactNoRetry(
      @Nullable TransactionIsolationLevel isolationLevel, Callable<T> work) {
    return transactNoRetry(isolationLevel, work, false);
  }

  @Override
  public <T> T transactNoRetry(
      @Nullable TransactionIsolationLevel isolationLevel,
      Callable<T> work,
      boolean logSqlStatements) {
    if (inTransaction()) {
      // This check will no longer be necessary when the transact() method always throws
      // inside a nested transaction, as the only way to pass a non-null isolation level
      // is by calling the transact() method (and its variants), which would have already
      // thrown before calling transactNoRetry() when inside a nested transaction.
      //
      // For now, we still need it, so we don't accidentally call a nested transact() with an
      // isolation level override. This buys us time to detect nested transact() calls and either
      // remove them or change the call site to reTransact().
      if (isolationLevel != null) {
        throw new IllegalStateException(
            "Transaction isolation level cannot be specified for nested transactions");
      }
      try {
        return work.call();
      } catch (Exception e) {
        throwIfSqlException(e);
        throwIfUnchecked(e);
        throw new RuntimeException(e);
      }
    }
    TransactionInfo txnInfo = transactionInfo.get();

    txnInfo.entityManager =
        logSqlStatements
            ? emf.unwrap(SessionFactory.class)
                .withOptions()
                .statementInspector(
                    s -> {
                      logger.atInfo().log(SQL_STATEMENT_LOG_SENTINEL_FORMAT, s);
                      return s;
                    })
                .openSession()
            : emf.createEntityManager();
    if (readOnly) {
      // Disable Hibernate's dirty object check on flushing, it has become more aggressive in v6.
      txnInfo.entityManager.unwrap(Session.class).setDefaultReadOnly(true);
    }
    EntityTransaction txn = txnInfo.entityManager.getTransaction();
    try {
      txn.begin();
      txnInfo.start(clock, readOnly ? ReplicaDbIdService::allocateId : this::fetchIdFromSequence);
      if (readOnly) {
        getEntityManager().createNativeQuery("SET TRANSACTION READ ONLY").executeUpdate();
        logger.atInfo().log("Using read-only SQL replica");
      }
      if (isolationLevel != null && isolationLevel != getDefaultTransactionIsolationLevel()) {
        getEntityManager()
            .createNativeQuery(
                String.format("SET TRANSACTION ISOLATION LEVEL %s", isolationLevel.getMode()))
            .executeUpdate();
        logger.atInfo().log(
            "Overriding transaction isolation level from %s to %s",
            getDefaultTransactionIsolationLevel(), isolationLevel);
      }
      T result = work.call();
      txn.commit();
      long duration = clock.nowUtc().getMillis() - txnInfo.transactionTime.getMillis();
      if (duration >= 100) {
        logger.atInfo().log("Transaction duration: %d milliseconds", duration);
      }
      return result;
    } catch (Throwable e) {
      // Catch a Throwable here so even Errors would lead to a rollback.
      try {
        txn.rollback();
        logger.atWarning().log("Error during transaction; transaction rolled back.");
      } catch (Exception rollbackException) {
        logger.atSevere().withCause(rollbackException).log("Rollback failed; suppressing error.");
      }
      throwIfSqlException(e);
      throwIfUnchecked(e);
      throw new RuntimeException(e);
    } finally {
      txnInfo.clear();
    }
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
  public void transact(ThrowingRunnable work) {
    transact(null, work);
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
  public TransactionIsolationLevel getDefaultTransactionIsolationLevel() {
    return TransactionIsolationLevel.valueOf(
        (String) emf.getProperties().get(Environment.ISOLATION));
  }

  @Override
  public TransactionIsolationLevel getCurrentTransactionIsolationLevel() {
    assertInTransaction();
    String mode =
        (String)
            getEntityManager()
                .createNativeQuery("SHOW TRANSACTION ISOLATION LEVEL")
                .getSingleResult();
    return TransactionIsolationLevel.fromMode(mode);
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
  public void insert(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    transactionInfo.get().insertObject(entity);
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::insert);
  }

  @Override
  public void insertAll(ImmutableObject... entities) {
    insertAll(ImmutableSet.copyOf(entities));
  }

  @Override
  public void put(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    transactionInfo.get().updateObject(entity);
  }

  @Override
  public void putAll(ImmutableObject... entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    for (Object entity : entities) {
      put(entity);
    }
  }

  @Override
  public void putAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::put);
  }

  @Override
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    checkArgument(exists(entity), "Given entity does not exist");
    transactionInfo.get().updateObject(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::update);
  }

  @Override
  public void updateAll(ImmutableObject... entities) {
    updateAll(ImmutableList.copyOf(entities));
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getKey());
    return exists(entityType.getName(), entityIds);
  }

  @Override
  public boolean exists(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    EntityType<?> entityType = getEntityType(entity.getClass());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromEntity(entityType, entity);
    return exists(entityType.getName(), entityIds);
  }

  private boolean exists(String entityName, ImmutableSet<EntityId> entityIds) {
    assertInTransaction();
    TypedQuery<Integer> query =
        query(
                String.format("SELECT 1 FROM %s WHERE %s", entityName, getAndClause(entityIds)),
                Integer.class)
            .setMaxResults(1);
    entityIds.forEach(entityId -> query.setParameter(entityId.name, entityId.value));
    return !query.getResultList().isEmpty();
  }

  @Override
  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    return Optional.ofNullable(getEntityManager().find(key.getKind(), key.getKey()))
        .map(this::detach);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys) {
    checkArgumentNotNull(keys, "keys must be specified");
    assertInTransaction();
    return StreamSupport.stream(keys.spliterator(), false)
        // Accept duplicate keys.
        .distinct()
        .map(
            key ->
                new SimpleEntry<VKey<? extends T>, T>(
                    key, detach(getEntityManager().find(key.getKind(), key.getKey()))))
        .filter(entry -> entry.getValue() != null)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return Streams.stream(entities)
        .filter(this::exists)
        .map(this::loadByEntity)
        .collect(toImmutableList());
  }

  @Override
  public <T> T loadByKey(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    T result = getEntityManager().find(key.getKind(), key.getKey());
    if (result == null) {
      throw new NoSuchElementException(key.toString());
    }
    return detach(result);
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(
      Iterable<? extends VKey<? extends T>> keys) {
    ImmutableMap<VKey<? extends T>, T> existing = loadByKeysIfPresent(keys);
    ImmutableSet<? extends VKey<? extends T>> missingKeys =
        Streams.stream(keys).filter(k -> !existing.containsKey(k)).collect(toImmutableSet());
    if (!missingKeys.isEmpty()) {
      throw new NoSuchElementException(
          String.format(
              "Expected to find the following VKeys but they were missing: %s.", missingKeys));
    }
    return existing;
  }

  @Override
  public <T> T loadByEntity(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    @SuppressWarnings("unchecked")
    T returnValue =
        (T)
            loadByKey(
                VKey.create(
                    entity.getClass(),
                    // Casting to Serializable is safe according to JPA (JSR 338 sec. 2.4).
                    (Serializable) emf.getPersistenceUnitUtil().getIdentifier(entity)));
    return returnValue;
  }

  @Override
  public <T> ImmutableList<T> loadByEntities(Iterable<T> entities) {
    return Streams.stream(entities).map(this::loadByEntity).collect(toImmutableList());
  }

  @Override
  public <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return loadAllOfStream(clazz).collect(toImmutableList());
  }

  @Override
  public <T> Stream<T> loadAllOfStream(Class<T> clazz) {
    checkArgumentNotNull(clazz, "clazz must be specified");
    assertInTransaction();
    return getEntityManager()
        .createQuery(String.format("FROM %s", getEntityType(clazz).getName()), clazz)
        .getResultStream()
        .map(this::detach);
  }

  @Override
  public <T> Optional<T> loadSingleton(Class<T> clazz) {
    assertInTransaction();
    List<T> elements =
        getEntityManager()
            .createQuery(String.format("FROM %s", getEntityType(clazz).getName()), clazz)
            .setMaxResults(2)
            .getResultList();
    checkArgument(
        elements.size() <= 1,
        "Expected at most one entity of type %s, found at least two",
        clazz.getSimpleName());
    return elements.stream().findFirst().map(this::detach);
  }

  private int internalDelete(VKey<?> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getKey());
    String sql =
        String.format("DELETE FROM %s WHERE %s", entityType.getName(), getAndClause(entityIds));
    Query query = query(sql);
    entityIds.forEach(entityId -> query.setParameter(entityId.name, entityId.value));
    return query.executeUpdate();
  }

  @Override
  public void delete(VKey<?> key) {
    internalDelete(key);
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    checkArgumentNotNull(vKeys, "vKeys must be specified");
    vKeys.forEach(this::internalDelete);
  }

  @Override
  public <T> T delete(T entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    assertInTransaction();
    T managedEntity = entity;
    if (!getEntityManager().contains(entity)) {
      // We don't add the entity to "objectsToSave": once deleted, the object should never be
      // returned as a result of the query or lookup.
      managedEntity = getEntityManager().merge(entity);
    }
    getEntityManager().remove(managedEntity);
    return managedEntity;
  }

  @Override
  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return new JpaQueryComposerImpl<>(entity);
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    if (internalDelete(key) != 1) {
      throw new IllegalArgumentException(
          String.format("Error deleting the entity of the key: %s", key.getKey()));
    }
  }

  private <T> EntityType<T> getEntityType(Class<T> clazz) {
    return emf.getMetamodel().entity(clazz);
  }

  /**
   * A SQL Sequence based ID allocator that generates an ID from a monotonically increasing {@link
   * AtomicLong}
   *
   * <p>The generated IDs are project-wide unique.
   */
  private long fetchIdFromSequence() {
    return (Long)
        getEntityManager()
            .createNativeQuery("SELECT nextval('project_wide_unique_id_seq')")
            .getSingleResult();
  }

  private record EntityId(String name, Object value) {}

  private static ImmutableSet<EntityId> getEntityIdsFromEntity(
      EntityType<?> entityType, Object entity) {
    if (entityType.hasSingleIdAttribute()) {
      String idName = entityType.getId(entityType.getIdType().getJavaType()).getName();
      Object idValue = getFieldValue(entity, idName);
      return ImmutableSet.of(new EntityId(idName, idValue));
    } else {
      return getEntityIdsFromIdContainer(entityType, entity);
    }
  }

  private static ImmutableSet<EntityId> getEntityIdsFromSqlKey(
      EntityType<?> entityType, Object sqlKey) {
    if (entityType.hasSingleIdAttribute()) {
      String idName = entityType.getId(entityType.getIdType().getJavaType()).getName();
      return ImmutableSet.of(new EntityId(idName, sqlKey));
    } else {
      return getEntityIdsFromIdContainer(entityType, sqlKey);
    }
  }

  private static ImmutableSet<EntityId> getEntityIdsFromIdContainer(
      EntityType<?> entityType, Object idContainer) {
    return entityType.getIdClassAttributes().stream()
        .map(
            attribute -> {
              String idName = attribute.getName();
              // The object may use either Java getters or field names to represent the ID object.
              // Attempt the Java getter, then fall back to the field name if that fails.
              String methodName = attribute.getJavaMember().getName();
              Object idValue;
              try {
                Method method = idContainer.getClass().getDeclaredMethod(methodName);
                method.setAccessible(true);
                idValue = method.invoke(idContainer);
              } catch (NoSuchMethodException
                  | IllegalAccessException
                  | InvocationTargetException e) {
                idValue = getFieldValue(idContainer, idName);
              }
              return new EntityId(idName, idValue);
            })
        .collect(toImmutableSet());
  }

  private static String getAndClause(ImmutableSet<EntityId> entityIds) {
    return entityIds.stream()
        .map(entityId -> String.format("%s = :%s", entityId.name, entityId.name))
        .collect(joining(" AND "));
  }

  private static Object getFieldValue(Object object, String fieldName) {
    try {
      Field field = getField(object.getClass(), fieldName);
      field.setAccessible(true);
      return field.get(object);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Gets the field definition from clazz or any superclass. */
  private static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
    try {
      // Note that we have to use getDeclaredField() for this, getField() just finds public fields.
      return clazz.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      Class<?> base = clazz.getSuperclass();
      if (base != null) {
        return getField(base, fieldName);
      } else {
        throw e;
      }
    }
  }

  @Nullable
  private <T> T detachIfEntity(@Nullable T object) {
    if (object == null) {
      return null;
    }

    // Check if the object is an array, if so we'll want to recurse through the elements.
    if (object.getClass().isArray()) {
      for (int i = 0; i < Array.getLength(object); ++i) {
        detachIfEntity(Array.get(object, i));
      }
      return object;
    }

    // Check to see if it is an entity (queries can return raw column values or counts, so this
    // could be String, Long, ...).
    try {
      getEntityManager().getMetamodel().entity(object.getClass());
    } catch (IllegalArgumentException e) {
      // The object is not an entity. Return without detaching.
      return object;
    }

    // At this point, object must be an entity.
    return detach(object);
  }

  /** Detach the entity, suitable for use in Optional.map(). */
  @Nullable
  private <T> T detach(@Nullable T entity) {
    if (entity != null) {

      // If the entity was previously persisted or merged, we have to throw an exception.
      if (transactionInfo.get().willSave(entity)) {
        throw new IllegalStateException("Inserted/updated object reloaded: " + entity);
      }

      getEntityManager().detach(entity);
    }
    return entity;
  }

  private static class TransactionInfo {
    EntityManager entityManager;
    boolean inTransaction = false;
    DateTime transactionTime;
    Supplier<Long> idProvider;

    // The set of entity objects that have been either persisted (via insert()) or merged (via
    // put()/update()). If the entity manager returns these as a result of a find() or query
    // operation, we can not detach them -- detaching removes them from the transaction and causes
    // them to not be saved to the database -- so we throw an exception instead.
    Set<Object> objectsToSave = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Start a new transaction. */
    private void start(Clock clock, Supplier<Long> idProvider) {
      checkArgumentNotNull(clock);
      inTransaction = true;
      transactionTime = clock.nowUtc();
      this.idProvider = idProvider;
    }

    private void clear() {
      idProvider = null;
      inTransaction = false;
      transactionTime = null;
      objectsToSave = Collections.newSetFromMap(new IdentityHashMap<>());
      if (entityManager != null) {
        // Close this EntityManager just let the connection pool be able to reuse it, it doesn't
        // close the underlying database connection.
        entityManager.close();
        entityManager = null;
      }
    }

    /** Does the full "update" on an object including all internal housekeeping. */
    private void updateObject(Object object) {
      Object merged = entityManager.merge(object);
      objectsToSave.add(merged);
    }

    /** Does the full "insert" on a new object including all internal housekeeping. */
    private void insertObject(Object object) {
      entityManager.persist(object);
      objectsToSave.add(object);
    }

    /** Returns true if the object has been persisted/merged and will be saved on commit. */
    private boolean willSave(Object object) {
      return objectsToSave.contains(object);
    }
  }

  /**
   * Typed query wrapper that applies a transform to all result objects.
   *
   * <p>This is used to detach objects upon load.
   */
  @VisibleForTesting
  class DetachingTypedQuery<T> implements TypedQuery<T> {

    TypedQuery<T> delegate;

    DetachingTypedQuery(TypedQuery<T> delegate) {
      this.delegate = delegate;
    }

    @Override
    public Integer getTimeout() {
      return delegate.getTimeout();
    }

    @Override
    public CacheRetrieveMode getCacheRetrieveMode() {
      return delegate.getCacheRetrieveMode();
    }

    @Override
    public CacheStoreMode getCacheStoreMode() {
      return delegate.getCacheStoreMode();
    }

    @Override
    public TypedQuery<T> setTimeout(Integer timeout) {
      delegate.setTimeout(timeout);
      return this;
    }

    @Override
    public TypedQuery<T> setCacheStoreMode(CacheStoreMode mode) {
      delegate.setCacheStoreMode(mode);
      return this;
    }

    @Override
    public TypedQuery<T> setCacheRetrieveMode(CacheRetrieveMode mode) {
      delegate.setCacheRetrieveMode(mode);
      return this;
    }

    @Override
    public T getSingleResultOrNull() {
      return delegate.getSingleResultOrNull();
    }

    @Override
    public List<T> getResultList() {
      return delegate
          .getResultStream()
          .map(JpaTransactionManagerImpl.this::detachIfEntity)
          .collect(toImmutableList());
    }

    @Override
    public Stream<T> getResultStream() {
      return delegate.getResultStream().map(JpaTransactionManagerImpl.this::detachIfEntity);
    }

    @Override
    public T getSingleResult() {
      return detachIfEntity(delegate.getSingleResult());
    }

    @Override
    public TypedQuery<T> setMaxResults(int maxResults) {
      delegate.setMaxResults(maxResults);
      return this;
    }

    @Override
    public TypedQuery<T> setFirstResult(int startPosition) {
      delegate.setFirstResult(startPosition);
      return this;
    }

    @Override
    public TypedQuery<T> setHint(String hintName, Object value) {
      delegate.setHint(hintName, value);
      return this;
    }

    @Override
    public <U> TypedQuery<T> setParameter(Parameter<U> param, U value) {
      delegate.setParameter(param, value);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(
        Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
      delegate.setParameter(param, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(
        Parameter<Date> param, Date value, TemporalType temporalType) {
      delegate.setParameter(param, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(String name, Object value) {
      delegate.setParameter(name, value);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(String name, Calendar value, TemporalType temporalType) {
      delegate.setParameter(name, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(String name, Date value, TemporalType temporalType) {
      delegate.setParameter(name, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(int position, Object value) {
      delegate.setParameter(position, value);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(int position, Calendar value, TemporalType temporalType) {
      delegate.setParameter(position, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setParameter(int position, Date value, TemporalType temporalType) {
      delegate.setParameter(position, value, temporalType);
      return this;
    }

    @Override
    public TypedQuery<T> setFlushMode(FlushModeType flushMode) {
      delegate.setFlushMode(flushMode);
      return this;
    }

    @Override
    public TypedQuery<T> setLockMode(LockModeType lockMode) {
      delegate.setLockMode(lockMode);
      return this;
    }

    // Query interface

    @Override
    public int executeUpdate() {
      return delegate.executeUpdate();
    }

    @Override
    public int getMaxResults() {
      return delegate.getMaxResults();
    }

    @Override
    public int getFirstResult() {
      return delegate.getFirstResult();
    }

    @Override
    public Map<String, Object> getHints() {
      return delegate.getHints();
    }

    @Override
    public Set<Parameter<?>> getParameters() {
      return delegate.getParameters();
    }

    @Override
    public Parameter<?> getParameter(String name) {
      return delegate.getParameter(name);
    }

    @Override
    public <U> Parameter<U> getParameter(String name, Class<U> type) {
      return delegate.getParameter(name, type);
    }

    @Override
    public Parameter<?> getParameter(int position) {
      return delegate.getParameter(position);
    }

    @Override
    public <U> Parameter<U> getParameter(int position, Class<U> type) {
      return delegate.getParameter(position, type);
    }

    @Override
    public boolean isBound(Parameter<?> param) {
      return delegate.isBound(param);
    }

    @Override
    public <U> U getParameterValue(Parameter<U> param) {
      return delegate.getParameterValue(param);
    }

    @Override
    public Object getParameterValue(String name) {
      return delegate.getParameterValue(name);
    }

    @Override
    public Object getParameterValue(int position) {
      return delegate.getParameterValue(position);
    }

    @Override
    public FlushModeType getFlushMode() {
      return delegate.getFlushMode();
    }

    @Override
    public LockModeType getLockMode() {
      return delegate.getLockMode();
    }

    @Override
    public <U> U unwrap(Class<U> cls) {
      return delegate.unwrap(cls);
    }
  }

  private class JpaQueryComposerImpl<T> extends QueryComposer<T> {

    private static final int DEFAULT_FETCH_SIZE = 1000;

    private int fetchSize = DEFAULT_FETCH_SIZE;

    JpaQueryComposerImpl(Class<T> entityClass) {
      super(entityClass);
    }

    private TypedQuery<T> buildQuery() {
      CriteriaQueryBuilder<T> queryBuilder =
          CriteriaQueryBuilder.create(JpaTransactionManagerImpl.this, entityClass);
      return addCriteria(queryBuilder);
    }

    private <U> TypedQuery<U> addCriteria(CriteriaQueryBuilder<U> queryBuilder) {
      for (WhereClause<?> pred : predicates) {
        pred.addToCriteriaQueryBuilder(queryBuilder);
      }

      if (orderBy != null) {
        queryBuilder.orderByAsc(orderBy);
      }

      return getEntityManager().createQuery(queryBuilder.build());
    }

    @Override
    public QueryComposer<T> withFetchSize(int fetchSize) {
      checkArgument(fetchSize >= 0, "FetchSize must not be negative");
      this.fetchSize = fetchSize;
      return this;
    }

    @Override
    public Optional<T> first() {
      List<T> results = buildQuery().setMaxResults(1).getResultList();
      return !results.isEmpty() ? Optional.of(detach(results.get(0))) : Optional.empty();
    }

    @Override
    public T getSingleResult() {
      return detach(buildQuery().getSingleResult());
    }

    @Override
    public Stream<T> stream() {
      if (fetchSize == 0) {
        logger.atWarning().log("Query result streaming is not enabled.");
      }
      TypedQuery<T> query = buildQuery();
      JpaTransactionManager.setQueryFetchSize(query, fetchSize);
      return query.getResultStream().map(JpaTransactionManagerImpl.this::detach);
    }

    @Override
    public long count() {
      CriteriaQueryBuilder<Long> queryBuilder =
          CriteriaQueryBuilder.createCount(JpaTransactionManagerImpl.this, entityClass);
      return addCriteria(queryBuilder).getSingleResult();
    }

    @Override
    public ImmutableList<T> list() {
      return buildQuery().getResultList().stream()
          .map(JpaTransactionManagerImpl.this::detach)
          .collect(toImmutableList());
    }
  }

  /**
   * Provides {@code long} values for use as {@code id} by JPA model entities in (read-only)
   * transactions in the replica database. Each id is only unique in the JVM instance.
   *
   * <p>The {@link #fetchIdFromSequence database sequence-based id allocator} cannot be used with
   * the replica because id generation is a write operation.
   */
  private static final class ReplicaDbIdService {

    private static final AtomicLong nextId = new AtomicLong(1);

    /**
     * Returns the next long value from a {@link AtomicLong}. Each id is unique in the JVM instance.
     */
    static long allocateId() {
      return nextId.getAndIncrement();
    }
  }
}
