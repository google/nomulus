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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;

import com.google.common.base.Functions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.Result;
import com.googlecode.objectify.cmd.Query;
import google.registry.model.annotations.InCrossTld;
import google.registry.model.contact.ContactHistory;
import google.registry.model.domain.DomainHistory;
import google.registry.model.host.HostHistory;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.QueryComposer;
import google.registry.persistence.transaction.TransactionManager;
import google.registry.schema.replay.DatastoreEntity;
import google.registry.schema.replay.SqlEntity;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import org.joda.time.DateTime;

/** Datastore implementation of {@link TransactionManager}. */
public class DatastoreTransactionManager implements TransactionManager {

  private Ofy injectedOfy;

  /** Constructs an instance. */
  public DatastoreTransactionManager(Ofy injectedOfy) {
    this.injectedOfy = injectedOfy;
  }

  private Ofy getOfy() {
    return injectedOfy == null ? auditedOfy() : injectedOfy;
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
  public void insert(Object entity) {
    put(entity);
  }

  @Override
  public void insertAll(ImmutableCollection<?> entities) {
    putAll(entities);
  }

  @Override
  public void insertWithoutBackup(Object entity) {
    putWithoutBackup(entity);
  }

  @Override
  public void insertAllWithoutBackup(ImmutableCollection<?> entities) {
    putAllWithoutBackup(entities);
  }

  @Override
  public void put(Object entity) {
    saveEntity(entity);
  }

  @Override
  public void putAll(Object... entities) {
    syncIfTransactionless(
        getOfy().save().entities(toDatastoreEntities(ImmutableList.copyOf(entities))));
  }

  @Override
  public void putAll(ImmutableCollection<?> entities) {
    syncIfTransactionless(getOfy().save().entities(toDatastoreEntities(entities)));
  }

  @Override
  public void putWithoutBackup(Object entity) {
    syncIfTransactionless(getOfy().saveWithoutBackup().entities(toDatastoreEntity(entity)));
  }

  @Override
  public void putAllWithoutBackup(ImmutableCollection<?> entities) {
    syncIfTransactionless(getOfy().saveWithoutBackup().entities(toDatastoreEntities(entities)));
  }

  @Override
  public void update(Object entity) {
    put(entity);
  }

  @Override
  public void updateAll(ImmutableCollection<?> entities) {
    putAll(entities);
  }

  @Override
  public void updateAll(Object... entities) {
    updateAll(ImmutableList.of(entities));
  }

  @Override
  public void updateWithoutBackup(Object entity) {
    putWithoutBackup(entity);
  }

  @Override
  public void updateAllWithoutBackup(ImmutableCollection<?> entities) {
    putAllWithoutBackup(entities);
  }

  @Override
  public boolean exists(Object entity) {
    return getOfy().load().key(Key.create(toDatastoreEntity(entity))).now() != null;
  }

  @Override
  public <T> boolean exists(VKey<T> key) {
    return loadNullable(key) != null;
  }

  // TODO: add tests for these methods.  They currently have some degree of test coverage because
  // they are used when retrieving the nameservers which require these, as they are now loaded by
  // VKey instead of by ofy Key.  But ideally, there should be one set of TransactionManager
  // interface tests that are applied to both the datastore and SQL implementations.
  @Override
  public <T> Optional<T> loadByKeyIfPresent(VKey<T> key) {
    return Optional.ofNullable(loadNullable(key));
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys) {
    // Keep track of the Key -> VKey mapping so we can translate them back.
    ImmutableMap<Key<T>, VKey<? extends T>> keyMap =
        StreamSupport.stream(keys.spliterator(), false)
            .distinct()
            .collect(toImmutableMap(key -> (Key<T>) key.getOfyKey(), Functions.identity()));

    return getOfy().load().keys(keyMap.keySet()).entrySet().stream()
        .collect(
            toImmutableMap(
                entry -> keyMap.get(entry.getKey()), entry -> toSqlEntity(entry.getValue())));
  }

  @Override
  public <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities) {
    return ImmutableList.copyOf(getOfy().load().entities(entities).values());
  }

  @Override
  public <T> T loadByKey(VKey<T> key) {
    T result = loadNullable(key);
    if (result == null) {
      throw new NoSuchElementException(key.toString());
    }
    return result;
  }

