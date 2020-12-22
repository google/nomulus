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
import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.persistence.VKey;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import org.joda.time.DateTime;

/**
 * This interface defines the methods to execute database operations with or without a transaction.
 */
public interface TransactionManager {

  /** Returns {@code true} if the caller is in a transaction.
   *
   *  <p>Note that this function is kept for backward compatibility. We will review the use case
   *  later when adding the cloud sql implementation.
   */
  boolean inTransaction();

  /** Throws {@link IllegalStateException} if the caller is not in a transaction.
   *
   *  <p>Note that this function is kept for backward compatibility. We will review the use case
   *  later when adding the cloud sql implementation.
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

  /** Pauses the current transaction (if any) and executes the work in a new transaction.
   *
   *  <p>Note that this function is kept for backward compatibility. We will review the use case
   *  later when adding the cloud sql implementation.
   */
  void transactNew(Runnable work);

  /**
   * Executes the work in a read-only transaction and returns the result.
   *
   * <p>Note that this function is kept for backward compatibility. We will review the use case
   * later when adding the cloud sql implementation.
   */
  <R> R transactNewReadOnly(Supplier<R> work);

  /** Executes the work in a read-only transaction.
   *
   *  <p>Note that this function is kept for backward compatibility. We will review the use case
   *  later when adding the cloud sql implementation.
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

  /**
   * Persists a new entity in the database without writing any backup if the underlying database is
   * Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void insertWithoutBackup(Object entity);

  /**
   * Persists all new entities in the database without writing any backup if the underlying database
   * is Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void insertAllWithoutBackup(ImmutableCollection<?> entities);

  /** Persists a new entity or update the existing entity in the database. */
  void put(Object entity);

  /** Persists all new entities or update the existing entities in the database. */
  void putAll(ImmutableCollection<?> entities);

  /**
   * Persists a new entity or update the existing entity in the database without writing any backup
   * if the underlying database is Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void putWithoutBackup(Object entity);

  /**
   * Persists all new entities or update the existing entities in the database without writing any
   * backup if the underlying database is Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void putAllWithoutBackup(ImmutableCollection<?> entities);

  /** Updates an entity in the database, throws exception if the entity does not exist. */
  void update(Object entity);

  /** Updates all entities in the database, throws exception if any entity does not exist. */
  void updateAll(ImmutableCollection<?> entities);

  /**
   * Updates an entity in the database without writing any backup if the underlying database is
   * Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void updateWithoutBackup(Object entity);

  /**
   * Updates all entities in the database without writing any backup if the underlying database is
   * Datastore.
   *
   * <p>This method is for the sake of keeping a single code path when replacing ofy() with tm() in
   * the application code. When the method is invoked with Datastore, it won't write the commit log
   * backup; when invoked with Cloud SQL, it behaves the same as the method which doesn't have
   * "WithoutBackup" in its method name because it is not necessary to have the backup mechanism in
   * SQL.
   */
  void updateAllWithoutBackup(ImmutableCollection<?> entities);

  /** Returns whether the given entity with same ID exists. */
  boolean exists(Object entity);

  /** Returns whether the entity of given key exists. */
  <T> boolean exists(VKey<T> key);

  /** Loads the entity by its id, returns empty if the entity doesn't exist. */
  <T> Optional<T> maybeLoad(VKey<T> key);

  /** Loads the entity by its id, throws NoSuchElementException if it doesn't exist. */
  <T> T load(VKey<T> key);

  /**
   * Loads the given entity from the database, throws NoSuchElementException if it doesn't exist.
   */
  <T> T load(T entity);

  /**
   * Loads the set of entities by their key id.
   *
   * @throws NoSuchElementException if any of the keys are not found.
   */
  <T> ImmutableMap<VKey<? extends T>, T> load(Iterable<? extends VKey<? extends T>> keys);

  /** Loads the set of entities by their key id, nonexistent entiites are ignored. */
  <T> ImmutableMap<VKey<? extends T>, T> loadExisting(Iterable<? extends VKey<? extends T>> keys);

  /** Loads all entities of the given type, returns empty if there is no such entity. */
  <T> ImmutableList<T> loadAll(Class<T> clazz);

  /**
   * Loads all given entities from the database, throws NoSuchElementException if it doesn't exist.
   */
  <T> ImmutableList<T> loadAll(Iterable<T> entities);

  /** Loads all given entities from the database if possible, nonexistent entiites are ignored. */
  <T> ImmutableList<T> loadAllExisting(Iterable<T> entities);

  /** Deletes the entity by its id. */
  void delete(VKey<?> key);

  /** Deletes the set of entities by their key id. */
  void delete(Iterable<? extends VKey<?>> keys);

  /** Deletes the given entity from the database. */
  void delete(Object entity);

  /**
   * Deletes the entity by its id without writing any backup if the underlying database is
   * Datastore.
   */
  void deleteWithoutBackup(VKey<?> key);

  /**
   * Deletes the set of entities by their key id without writing any backup if the underlying
   * database is Datastore.
   */
  void deleteWithoutBackup(Iterable<? extends VKey<?>> keys);

  /**
   * Deletes the given entity from the database without writing any backup if the underlying
   * database is Datastore.
   */
  void deleteWithoutBackup(Object entity);

  /** Clears the session cache if the underlying database is Datastore, otherwise it is a no-op. */
  void clearSessionCache();

  /** Returns true if the transaction manager is DatastoreTransactionManager. */
  default boolean isOfy() {
    return this instanceof DatastoreTransactionManager;
  }
}
