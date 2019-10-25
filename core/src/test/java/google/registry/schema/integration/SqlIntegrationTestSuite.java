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

import google.registry.model.registry.RegistryLockDaoTest;
import google.registry.model.tmch.ClaimsListDaoTest;
import google.registry.model.transaction.JpaTransactionManagerImplTest;
import google.registry.model.transaction.JpaTransactionManagerRuleTest;
import google.registry.persistence.BloomFilterConverterTest;
import google.registry.persistence.CreateAutoTimestampConverterTest;
import google.registry.persistence.UpdateAutoTimestampConverterTest;
import google.registry.persistence.ZonedDateTimeConverterTest;
import google.registry.schema.tld.PremiumListDaoTest;
import google.registry.ui.server.registrar.RegistryLockVerifyActionTest;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

/**
 * Groups all tests that may depends on Cloud SQL schema. They will be run for server-schema
 * compatibility check.
 *
 * <p>Schema dependency is approximated by the use of {@link
 * google.registry.model.transaction.JpaTransactionManagerRule}.
 *
 * @see SqlIntegrationMembershipTest
 */
// TODO(weiminyu): refactor JpaTransactionManagerRule to eliminate false positives.
@RunWith(Suite.class)
@SuiteClasses({
  BloomFilterConverterTest.class,
  ClaimsListDaoTest.class,
  CreateAutoTimestampConverterTest.class,
  JpaTransactionManagerImplTest.class,
  JpaTransactionManagerRuleTest.class,
  PremiumListDaoTest.class,
  RegistryLockDaoTest.class,
  RegistryLockVerifyActionTest.class,
  UpdateAutoTimestampConverterTest.class,
  ZonedDateTimeConverterTest.class
})
public class SqlIntegrationTestSuite {}