  @Override
  public <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(
      Iterable<? extends VKey<? extends T>> keys) {
    ImmutableMap<VKey<? extends T>, T> result = loadByKeysIfPresent(keys);
    ImmutableSet<? extends VKey<? extends T>> missingKeys =
        Streams.stream(keys).filter(k -> !result.containsKey(k)).collect(toImmutableSet());
    if (!missingKeys.isEmpty()) {
      // Ofy ignores nonexistent keys but the method contract specifies to throw if nonexistent
      throw new NoSuchElementException(
          String.format("Failed to load nonexistent entities for keys: %s", missingKeys));
    }
    return result;
  }

  @Override
  public <T> T loadByEntity(T entity) {
    return (T) toSqlEntity(auditedOfy().load().entity(toDatastoreEntity(entity)).now());
  }

  @Override
  public <T> ImmutableList<T> loadByEntities(Iterable<T> entities) {
    ImmutableList<T> result = loadByEntitiesIfPresent(entities);
    if (result.size() != Iterables.size(entities)) {
      throw new NoSuchElementException(
          String.format("Attempted to load entities, some of which are missing: %s", entities));
    }
    return result;
  }

  @Override
  public <T> ImmutableList<T> loadAllOf(Class<T> clazz) {
    return ImmutableList.copyOf(getPossibleAncestorQuery(clazz));
  }

  @Override
  public <T> Optional<T> loadSingleton(Class<T> clazz) {
    List<T> elements = getPossibleAncestorQuery(clazz).limit(2).list();
    checkArgument(
        elements.size() <= 1,
        "Expected at most one entity of type %s, found at least two",
        clazz.getSimpleName());
    return elements.stream().findFirst();
  }

  @Override
  public void delete(VKey<?> key) {
    syncIfTransactionless(getOfy().delete().key(key.getOfyKey()));
  }

  @Override
  public void delete(Iterable<? extends VKey<?>> vKeys) {
    // We have to create a list to work around the wildcard capture issue here.
    // See https://docs.oracle.com/javase/tutorial/java/generics/capture.html
    ImmutableList<Key<?>> list =
        StreamSupport.stream(vKeys.spliterator(), false)
            .map(VKey::getOfyKey)
            .collect(toImmutableList());
    syncIfTransactionless(getOfy().delete().keys(list));
  }

  @Override
  public <T> T delete(T entity) {
    syncIfTransactionless(getOfy().delete().entity(toDatastoreEntity(entity)));
    return entity;
  }

  @Override
  public void deleteWithoutBackup(VKey<?> key) {
    syncIfTransactionless(getOfy().deleteWithoutBackup().key(key.getOfyKey()));
  }

  @Override
  public void deleteWithoutBackup(Iterable<? extends VKey<?>> keys) {
    syncIfTransactionless(
        getOfy()
            .deleteWithoutBackup()
            .keys(Streams.stream(keys).map(VKey::getOfyKey).collect(toImmutableList())));
  }

  @Override
  public void deleteWithoutBackup(Object entity) {
    syncIfTransactionless(getOfy().deleteWithoutBackup().entity(toDatastoreEntity(entity)));
  }

  @Override
  public <T> QueryComposer<T> createQueryComposer(Class<T> entity) {
    return new DatastoreQueryComposerImpl(entity);
  }

  @Override
  public void clearSessionCache() {
    getOfy().clearSessionCache();
  }

  @Override
  public boolean isOfy() {
    return true;
  }

  /**
   * Executes the given {@link Result} instance synchronously if not in a transaction.
   *
   * <p>The {@link Result} instance contains a task that will be executed by Objectify
   * asynchronously. If it is in a transaction, we don't need to execute the task immediately
   * because it is guaranteed to be done by the end of the transaction. However, if it is not in a
   * transaction, we need to execute it in case the following code expects that happens before
   * themselves.
   */
  private void syncIfTransactionless(Result<?> result) {
    if (!inTransaction()) {
      result.now();
    }
  }

  /**
   * The following three methods exist due to the migration to Cloud SQL.
   *
   * <p>In Cloud SQL, {@link HistoryEntry} objects are represented instead as {@link DomainHistory},
   * {@link ContactHistory}, and {@link HostHistory} objects. During the migration, we do not wish
   * to change the Datastore schema so all of these objects are stored in Datastore as HistoryEntry
   * objects. They are converted to/from the appropriate classes upon retrieval, and converted to
   * HistoryEntry on save. See go/r3.0-history-objects for more details.
   */
  private void saveEntity(Object entity) {
    checkArgumentNotNull(entity, "entity must be specified");
    syncIfTransactionless(getOfy().save().entity(toDatastoreEntity(entity)));
  }

