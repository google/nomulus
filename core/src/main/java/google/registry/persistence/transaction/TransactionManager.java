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

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.joda.time.DateTime;

/**
 * This interface defines the methods to execute database operations with or without a transaction.
 */
public interface TransactionManager {

  /**
   * Returns {@code true} if the caller is in a transaction.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  boolean inTransaction();

  /**
   * Throws {@link IllegalStateException} if the caller is not in a transaction.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  void assertInTransaction();

  /** Executes the work in a transaction and returns the result. */
  <T> T transact(Supplier<T> work);

  /** Executes the work in a transaction. */
  void transact(Runnable work);

  /**
   * Pauses the current transaction (if any), executes the work in a new transaction and returns the
   * result.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  <T> T transactNew(Supplier<T> work);

  /**
   * Pauses the current transaction (if any) and executes the work in a new transaction.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  void transactNew(Runnable work);

  /**
   * Executes the work in a read-only transaction and returns the result.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  <R> R transactNewReadOnly(Supplier<R> work);

  /**
   * Executes the work in a read-only transaction.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  void transactNewReadOnly(Runnable work);

  /** Executes the work in a transactionless context. */
  <R> R doTransactionless(Supplier<R> work);

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

  /** Clears the session cache if the underlying database is Datastore, otherwise it is a no-op. */
  void clearSessionCache();

  /** Returns true if the transaction manager is DatastoreTransactionManager, false otherwise. */
  boolean isOfy();
}
