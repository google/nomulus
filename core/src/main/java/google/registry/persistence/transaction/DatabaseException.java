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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Wraps an exception thrown by the JPA framework and captures the SQL error details (state and
 * status code) in the {@link #getMessage message}.
 *
 * <p>The {@link SQLException} class has its own chain of exceptions that describe multiple error
 * conditions encontered during a transaction. A typical logger relying on the {@link
 * Throwable#getCause() chain of causes} in {@code Throwable} instances cannot capture all details
 * of errors thrown from the database drivers. This exception captures all error details in its
 * message text.
 *
 * <p>The {@link TransactionManager} wraps every persistence exception in an instance of this class.
 * This allows us to disable logging in specific Hibernate classes that logs at {@code ERROR}-level
 * and rethrows. These {@code ERROR} logs are misleading, since the impacted transactions often
 * succeeds on retries.
 *
 * <p>See the {@code logging.properties} files in the {@code env} package for the specific Hibernate
 * classes that have logging suppressed.
 */
public class DatabaseException extends PersistenceException {

  private transient String cachedMessage;

  @VisibleForTesting
  DatabaseException(Throwable throwable) {
    super(throwable);
  }

  @Override
  public String getMessage() {
    if (cachedMessage == null) {
      cachedMessage = getSqlError(getCause());
    }
    return cachedMessage;
  }

  /**
   * Throws an unchecked exception on behalf of the {@code original} {@link Throwable}.
   *
   * <p>If the {@code original Throwable} has at least one {@link SQLException} in its chain of
   * causes, a {@link DatabaseException} is thrown; otherwise this does nothing.
   */
  static void throwIfSqlException(Throwable original) {
    Throwable t = original;
    do {
      if (t instanceof SQLException) {
        throw new DatabaseException(original);
      }
    } while ((t = t.getCause()) != null);
  }

  /**
   * Returns the SQL state and error code of every {@link SQLException} in the chain of causes, or
   * empty string if such information is not found.
   */
  static String getSqlError(Throwable t) {
    ImmutableList.Builder<String> errMessageBuilder = new ImmutableList.Builder<>();
    do {
      if (t instanceof SQLException e) {
        getSqlExceptionDetails(e).ifPresent(errMessageBuilder::add);
      }
      t = t.getCause();
    } while (t != null);
    return Joiner.on("\n").join(errMessageBuilder.build());
  }

  static Optional<String> getSqlExceptionDetails(SQLException sqlException) {
    ImmutableList.Builder<String> errBuilder = new ImmutableList.Builder<>();

    while (sqlException != null) {
      if (sqlException.getErrorCode() > 0 || sqlException.getSQLState() != null) {
        errBuilder.add(
            "\tSQL Error: "
                + sqlException.getErrorCode()
                + ", SQLState: "
                + sqlException.getSQLState()
                + ", message: "
                + sqlException.getMessage()
                + ".");
      }
      sqlException = sqlException.getNextException();
    }
    ImmutableList<String> errors = errBuilder.build();
    if (errors.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(Joiner.on("\n").join(errors));
  }
}