  @Nullable
  private <T> T loadNullable(VKey<T> key) {
    return toSqlEntity(getOfy().load().key(key.getOfyKey()).now());
  }

  /**
   * Converts a possible {@link SqlEntity} to a {@link DatastoreEntity}.
   *
   * <p>One example is that this would convert a {@link DomainHistory} to a {@link HistoryEntry}.
   */
  private static Object toDatastoreEntity(@Nullable Object obj) {
    if (obj instanceof SqlEntity) {
      Optional<DatastoreEntity> possibleDatastoreEntity = ((SqlEntity) obj).toDatastoreEntity();
      if (possibleDatastoreEntity.isPresent()) {
        return possibleDatastoreEntity.get();
      }
    }
    return obj;
  }

  /** Converts many possible {@link SqlEntity} objects to {@link DatastoreEntity} objects. */
  private static ImmutableList<Object> toDatastoreEntities(ImmutableCollection<?> collection) {
    return collection.stream()
        .map(DatastoreTransactionManager::toDatastoreEntity)
        .collect(toImmutableList());
  }

  /**
   * Converts an object to the corresponding {@link SqlEntity} if necessary and possible.
   *
   * <p>This should be used when returning objects from Datastore to make sure they reflect the most
   * recent type of the object in question.
   */
  @SuppressWarnings("unchecked")
  public static <T> T toSqlEntity(@Nullable T obj) {
    // NB: The Key of the object in question may not necessarily be the resulting class that we
    // wish to have. For example, because all *History classes are @EntitySubclasses, their Keys
    // will have type HistoryEntry -- even if you create them based off the *History class.
    if (obj instanceof DatastoreEntity && !(obj instanceof SqlEntity)) {
      Optional<SqlEntity> possibleSqlEntity = ((DatastoreEntity) obj).toSqlEntity();
      if (possibleSqlEntity.isPresent()) {
        return (T) possibleSqlEntity.get();
      }
    }
    return obj;
  }

  /** A query for returning any/all results of an object, with an ancestor if possible. */
  private <T> Query<T> getPossibleAncestorQuery(Class<T> clazz) {
    Query<T> query = getOfy().load().type(clazz);
    // If the entity is in the cross-TLD entity group, then we can take advantage of an ancestor
    // query to give us strong transactional consistency.
    if (clazz.isAnnotationPresent(InCrossTld.class)) {
      query = query.ancestor(getCrossTldKey());
    }
    return query;
  }

  private static class DatastoreQueryComposerImpl<T> extends QueryComposer<T> {

    DatastoreQueryComposerImpl(Class<T> entityClass) {
      super(entityClass);
    }

    Query<T> buildQuery() {
      checkOnlyOneInequalityField();
      Query<T> result = auditedOfy().load().type(entityClass);
      for (WhereClause pred : predicates) {
        String comparatorString = pred.comparator.getDatastoreString();
        if (comparatorString == null) {
          throw new UnsupportedOperationException(
              String.format("The %s operation is not supported on Datastore.", pred.comparator));
        }
        result = result.filter(pred.fieldName + comparatorString, pred.value);
      }

      if (orderBy != null) {
        result = result.order(orderBy);
      }

      return result;
    }

    @Override
    public Optional<T> first() {
      return Optional.ofNullable(buildQuery().limit(1).first().now());
    }

    @Override
    public T getSingleResult() {
      List<T> results = buildQuery().limit(2).list();
      if (results.size() == 0) {
        // The exception text here is the same as what we get for JPA queries.
        throw new NoResultException("No entity found for query");
      } else if (results.size() > 1) {
        throw new NonUniqueResultException("More than one result found for getSingleResult query");
      }
      return results.get(0);
    }

    @Override
    public Stream<T> stream() {
      return Streams.stream(buildQuery());
    }

    @Override
    public long count() {
      return buildQuery().count();
    }

    @Override
    public ImmutableList<T> list() {
      return ImmutableList.copyOf(buildQuery().list());
    }

    private void checkOnlyOneInequalityField() {
      // Datastore inequality queries are limited to one property, see
      // https://cloud.google.com/appengine/docs/standard/go111/datastore/query-restrictions#inequality_filters_are_limited_to_at_most_one_property
      long numInequalityFields =
          predicates.stream()
              .filter(pred -> !pred.comparator.equals(Comparator.EQ))
              .map(pred -> pred.fieldName)
              .distinct()
              .count();
      checkArgument(
          numInequalityFields <= 1,
          "Datastore cannot handle inequality queries on multiple fields, we found %s fields.",
          numInequalityFields);
    }
  }
}
