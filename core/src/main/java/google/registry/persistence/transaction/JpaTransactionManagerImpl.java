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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.ofy.DatastoreTransactionManager.toSqlEntity;
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
import google.registry.config.RegistryConfig;
import google.registry.model.ImmutableObject;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyContactIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyDomainIndex;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.model.server.KmsSecret;
import google.registry.model.tmch.ClaimsList.ClaimsListSingleton;
import google.registry.persistence.JpaRetries;
import google.registry.persistence.VKey;
import google.registry.util.Clock;
import google.registry.util.Retrier;
import google.registry.util.SystemSleeper;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;
import javax.persistence.FlushModeType;
import javax.persistence.LockModeType;
import javax.persistence.Parameter;
import javax.persistence.PersistenceException;
import javax.persistence.Query;
import javax.persistence.TemporalType;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.metamodel.EntityType;
import javax.persistence.metamodel.SingularAttribute;
import org.joda.time.DateTime;

/** Implementation of {@link JpaTransactionManager} for JPA compatible database. */
public class JpaTransactionManagerImpl implements JpaTransactionManager {

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final Retrier retrier = new Retrier(new SystemSleeper(), 3);

  // The entity of classes in this set will be simply ignored when passed to modification
  // operations, i.e. insert, put, update and delete. This is to help maintain a single code path
  // when we switch from ofy to tm() for the database migration as we don't need have a condition
  // to exclude the Datastore specific entities when the underlying tm() is jpaTm().
  // TODO(b/176108270): Remove this property after database migration.
  private static final ImmutableSet<Class<? extends ImmutableObject>> IGNORED_ENTITY_CLASSES =
      ImmutableSet.of(
          ClaimsListSingleton.class,
          EppResourceIndex.class,
          ForeignKeyContactIndex.class,
          ForeignKeyDomainIndex.class,
          ForeignKeyHostIndex.class,
          KmsSecret.class);

  // EntityManagerFactory is thread safe.
  private final EntityManagerFactory emf;
  private final Clock clock;
  // TODO(b/177588434): Investigate alternatives for managing transaction information. ThreadLocal
  // adds an unnecessary restriction that each request has to be processed by one thread
  // synchronously.
  private final ThreadLocal<TransactionInfo> transactionInfo =
      ThreadLocal.withInitial(TransactionInfo::new);

  public JpaTransactionManagerImpl(EntityManagerFactory emf, Clock clock) {
    this.emf = emf;
    this.clock = clock;
  }

