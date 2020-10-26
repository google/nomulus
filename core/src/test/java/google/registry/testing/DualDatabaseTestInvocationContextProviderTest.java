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

package google.registry.testing;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import google.registry.model.ofy.DatastoreTransactionManager;
import google.registry.persistence.transaction.JpaTransactionManager;
import google.registry.persistence.transaction.TransactionManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test to verify that {@link DualDatabaseTestInvocationContextProvider} extension executes tests
 * with corresponding {@link TransactionManager}.
 */
@DualDatabaseTest
public class DualDatabaseTestInvocationContextProviderTest {

  private static int testBothDbsOfyCounter = 0;
  private static int testBothDbsSqlCounter = 0;
  private static int testOfyOnlyOfyCounter = 0;
  private static int testOfyOnlySqlCounter = 0;
  private static int testSqlOnlyOfyCounter = 0;
  private static int testSqlOnlySqlCounter = 0;

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @TestOfyAndSql
  void testToVerifyBothOfyAndSqlTmAreUsed() {
    if (tm() instanceof DatastoreTransactionManager) {
      testBothDbsOfyCounter++;
    }
    if (tm() instanceof JpaTransactionManager) {
      testBothDbsSqlCounter++;
    }
  }

  @TestOfyOnly
  void testToVerifyOnlyOfyTmIsUsed() {
    if (tm() instanceof DatastoreTransactionManager) {
      testOfyOnlyOfyCounter++;
    }
    if (tm() instanceof JpaTransactionManager) {
      testOfyOnlySqlCounter++;
    }
  }

  @TestSqlOnly
  void testToVerifyOnlySqlTmIsUsed() {
    if (tm() instanceof DatastoreTransactionManager) {
      testSqlOnlyOfyCounter++;
    }
    if (tm() instanceof JpaTransactionManager) {
      testSqlOnlySqlCounter++;
    }
  }

  @AfterAll
  static void assertEachTransactionManagerIsUsed() {
    assertThat(testBothDbsOfyCounter).isEqualTo(1);
    assertThat(testBothDbsSqlCounter).isEqualTo(1);

    assertThat(testOfyOnlyOfyCounter).isEqualTo(1);
    assertThat(testOfyOnlySqlCounter).isEqualTo(0);

    assertThat(testSqlOnlyOfyCounter).isEqualTo(0);
    assertThat(testSqlOnlySqlCounter).isEqualTo(1);
  }
}
