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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.DatabaseException.getSqlError;
import static google.registry.persistence.transaction.DatabaseException.getSqlExceptionDetails;
import static google.registry.persistence.transaction.DatabaseException.throwIfSqlException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DatabaseException}. */
public class DatabaseExceptionTest {

  @Test
  void getSqlError_singleNonSqlException() {
    assertThat(getSqlError(new Exception())).isEmpty();
  }

  @Test
  void getSqlExceptionDetails_chainedNoSqlExceptions() {
    assertThat(getSqlError(new Exception(new Exception()))).isEmpty();
  }

  @Test
  void getSqlError_sqlExceptionNoDetails() {
    assertThat(getSqlError(new java.sql.SQLException())).isEmpty();
  }

  @Test
  void getSqlError_sqlExceptionWithSqlState() {
    assertThat(getSqlError(new java.sql.SQLException("msg", "state")))
        .contains("\tSQL Error: 0, SQLState: state, message: msg.");
  }

  @Test
  void getSqlError_sqlExceptionWithAllDetails() {
    assertThat(getSqlError(new java.sql.SQLException("msg", "state", 1)))
        .contains("\tSQL Error: 1, SQLState: state, message: msg.");
  }

  @Test
  void getSqlError_chainedSqlExceptionWithAllDetails() {
    SQLException sqlException = new java.sql.SQLException("msg", "state", 1);
    assertThat(getSqlError(new Exception("not-captured", sqlException)))
        .contains("\tSQL Error: 1, SQLState: state, message: msg.");
  }

  @Test
  void getSqlError_multipleChainedSqlExceptionWithAllDetails() {
    SQLException lower = new java.sql.SQLException("lower", "lower-state", 1);
    SQLException higher =
        new java.sql.SQLException("higher", "higher-state", 2, new Exception(lower));
    assertThat(getSqlError(new Exception(higher)))
        .contains(
            "\tSQL Error: 2, SQLState: higher-state, message: higher.\n"
                + "\tSQL Error: 1, SQLState: lower-state, message: lower.");
  }

  @Test
  void getSqlExceptionDetails_singleNoDetails() {
    assertThat(getSqlExceptionDetails(new SQLException())).isEmpty();
  }

  @Test
  void getSqlExceptionDetails_singleWithDetails() {
    assertThat(getSqlExceptionDetails(new SQLException("msg", "state", 1)))
        .hasValue("\tSQL Error: 1, SQLState: state, message: msg.");
  }

  @Test
  void getSqlExceptionDetails_multipleWithDetails() {
    SQLException first = new SQLException("msg", "state", 1);
    first.setNextException(new SQLException("msg2", "state2", 2));
    assertThat(getSqlExceptionDetails(first))
        .hasValue(
            "\tSQL Error: 1, SQLState: state, message: msg.\n"
                + "\tSQL Error: 2, SQLState: state2, message: msg2.");
  }

  @Test
  void tryWrapAndThrow_notSQLException() {
    RuntimeException orig = new RuntimeException(new Exception());
    throwIfSqlException(orig);
  }

  @Test
  void tryWrapAndThrow_hasSQLException() {
    Throwable orig = new Throwable(new SQLException());
    assertThrows(DatabaseException.class, () -> throwIfSqlException(orig));
  }

  @Test
  @SuppressWarnings("ReturnValueIgnored")
  void getMessage_cachedMessageReused() {
    SQLException sqlException = mock(SQLException.class);
    DatabaseException databaseException = new DatabaseException(sqlException);
    databaseException.getMessage();
    databaseException.getMessage();
    verify(sqlException, times(1)).getCause();
  }
}
