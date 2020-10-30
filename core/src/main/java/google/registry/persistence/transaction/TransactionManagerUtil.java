// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.model.ofy.DatastoreTransactionManager;
import java.util.function.Supplier;

/** Utility class that provides supplementary methods for {@link TransactionManager}. */
public class TransactionManagerUtil {

  /**
   * Returns the result of the given {@link Supplier}.
   *
   * <p>If {@link TransactionManagerFactory#tm()} returns a {@link JpaTransactionManager} instance,
   * the {@link Supplier} is executed in a transaction.
   */
  public static <T> T transactIfJpaTm(Supplier<T> supplier) {
    if (tm() instanceof JpaTransactionManager) {
      return tm().transact(supplier);
    } else {
      return supplier.get();
    }
  }

  /**
   * Executes the given {@link Runnable}.
   *
   * <p>If {@link TransactionManagerFactory#tm()} returns a {@link JpaTransactionManager} instance,
   * the {@link Runnable} is executed in a transaction.
   */
  public static void transactIfJpaTm(Runnable runnable) {
    transactIfJpaTm(
        () -> {
          runnable.run();
          return null;
        });
  }

  /**
   * Executes either {@code ofyRunnable} if {@link TransactionManagerFactory#tm()} returns a {@link
   * JpaTransactionManager} instance, or {@code jpaRunnable} if {@link
   * TransactionManagerFactory#tm()} returns a {@link DatastoreTransactionManager} instance.
   */
  public static void ofyOrJpaTm(Runnable ofyRunnable, Runnable jpaRunnable) {
    ofyOrJpaTm(
        () -> {
          ofyRunnable.run();
          return null;
        },
        () -> {
          jpaRunnable.run();
          return null;
        });
  }

  /**
   * Returns the result from either {@code ofySupplier} if {@link TransactionManagerFactory#tm()}
   * returns a {@link JpaTransactionManager} instance, or {@code jpaSupplier} if {@link
   * TransactionManagerFactory#tm()} returns a {@link DatastoreTransactionManager} instance.
   */
  public static <T> T ofyOrJpaTm(Supplier<T> ofySupplier, Supplier<T> jpaSupplier) {
    if (tm() instanceof DatastoreTransactionManager) {
      return ofySupplier.get();
    } else if (tm() instanceof JpaTransactionManager) {
      return jpaSupplier.get();
    } else {
      throw new IllegalStateException(
          "Expected tm() to be DatastoreTransactionManager or JpaTransactionManager, but got "
              + tm().getClass());
    }
  }

  /**
   * Executes the given {@link Runnable} if {@link TransactionManagerFactory#tm()} returns a {@link
   * DatastoreTransactionManager} instance, otherwise does nothing.
   */
  public static void ofyTmOrDoNothing(Runnable ofyRunnable) {
    if (tm() instanceof DatastoreTransactionManager) {
      ofyRunnable.run();
    }
  }

  /**
   * Returns the result from the given {@link Supplier} if {@link TransactionManagerFactory#tm()}
   * returns a {@link DatastoreTransactionManager} instance, otherwise returns null.
   */
  public static <T> T ofyTmOrDoNothing(Supplier<T> ofySupplier) {
    if (tm() instanceof DatastoreTransactionManager) {
      return ofySupplier.get();
    } else {
      return null;
    }
  }

  private TransactionManagerUtil() {}
}
