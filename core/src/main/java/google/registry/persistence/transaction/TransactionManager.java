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
  void saveNew(Object entity);

  /** Persists all new entities in the database, throws exception if any entity already exists. */
  void saveAllNew(ImmutableCollection<?> entities);

  /** Persists a new entity or update the existing entity in the database. */
  void saveNewOrUpdate(Object entity);

  /** Persists all new entities or update the existing entities in the database. */
  void saveNewOrUpdateAll(ImmutableCollection<?> entities);

  /** Updates an entity in the database, throws exception if the entity does not exist. */
  void update(Object entity);

  /** Updates all entities in the database, throws exception if any entity does not exist. */
  void updateAll(ImmutableCollection<?> entities);

  /** Returns whether the given entity with same ID exists. */
  boolean checkExists(Object entity);

  /** Returns whether the entity of given key exists. */
  <T> boolean checkExists(VKey<T> key);

  /** Loads the entity by its id, returns empty if the entity doesn't exist. */
  <T> Optional<T> maybeLoad(VKey<T> key);

  /** Loads the entity by its id, throws NoSuchElementException if it doesn't exist. */
  <T> T load(VKey<T> key);

  /**
   * Loads the set of entities by their key id.
   *
   * @throws NoSuchElementException if any of the keys are not found.
   */
  <T> ImmutableMap<VKey<? extends T>, T> load(Iterable<? extends VKey<? extends T>> keys);

  /** Loads all entities of the given type, returns empty if there is no such entity. */
  <T> ImmutableList<T> loadAll(Class<T> clazz);

  /** Deletes the entity by its id. */
  void delete(VKey<?> key);

  /** Deletes the set of entities by their key id. */
  void delete(Iterable<? extends VKey<?>> keys);
}
