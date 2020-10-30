// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.schema.integration;

import static com.google.common.truth.Truth.assert_;

import google.registry.model.billing.BillingEventTest;
import google.registry.model.contact.ContactResourceTest;
import google.registry.model.domain.DomainBaseSqlTest;
import google.registry.model.domain.token.AllocationTokenTest;
import google.registry.model.history.ContactHistoryTest;
import google.registry.model.history.DomainHistoryTest;
import google.registry.model.history.HostHistoryTest;
import google.registry.model.poll.PollMessageTest;
import google.registry.model.rde.RdeRevisionTest;
import google.registry.model.registry.RegistryLockDaoTest;
import google.registry.model.registry.RegistryTest;
import google.registry.model.registry.label.ReservedListSqlDaoTest;
import google.registry.model.reporting.Spec11ThreatMatchTest;
import google.registry.model.server.KmsSecretRevisionSqlDaoTest;
import google.registry.model.smd.SignedMarkRevocationListDaoTest;
import google.registry.model.tmch.ClaimsListDaoTest;
import google.registry.persistence.transaction.JpaEntityCoverageExtension;
import google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageExtension;
import google.registry.schema.cursor.CursorDaoTest;
import google.registry.schema.integration.SqlIntegrationTestSuite.AfterSuiteTest;
import google.registry.schema.integration.SqlIntegrationTestSuite.BeforeSuiteTest;
import google.registry.schema.registrar.RegistrarDaoTest;
import google.registry.schema.server.LockDaoTest;
import google.registry.schema.tld.PremiumListDaoTest;
import google.registry.testing.AppEngineExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.runner.RunWith;

/**
 * Groups all JPA entity tests in one suite for easy invocation. This suite is used for
 * server/schema compatibility tests between releases.
 *
 * <p>Suite members are typically DAO tests, which perform simple create/update/delete operations on
 * JPA entities. Each member class must call {@link
 * AppEngineExtension.Builder#enableJpaEntityCoverageCheck} and have at least one test method that
 * persists a JPA entity declared in persistence.xml.
 *
 * <p>Note that with {@link JpaIntegrationWithCoverageExtension}, each method starts with an empty
 * database. Therefore this is not the right place for verifying backwards data compatibility in
 * end-to-end functional tests.
 *
 * <p>As of April 2020, none of the before/after annotations ({@code BeforeClass} and {@code
 * AfterClass} in JUnit 4, or {@code BeforeAll} and {@code AfterAll} in JUnit5) work in a test suite
 * run with {@link JUnitPlatform the current JUnit 5 runner}. However, staying with the JUnit 4
 * runner would prevent any member tests from migrating to JUnit 5.
 *
 * <p>This class uses a hack to work with the current JUnit 5 runner. {@link BeforeSuiteTest} is
 * added to the front of the suite class list and invokes the suite's setup method, and {@link
 * AfterSuiteTest} is added to the tail of the suite class list and invokes the suite's teardown
 * method. This works because the member tests are run in the order they are declared (See {@code
 * org.junit.platform.engine.support.descriptor.AbstractTestDescriptor#addChild}). Should the
 * ordering changes in the future, we will only get false alarms.
 */
@RunWith(JUnitPlatform.class)
@SelectClasses({
  // BeforeSuiteTest must be the first entry. See class javadoc for details.
  BeforeSuiteTest.class,
  AllocationTokenTest.class,
  BillingEventTest.class,
  ClaimsListDaoTest.class,
  ContactHistoryTest.class,
  ContactResourceTest.class,
  CursorDaoTest.class,
  DomainBaseSqlTest.class,
  DomainHistoryTest.class,
  HostHistoryTest.class,
  KmsSecretRevisionSqlDaoTest.class,
  LockDaoTest.class,
  PollMessageTest.class,
  PremiumListDaoTest.class,
  RdeRevisionTest.class,
  RegistrarDaoTest.class,
  RegistryTest.class,
  ReservedListSqlDaoTest.class,
  RegistryLockDaoTest.class,
  SignedMarkRevocationListDaoTest.class,
  Spec11ThreatMatchTest.class,
  // AfterSuiteTest must be the last entry. See class javadoc for details.
  AfterSuiteTest.class
})
public class SqlIntegrationTestSuite {

  @BeforeAll // Not yet supported in JUnit 5. Called through BeforeSuiteTest.
  public static void initJpaEntityCoverage() {
    JpaEntityCoverageExtension.init();
  }

  @AfterAll // Not yet supported in JUnit 5. Called through AfterSuiteTest.
  public static void checkJpaEntityCoverage() {
    // TODO(weiminyu): collect both assertion errors like Truth's Expect does in JUnit 4.
    assert_()
        .withMessage("Tests are missing for the following JPA entities:")
        .that(JpaEntityCoverageExtension.getUncoveredEntities())
        .isEmpty();
    assert_()
        .withMessage(
            "The following classes do not test JPA entities. Please remove them from this suite")
        .that(JpaEntityCoverageExtension.getIrrelevantTestClasses())
        .isEmpty();
  }

  /**
   * Hack for calling {@link SqlIntegrationTestSuite#initJpaEntityCoverage()} before all real tests
   * in suite. See outer class javadoc for details.
   *
   * <p>The 'Test' suffix in class name is required.
   */
  static class BeforeSuiteTest {

    @Test
    void beforeAll() {
      initJpaEntityCoverage();
    }
  }

  /**
   * Hack for invoking {@link SqlIntegrationTestSuite#checkJpaEntityCoverage()} after all real tests
   * in suite. See outer class javadoc for details.
   *
   * <p>The 'Test' suffix in class name is required.
   */
  static class AfterSuiteTest {

    @Test
    void afterSuite() {
      checkJpaEntityCoverage();
    }
  }
}
