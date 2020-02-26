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

import com.google.common.truth.Expect;
import google.registry.batch.RelockDomainActionTest;
import google.registry.model.common.CursorTest;
import google.registry.model.domain.DomainBaseSqlTest;
import google.registry.model.registry.RegistryLockDaoTest;
import google.registry.persistence.transaction.JpaEntityCoverage;
import google.registry.schema.cursor.CursorDaoTest;
import google.registry.schema.registrar.RegistrarDaoTest;
import google.registry.schema.server.LockDaoTest;
import google.registry.schema.tld.PremiumListDaoTest;
import google.registry.schema.tld.ReservedListDaoTest;
import google.registry.schema.tmch.ClaimsListDaoTest;
import google.registry.tools.CreateRegistrarCommandTest;
import google.registry.tools.CreateReservedListCommandTest;
import google.registry.tools.DomainLockUtilsTest;
import google.registry.tools.LockDomainCommandTest;
import google.registry.tools.UnlockDomainCommandTest;
import google.registry.tools.UpdateRegistrarCommandTest;
import google.registry.tools.UpdateReservedListCommandTest;
import google.registry.tools.javascrap.BackfillRegistryLocksCommandTest;
import google.registry.tools.server.CreatePremiumListActionTest;
import google.registry.tools.server.UpdatePremiumListActionTest;
import google.registry.ui.server.registrar.RegistryLockGetActionTest;
import google.registry.ui.server.registrar.RegistryLockVerifyActionTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/**
 * Groups all JPA entity tests in one suite for easy invocation. This suite is used for
 * server/schema compatibility tests between releases.
 *
 * <p>Every member class must use the {@link
 * google.registry.persistence.transaction.JpaTestRules.JpaIntegrationWithCoverageRule} and have at
 * least one test method that persists a JPA entity declared in persistence.xml.
 *
 * <p>Membership of this suite is monitored by the checks in {@link #checkJpaEntityCoverage()} and
 * {@link SqlIntegrationMembershipTest#sqlIntegrationMembershipComplete()}.
 */
@RunWith(Suite.class)
@SuiteClasses({
  BackfillRegistryLocksCommandTest.class,
  ClaimsListDaoTest.class,
  CreatePremiumListActionTest.class,
  CreateRegistrarCommandTest.class,
  CreateReservedListCommandTest.class,
  CursorDaoTest.class,
  CursorTest.class,
  DomainLockUtilsTest.class,
  LockDaoTest.class,
  LockDomainCommandTest.class,
  DomainBaseSqlTest.class,
  PremiumListDaoTest.class,
  RegistrarDaoTest.class,
  RegistryLockDaoTest.class,
  RegistryLockGetActionTest.class,
  RegistryLockVerifyActionTest.class,
  RelockDomainActionTest.class,
  ReservedListDaoTest.class,
  UnlockDomainCommandTest.class,
  UpdatePremiumListActionTest.class,
  UpdateRegistrarCommandTest.class,
  UpdateReservedListCommandTest.class
})
public class SqlIntegrationTestSuite {

  @ClassRule public static final Expect expect = Expect.create();

  @BeforeClass
  public static void initJpaEntityCoverage() {
    JpaEntityCoverage.init();
  }

  @AfterClass
  public static void checkJpaEntityCoverage() {
    expect
        .withMessage("Tests are missing for the following JPA entities:")
        .that(JpaEntityCoverage.getUncoveredEntities())
        .isEmpty();
    expect
        .withMessage(
            "The following classes do not test JPA entities. Please remove them from this suite")
        .that(JpaEntityCoverage.getIrrelevantTestClasses())
        .isEmpty();
  }
}
