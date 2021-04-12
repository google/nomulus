// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.ofyTm;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumListDatastoreDao;
import google.registry.schema.tld.PremiumListSqlDao;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ComparePremiumListsCommandTest extends CommandTestCase<ComparePremiumListsCommand> {

  @BeforeEach
  void beforeEach() {
    persistPremiumList("xn--q9jyb4c", "rich,USD 100");
    persistPremiumList("how", "richer,JPY 10000");
  }

  @Test
  void test_success() throws Exception {
    runCommand();
    assertThat(getStdoutAsString()).isEqualTo("Found 0 unequal list(s).\n");
  }

  @Test
  void test_listMissingFromCloudSql() throws Exception {
    jpaTm()
        .transact(
            () -> {
              PremiumList premiumList = PremiumListSqlDao.getLatestRevision("how").get();
              PremiumListSqlDao.delete(premiumList);
            });
    runCommand();
    assertThat(getStdoutAsString())
        .isEqualTo(
            "PremiumList 'how' is present in Datastore, but not in Cloud SQL.\n"
                + "Found 1 unequal list(s).\n");
  }

  @Test
  void test_listMissingFromDatastore() throws Exception {
    PremiumList premiumList = PremiumListDatastoreDao.getLatestRevision("how").get();
    ofyTm().transact(() -> ofyTm().delete(premiumList));
    runCommand();
    assertThat(getStdoutAsString())
        .isEqualTo(
            "PremiumList 'how' is present in Cloud SQL, but not in Datastore.\n"
                + "Found 1 unequal list(s).\n");
  }

  @Test
  void test_listsDiffer() throws Exception {
    PremiumListSqlDao.save(
        new PremiumList.Builder()
            .setName("how")
            .setCurrency(USD)
            .setLabelsToPrices(ImmutableMap.of("silver", BigDecimal.valueOf(30.03)))
            .setCreationTime(fakeClock.nowUtc())
            .build());
    runCommand();
    assertThat(getStdoutAsString())
        .isEqualTo(
            "PremiumList 'how' has different entries in each database.\n"
                + "Found 1 unequal list(s).\n");
  }
}
