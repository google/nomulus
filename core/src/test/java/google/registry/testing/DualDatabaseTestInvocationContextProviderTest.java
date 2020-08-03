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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Test to verify that {@link DualDatabaseTestInvocationContextProvider} extension executes {@link
 * TestTemplate} test twice with different databases.
 */
@DualDatabaseTest
public class DualDatabaseTestInvocationContextProviderTest {

  private static int datastoreTestCounter = 0;
  private static int postgresqlTestCounter = 0;

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @TestTemplate
  void testToUseTransactionManager() {
    if (tm() instanceof DatastoreTransactionManager) {
      datastoreTestCounter++;
    }
    if (tm() instanceof JpaTransactionManager) {
      postgresqlTestCounter++;
    }
  }

  @AfterAll
  static void assertEachTransactionManagerIsUsed() {
    assertThat(datastoreTestCounter).isEqualTo(1);
    assertThat(postgresqlTestCounter).isEqualTo(1);
  }
}
