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

package google.registry.schema.tld;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.transaction.JpaTransactionManagerRule;
import java.math.BigDecimal;
import org.joda.money.CurrencyUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PremiumListDao}. */
@RunWith(JUnit4.class)
public class PremiumListDaoTest {

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder().build();

  private static final ImmutableMap<String, BigDecimal> TEST_PRICES =
      ImmutableMap.of(
          "silver",
          BigDecimal.valueOf(10.23),
          "gold",
          BigDecimal.valueOf(1305.47),
          "palladium",
          BigDecimal.valueOf(1552.78));

  @Test
  public void saveNew_worksSuccessfully() {
    PremiumList premiumList = PremiumList.create("testname", CurrencyUnit.USD, TEST_PRICES);
    PremiumListDao.saveNew(premiumList);
    jpaTm()
        .transact(
            () -> {
              PremiumList persistedList =
                  jpaTm()
                      .getEntityManager()
                      .createQuery(
                          "SELECT pl FROM PremiumList pl WHERE pl.name = :name", PremiumList.class)
                      .setParameter("name", "testname")
                      .getSingleResult();
              assertThat(persistedList.getLabelsToPrices()).containsExactlyEntriesIn(TEST_PRICES);
              assertThat(persistedList.getCreationTimestamp())
                  .isEqualTo(jpaTmRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void saveNew_throwsWhenPremiumListAlreadyExists() {
    PremiumListDao.saveNew(PremiumList.create("testlist", CurrencyUnit.USD, TEST_PRICES));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PremiumListDao.saveNew(
                    PremiumList.create("testlist", CurrencyUnit.USD, TEST_PRICES)));
    assertThat(thrown).hasMessageThat().contains("A premium list of this name already exists");
  }

  @Test
  public void checkExists_worksSuccessfully() {
    assertThat(PremiumListDao.checkExists("testlist")).isFalse();
    PremiumListDao.saveNew(PremiumList.create("testlist", CurrencyUnit.USD, TEST_PRICES));
    assertThat(PremiumListDao.checkExists("testlist")).isTrue();
  }
}
