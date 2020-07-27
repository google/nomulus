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

package google.registry.persistence;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import java.sql.SQLException;
import java.util.function.Predicate;
import javax.persistence.OptimisticLockException;

/** Helpers for identifying retriable database operations. */
public final class JpaRetries {

  private JpaRetries() {}

  private static final ImmutableSet<String> RETRIABLE_TXN_SQL_STATE =
      ImmutableSet.of(
          "40001", // serialization_failure
          "40P01", // deadlock_detected, PSQL-specific
          "55006", // object_in_use, PSQL and DB2
          "55P03" // lock_not_available, PSQL-specific
          );

  private static final Predicate<Throwable> RETRIABLE_TXN_PREDICATE =
      Predicates.or(
          OptimisticLockException.class::isInstance,
          e ->
              e instanceof SQLException
                  && RETRIABLE_TXN_SQL_STATE.contains(((SQLException) e).getSQLState()));

  public static boolean isFailedTxnRetriable(Throwable throwable) {
    Throwable t = throwable;
    while (t != null) {
      if (RETRIABLE_TXN_PREDICATE.test(t)) {
        return true;
      }
      t = t.getCause();
    }
    return false;
  }

  public static boolean isFailedQueryRetriable(Throwable throwable) {
    // TODO(weiminyu): check for more error codes.
    return isFailedTxnRetriable(throwable);
  }
}
