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

package google.registry.bsa;

import static com.google.common.base.Verify.verify;
import static google.registry.persistence.PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import google.registry.persistence.transaction.TransactionManager.ThrowingRunnable;
import java.util.concurrent.Callable;

/**
 * Helpers for executing JPA transactions for BSA processing.
 *
 * <p>All mutating transactions for BSA are executed at the {@code TRANSACTION_REPEATABLE_READ}
 * level since the global {@link BsaLock} ensures there is a single writer at any time.
 *
 * <p>All domain and label queries can use the replica since all processing are snapshot based.
 */
public final class BsaTransactions {

  @CanIgnoreReturnValue
  public static <T> T bsaTransact(Callable<T> work) {
    verify(!tm().inTransaction(), "May only be used for top-level transactions.");
    return tm().transact(TRANSACTION_REPEATABLE_READ, work);
  }

  public static void bsaTransact(ThrowingRunnable work) {
    verify(!tm().inTransaction(), "May only be used for top-level transactions.");
    tm().transact(TRANSACTION_REPEATABLE_READ, work);
  }

  @CanIgnoreReturnValue
  public static <T> T bsaQuery(Callable<T> work) {
    verify(!tm().inTransaction(), "May only be used for top-level transactions.");
    // TRANSACTION_REPEATABLE_READ is default on replica.
    return replicaTm().transact(work);
  }

  private BsaTransactions() {}
}