  @Override
  public void teardown() {
    emf.close();
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
  public <T> TypedQuery<T> query(String sqlString, Class<T> resultClass) {
    return new DetachingTypedQuery(getEntityManager().createQuery(sqlString, resultClass));
  }

  @Override
  public <T> TypedQuery<T> query(CriteriaQuery<T> criteriaQuery) {
    return new DetachingTypedQuery(getEntityManager().createQuery(criteriaQuery));
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
  public void assertInTransaction() {
    if (!inTransaction()) {
      throw new IllegalStateException("Not in a transaction");
    }
  }

  @Override
  public <T> T transact(Supplier<T> work) {
    return retrier.callWithRetry(
        () -> {
          if (inTransaction()) {
            return work.get();
          }
          TransactionInfo txnInfo = transactionInfo.get();
          txnInfo.entityManager = emf.createEntityManager();
          EntityTransaction txn = txnInfo.entityManager.getTransaction();
          try {
            txn.begin();
            txnInfo.start(clock);
            T result = work.get();
            txnInfo.recordTransaction();
            txn.commit();
            return result;
          } catch (RuntimeException | Error e) {
            // Error is unchecked!
            try {
              txn.rollback();
              logger.atWarning().log("Error during transaction; transaction rolled back");
            } catch (Throwable rollbackException) {
              logger.atSevere().withCause(rollbackException).log(
                  "Rollback failed; suppressing error");
            }
            throw e;
          } finally {
            txnInfo.clear();
          }
        },
        JpaRetries::isFailedTxnRetriable);
  }

  @Override
  public <T> T transactNoRetry(Supplier<T> work) {
    if (inTransaction()) {
      return work.get();
    }
    TransactionInfo txnInfo = transactionInfo.get();
    txnInfo.entityManager = emf.createEntityManager();
    EntityTransaction txn = txnInfo.entityManager.getTransaction();
    try {
      txn.begin();
      txnInfo.start(clock);
      T result = work.get();
      txnInfo.recordTransaction();
      txn.commit();
      return result;
    } catch (RuntimeException | Error e) {
      // Error is unchecked!
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
  public void transactNoRetry(Runnable work) {
    transactNoRetry(
        () -> {
          work.run();
          return null;
        });
  }

  // TODO(b/177674699): Remove all transactNew methods as they are same as transact after the
  // database migration.
  @Override
  public <T> T transactNew(Supplier<T> work) {
    return transact(work);
  }

  @Override
  public void transactNew(Runnable work) {
    transact(work);
  }

  // For now, read-only transactions and "transactNew" methods only create (or use existing)
  // standard transactions. Attempting to use a read-only transaction can break larger transactions
  // (if we were already in one) so we don't set read-only mode.
  //
  // TODO(gbrodman): If necessary, implement transactNew and readOnly transactions using Postgres
  // savepoints, see https://www.postgresql.org/docs/8.1/sql-savepoint.html
  @Override
  public <T> T transactNewReadOnly(Supplier<T> work) {
    return retrier.callWithRetry(() -> transact(work), JpaRetries::isFailedQueryRetriable);
  }

  @Override
  public void transactNewReadOnly(Runnable work) {
    transactNewReadOnly(
        () -> {
          work.run();
          return null;
        });
  }

  @Override
  public <T> T doTransactionless(Supplier<T> work) {
    return retrier.callWithRetry(() -> transact(work), JpaRetries::isFailedQueryRetriable);
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
    if (isEntityOfIgnoredClass(entity)) {
      return;
    }
    assertInTransaction();
    // Necessary due to the changes in HistoryEntry representation during the migration to SQL
    Object toPersist = toSqlEntity(entity);
    transactionInfo.get().insertObject(toPersist);
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::insert);
  }

  @Override
  public void insertWithoutBackup(Object entity) {
    insert(entity);
  }

  @Override
  public void insertAllWithoutBackup(ImmutableCollection<?> entities) {
    insertAll(entities);
  }

  @Override
  public void put(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    if (isEntityOfIgnoredClass(entity)) {
      return;
    }
    assertInTransaction();
    // Necessary due to the changes in HistoryEntry representation during the migration to SQL
    Object toPersist = toSqlEntity(entity);
    transactionInfo.get().updateObject(toPersist);
  }

  @Override
  public void putAll(Object... entities) {
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
  public void putWithoutBackup(Object entity) {
    put(entity);
  }

  @Override
  public void putAllWithoutBackup(ImmutableCollection<?> entities) {
    putAll(entities);
  }

  @Override
  public void update(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    if (isEntityOfIgnoredClass(entity)) {
      return;
    }
    assertInTransaction();
    checkArgument(exists(entity), "Given entity does not exist");
    // Necessary due to the changes in HistoryEntry representation during the migration to SQL
    Object toPersist = toSqlEntity(entity);
    transactionInfo.get().updateObject(toPersist);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    checkArgumentNotNull(entities, "entities must be specified");
    assertInTransaction();
    entities.forEach(this::update);
  }

  @Override
  public void updateAll(Object... entities) {
    updateAll(ImmutableList.of(entities));
  }

  @Override
  public void updateWithoutBackup(Object entity) {
    update(entity);
  }

  @Override
  public void updateAllWithoutBackup(ImmutableCollection<?> entities) {
    updateAll(entities);
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getSqlKey());
    return exists(entityType.getName(), entityIds);
  }

  @Override
  public boolean exists(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    entity = toSqlEntity(entity);
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
    return query.getResultList().size() > 0;
  }

  @Override
  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    return Optional.ofNullable(getEntityManager().find(key.getKind(), key.getSqlKey()))
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
                    key, detach(getEntityManager().find(key.getKind(), key.getSqlKey()))))
        .filter(entry -> entry.getValue() != null)
        .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Override
  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return Streams.stream(entities)
        .map(DatastoreTransactionManager::toSqlEntity)
        .filter(this::exists)
        .map(this::loadByEntity)
        .collect(toImmutableList());
  }

  @Override
  public <T> T loadByKey(VKey<T> key) {
    checkArgumentNotNull(key, "key must be specified");
    assertInTransaction();
    T result = getEntityManager().find(key.getKind(), key.getSqlKey());
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
    // If the caller requested a HistoryEntry, load the corresponding *History class
    T possibleChild = toSqlEntity(entity);
    @SuppressWarnings("unchecked")
    T returnValue =
        (T)
            loadByKey(
                VKey.createSql(
                    possibleChild.getClass(),
                    emf.getPersistenceUnitUtil().getIdentifier(possibleChild)));
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
    if (IGNORED_ENTITY_CLASSES.contains(key.getKind())) {
      return 0;
    }
    EntityType<?> entityType = getEntityType(key.getKind());
    ImmutableSet<EntityId> entityIds = getEntityIdsFromSqlKey(entityType, key.getSqlKey());
    // TODO(b/179158393): use Criteria for query to leave not doubt about sql injection risk.
    String sql =
        String.format("DELETE FROM %s WHERE %s", entityType.getName(), getAndClause(entityIds));
    Query query = query(sql);
    entityIds.forEach(entityId -> query.setParameter(entityId.name, entityId.value));
    transactionInfo.get().addDelete(key);
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
    if (isEntityOfIgnoredClass(entity)) {
      return entity;
    }
    assertInTransaction();
    entity = toSqlEntity(entity);
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
  public void deleteWithoutBackup(VKey<?> key) {
    delete(key);
  }

  @Override
  public void deleteWithoutBackup(Iterable<? extends VKey<?>> keys) {
    delete(keys);
  }

  @Override
  public void deleteWithoutBackup(Object entity) {
    delete(entity);
  }

  @Override
  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return new JpaQueryComposerImpl<T>(entity);
  }

