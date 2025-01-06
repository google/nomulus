// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import google.registry.persistence.PersistenceModule.TransactionIsolationLevel;
import google.registry.persistence.VKey;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/**
 * This interface defines the methods to execute database operations with or without a transaction.
 */
public interface TransactionManager {

  /**
   * Returns {@code true} if the caller is in a transaction.
   *
   * <p>Note that in the current implementation the entity manager is obtained from a static {@code
   * ThreadLocal} object that is set up by the outermost {@link #transact} call. Nested call sites
   * have no control over which database instance to use.
   */
  boolean inTransaction();

  /**
   * Throws {@link IllegalStateException} if the caller is not in a transaction.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  void assertInTransaction();

  /**
   * Returns a {@link long} value that can be used as {@code id} by a JPA model entity.
   *
   * <p>The returned value must be project-wide unique when transacting on the primary database
   * instance, but only needs to be unique within a JVM instance when transacting on the replica
   * instance.
   */
  long allocateId();

  /** Executes the work in a transaction and returns the result. */
  <T> T transact(Callable<T> work);

  /**
   * Executes the work in a transaction at the given {@link TransactionIsolationLevel} and returns
   * the result.
   */
  <T> T transact(TransactionIsolationLevel isolationLevel, Callable<T> work);

  /**
   * Executes the work in a transaction and returns the result, without retrying upon retryable
   * exceptions.
   *
   * <p>This method should only be used when the transaction contains side effects that are not
   * rolled back by the transaction manager, for example in {@link
   * google.registry.beam.common.RegistryJpaIO} where the results from a query are streamed to the
   * next transformation inside a transaction, as the result stream has to materialize to a list
   * outside a transaction and doing so would greatly affect the parallelism of the pipeline.
   */
  <T> T transactNoRetry(Callable<T> work);

  /**
   * Executes the work in a transaction at the given {@link TransactionIsolationLevel} and returns
   * the result, without retrying upon retryable exceptions.
   *
   * <p>This method should only be used when the transaction contains side effects that are not
   * rolled back by the transaction manager, for example in {@link
   * google.registry.beam.common.RegistryJpaIO} where the results from a query are streamed to the
   * next transformation inside a transaction, as the result stream has to materialize to a list
   * outside a transaction and doing so would greatly affect the parallelism of the pipeline.
   */
  <T> T transactNoRetry(TransactionIsolationLevel isolationLevel, Callable<T> work);

  /**
   * Executes the work in a (potentially wrapped) transaction and returns the result.
   *
   * <p>Calls to this method are typically going to be in inner functions, that are called either as
   * top-level transactions themselves or are nested inside larger transactions (e.g. a
   * transactional flow). Invocations of reTransact must be vetted to occur in both situations and
   * with such complexity that it is not trivial to refactor out the nested transaction calls. New
   * code should be written in such a way as to avoid requiring reTransact in the first place.
   *
   * <p>In the future we will be enforcing that {@link #transact(Callable)} calls be top-level only,
   * with reTransact calls being the only ones that can potentially be an inner nested transaction
   * (which is a noop). Note that, as this can be a nested inner exception, there is no overload
   * provided to specify a (potentially conflicting) transaction isolation level.
   */
  <T> T reTransact(Callable<T> work);

  /** Executes the work in a transaction. */
  void transact(ThrowingRunnable work);

  /** Executes the work in a transaction at the given {@link TransactionIsolationLevel}. */
  void transact(TransactionIsolationLevel isolationLevel, ThrowingRunnable work);

  /**
   * Executes the work in a (potentially wrapped) transaction and returns the result.
   *
   * <p>Calls to this method are typically going to be in inner functions, that are called either as
   * top-level transactions themselves or are nested inside larger transactions (e.g. a
   * transactional flow). Invocations of reTransact must be vetted to occur in both situations and
   * with such complexity that it is not trivial to refactor out the nested transaction calls. New
   * code should be written in such a way as to avoid requiring reTransact in the first place.
   *
   * <p>In the future we will be enforcing that {@link #transact(ThrowingRunnable)} calls be
   * top-level only, with reTransact calls being the only ones that can potentially be an inner
   * nested transaction (which is a noop). Note that, as this can be a nested inner exception, there
   * is no overload provided to specify a (potentially conflicting) transaction isolation level.
   */
  void reTransact(ThrowingRunnable work);

