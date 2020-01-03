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

package google.registry.model.transaction;

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
}
