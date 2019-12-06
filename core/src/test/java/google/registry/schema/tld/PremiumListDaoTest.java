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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.JUnitBackports.assertThrows;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry;
import google.registry.model.transaction.JpaTransactionManagerRule;
import google.registry.testing.AppEngineRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.joda.money.Money;
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

  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

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
    PremiumList premiumList = PremiumList.create("testname", USD, TEST_PRICES);
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
  public void update_worksSuccessfully() {
    PremiumListDao.saveNew(
        PremiumList.create(
            "testname", USD, ImmutableMap.of("firstversion", BigDecimal.valueOf(123.45))));
    PremiumListDao.update(PremiumList.create("testname", USD, TEST_PRICES));
    jpaTm()
        .transact(
            () -> {
              List<PremiumList> persistedLists =
                  jpaTm()
                      .getEntityManager()
                      .createQuery(
                          "SELECT pl FROM PremiumList pl WHERE pl.name = :name ORDER BY"
                              + " pl.revisionId",
                          PremiumList.class)
                      .setParameter("name", "testname")
                      .getResultList();
              assertThat(persistedLists).hasSize(2);
              assertThat(persistedLists.get(1).getLabelsToPrices())
                  .containsExactlyEntriesIn(TEST_PRICES);
              assertThat(persistedLists.get(1).getCreationTimestamp())
                  .isEqualTo(jpaTmRule.getTxnClock().nowUtc());
            });
  }

  @Test
  public void saveNew_throwsWhenPremiumListAlreadyExists() {
    PremiumListDao.saveNew(PremiumList.create("testlist", USD, TEST_PRICES));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> PremiumListDao.saveNew(PremiumList.create("testlist", USD, TEST_PRICES)));
    assertThat(thrown).hasMessageThat().isEqualTo("Premium list 'testlist' already exists");
  }

  @Test
  public void update_throwsWhenPremiumListDoesntExist() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> PremiumListDao.update(PremiumList.create("testlist", USD, TEST_PRICES)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Can't update non-existent premium list 'testlist'");
  }

  @Test
  public void checkExists_worksSuccessfully() {
    assertThat(PremiumListDao.checkExists("testlist")).isFalse();
    PremiumListDao.saveNew(PremiumList.create("testlist", USD, TEST_PRICES));
    assertThat(PremiumListDao.checkExists("testlist")).isTrue();
  }

  @Test
  public void getLatestRevision_returnsEmptyForNonexistentList() {
    assertThat(PremiumListDao.getLatestRevision("nonexistentlist")).isEmpty();
  }

  @Test
  public void getLatestRevision_worksSuccessfully() {
    PremiumListDao.saveNew(
        PremiumList.create("list1", JPY, ImmutableMap.of("wrong", BigDecimal.valueOf(1000.50))));
    PremiumListDao.update(PremiumList.create("list1", JPY, TEST_PRICES));
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> persistedList = PremiumListDao.getLatestRevision("list1");
              assertThat(persistedList).isPresent();
              assertThat(persistedList.get().getName()).isEqualTo("list1");
              assertThat(persistedList.get().getCurrency()).isEqualTo(JPY);
              assertThat(persistedList.get().getLabelsToPrices())
                  .containsExactlyEntriesIn(TEST_PRICES);
            });
  }

  @Test
  public void getPremiumPrice_returnsNoneWhenNoPremiumListConfigured() {
    persistResource(newRegistry("foobar", "FOOBAR").asBuilder().setPremiumList(null).build());
    assertThat(PremiumListDao.getPremiumPrice("rich", Registry.get("foobar"))).isEmpty();
  }

  @Test
  public void getPremiumPrice_worksSuccessfully() {
    persistResource(
        newRegistry("foobar", "FOOBAR")
            .asBuilder()
            .setPremiumListKey(
                Key.create(
                    getCrossTldKey(),
                    google.registry.model.registry.label.PremiumList.class,
                    "premlist"))
            .build());
    PremiumListDao.saveNew(PremiumList.create("premlist", USD, TEST_PRICES));
    assertThat(PremiumListDao.getPremiumPrice("silver", Registry.get("foobar")))
        .hasValue(Money.of(USD, 10.23));
    assertThat(PremiumListDao.getPremiumPrice("gold", Registry.get("foobar")))
        .hasValue(Money.of(USD, 1305.47));
    assertThat(PremiumListDao.getPremiumPrice("zirconium", Registry.get("foobar"))).isEmpty();
  }

  @Test
  public void testGetPremiumPrice_throwsWhenPremiumListCantBeLoaded() {
    createTld("tld");
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> PremiumListDao.getPremiumPrice("foobar", Registry.get("tld")));
    assertThat(thrown).hasMessageThat().isEqualTo("Could not load premium list 'tld'");
  }
}