  /** Returns the time associated with the start of this particular transaction attempt. */
  DateTime getTransactionTime();

  /** Persists a new entity in the database, throws exception if the entity already exists. */
  void insert(Object entity);

  /** Persists all new entities in the database, throws exception if any entity already exists. */
  void insertAll(ImmutableCollection<?> entities);

  /** Persists all new entities in the database, throws exception if any entity already exists. */
  void insertAll(ImmutableObject... entities);

  /** Persists a new entity or update the existing entity in the database. */
  void put(Object entity);

  /** Persists all new entities or updates the existing entities in the database. */
  void putAll(ImmutableObject... entities);

  /** Persists all new entities or updates the existing entities in the database. */
  void putAll(ImmutableCollection<?> entities);

  /** Updates an entity in the database, throws exception if the entity does not exist. */
  void update(Object entity);

  /** Updates all entities in the database, throws exception if any entity does not exist. */
  void updateAll(ImmutableCollection<?> entities);

  /** Updates all entities in the database, throws exception if any entity does not exist. */
  void updateAll(ImmutableObject... entities);

  /** Returns whether the given entity with same ID exists. */
  boolean exists(Object entity);

  /** Returns whether the entity of given key exists. */
  <T> boolean exists(VKey<T> key);

  /** Loads the entity by its key, returns empty if the entity doesn't exist. */
  <T> Optional<T> loadByKeyIfPresent(VKey<T> key);

  /**
   * Loads the set of entities by their keys.
   *
   * <p>Nonexistent keys / entities are absent from the resulting map, but no {@link
   * NoSuchElementException} will be thrown.
   */
  <T> ImmutableMap<VKey<? extends T>, T> loadByKeysIfPresent(
      Iterable<? extends VKey<? extends T>> keys);

  /**
   * Loads all given entities from the database if possible.
   *
   * <p>Nonexistent entities are absent from the resulting list, but no {@link
   * NoSuchElementException} will be thrown.
   */
  <T> ImmutableList<T> loadByEntitiesIfPresent(Iterable<T> entities);

  /**
   * Loads the entity by its key.
   *
   * @throws NoSuchElementException if this key does not correspond to an existing entity.
   */
  <T> T loadByKey(VKey<T> key);

  /**
   * Loads the set of entities by their keys.
   *
   * @throws NoSuchElementException if any of the keys do not correspond to an existing entity.
   */
  <T> ImmutableMap<VKey<? extends T>, T> loadByKeys(Iterable<? extends VKey<? extends T>> keys);

  /**
   * Loads the given entity from the database.
   *
   * @throws NoSuchElementException if the entity does not exist in the database.
   */
  <T> T loadByEntity(T entity);

  /**
   * Loads all given entities from the database.
   *
   * @throws NoSuchElementException if any of the entities do not exist in the database.
   */
  <T> ImmutableList<T> loadByEntities(Iterable<T> entities);

  /**
   * Returns a list of all entities of the given type that exist in the database.
   *
   * <p>The resulting list is empty if there are no entities of this type.
   */
  <T> ImmutableList<T> loadAllOf(Class<T> clazz);

  /**
   * Returns a stream of all entities of the given type that exist in the database.
   *
   * <p>The resulting stream is empty if there are no entities of this type.
   */
  <T> Stream<T> loadAllOfStream(Class<T> clazz);

  /**
   * Loads the only instance of this particular class, or empty if none exists.
   *
   * <p>Throws an exception if there is more than one element in the table.
   */
  <T> Optional<T> loadSingleton(Class<T> clazz);

  /** Deletes the entity by its id. */
  void delete(VKey<?> key);

  /** Deletes the set of entities by their key id. */
  void delete(Iterable<? extends VKey<?>> keys);

  /**
   * Deletes the given entity from the database.
   *
   * <p>This returns the deleted entity, which may not necessarily be the same as the original
   * entity passed in, as it may be a) converted to a different type of object more appropriate to
   * the database type or b) merged with an object managed by the database entity manager.
   */
  <T> T delete(T entity);

  /** Returns a QueryComposer which can be used to perform queries against the current database. */
  <T> QueryComposer<T> createQueryComposer(Class<T> entity);

  /**
   * A runnable that allows for checked exceptions to be thrown.
   *
   * <p>This makes it easier to write lambdas without having to worry about wrapping and re-throwing
   * checked excpetions as unchecked ones.
   */
  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }
}