  @Override
  public void clearSessionCache() {
    // This is an intended no-op method as there is no session cache in Postgresql.
  }

  @Override
  public boolean isOfy() {
    return false;
  }

  @Override
  public <T> void assertDelete(VKey<T> key) {
    if (internalDelete(key) != 1) {
      throw new IllegalArgumentException(
          String.format("Error deleting the entity of the key: %s", key.getSqlKey()));
    }
  }

  private <T> EntityType<T> getEntityType(Class<T> clazz) {
    return emf.getMetamodel().entity(clazz);
  }

  private static class EntityId {
    private String name;
    private Object value;

    private EntityId(String name, Object value) {
      this.name = name;
      this.value = value;
    }
  }

  private static boolean isEntityOfIgnoredClass(Object entity) {
    return IGNORED_ENTITY_CLASSES.contains(entity.getClass());
  }

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
        .map(SingularAttribute::getName)
        .map(
            idName -> {
              Object idValue = getFieldValue(idContainer, idName);
              return new EntityId(idName, idValue);
            })
        .collect(toImmutableSet());
  }

  private String getAndClause(ImmutableSet<EntityId> entityIds) {
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
  private static Field getField(Class clazz, String fieldName) throws NoSuchFieldException {
    try {
      // Note that we have to use getDeclaredField() for this, getField() just finds public fields.
      return clazz.getDeclaredField(fieldName);
    } catch (NoSuchFieldException e) {
      Class base = clazz.getSuperclass();
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
      // The object is not an entity.  Return without detaching.
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

    // Serializable representation of the transaction to be persisted in the Transaction table.
    Transaction.Builder contentsBuilder;

    // The set of entity objects that have been either persisted (via insert()) or merged (via
    // put()/update()).  If the entity manager returns these as a result of a find() or query
    // operation, we can not detach them -- detaching removes them from the transaction and causes
    // them to not be saved to the database -- so we throw an exception instead.
    Set<Object> objectsToSave = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());

    /** Start a new transaction. */
    private void start(Clock clock) {
      checkArgumentNotNull(clock);
      inTransaction = true;
      transactionTime = clock.nowUtc();
      if (RegistryConfig.getCloudSqlReplicateTransactions()) {
        contentsBuilder = new Transaction.Builder();
      }
    }

    private void clear() {
      inTransaction = false;
      transactionTime = null;
      contentsBuilder = null;
      objectsToSave = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
      if (entityManager != null) {
        // Close this EntityManager just let the connection pool be able to reuse it, it doesn't
        // close the underlying database connection.
        entityManager.close();
        entityManager = null;
      }
    }

    private void addUpdate(Object entity) {
      if (contentsBuilder != null) {
        contentsBuilder.addUpdate(entity);
      }
    }

    private void addDelete(VKey<?> key) {
      if (contentsBuilder != null) {
        contentsBuilder.addDelete(key);
      }
    }

    private void recordTransaction() {
      if (contentsBuilder != null) {
        Transaction persistedTxn = contentsBuilder.build();
        if (!persistedTxn.isEmpty()) {
          entityManager.persist(persistedTxn.toEntity());
        }
      }
    }

    /** Does the full "update" on an object including all internal housekeeping. */
    private void updateObject(Object object) {
      Object merged = entityManager.merge(object);
      objectsToSave.add(merged);
      addUpdate(object);
    }

    /** Does the full "insert" on a new object including all internal housekeeping. */
    private void insertObject(Object object) {
      entityManager.persist(object);
      objectsToSave.add(object);
      addUpdate(object);
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

    public DetachingTypedQuery(TypedQuery<T> delegate) {
      this.delegate = delegate;
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
          CriteriaQueryBuilder.create(getEntityManager(), entityClass);
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
      return results.size() > 0 ? Optional.of(detach(results.get(0))) : Optional.empty();
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
          CriteriaQueryBuilder.createCount(getEntityManager(), entityClass);
      return addCriteria(queryBuilder).getSingleResult();
    }

    @Override
    public ImmutableList<T> list() {
      return buildQuery().getResultList().stream()
          .map(JpaTransactionManagerImpl.this::detach)
          .collect(ImmutableList.toImmutableList());
    }
  }
}
