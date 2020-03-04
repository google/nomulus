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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newRegistry;
import static google.registry.testing.DatastoreHelper.persistResource;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import java.math.BigDecimal;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PremiumListDao}. */
@RunWith(JUnit4.class)
public class PremiumListDaoTest {

  private final FakeClock fakeClock = new FakeClock();

  @Rule
  public final AppEngineRule appEngine =
      AppEngineRule.builder().withDatastoreAndCloudSql().withClock(fakeClock).build();

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
              Optional<PremiumList> persistedListOpt = PremiumListDao.getLatestRevision("testname");
              assertThat(persistedListOpt).isPresent();
              PremiumList persistedList = persistedListOpt.get();
              assertThat(persistedList.getLabelsToPrices()).containsExactlyEntriesIn(TEST_PRICES);
              assertThat(persistedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
            });
  }

  @Test
  public void update_worksSuccessfully() {
    PremiumListDao.saveNew(PremiumList.create("testname", CurrencyUnit.USD, TEST_PRICES));
    Optional<PremiumList> persistedList = PremiumListDao.getLatestRevision("testname");
    assertThat(persistedList).isPresent();
    long firstRevisionId = persistedList.get().getRevisionId();
    PremiumListDao.update(
        PremiumList.create(
            "testname",
            CurrencyUnit.USD,
            ImmutableMap.of(
                "update",
                BigDecimal.valueOf(55343.12),
                "new",
                BigDecimal.valueOf(0.01),
                "silver",
                BigDecimal.valueOf(30.03))));
    jpaTm()
        .transact(
            () -> {
              Optional<PremiumList> updatedListOpt = PremiumListDao.getLatestRevision("testname");
              assertThat(updatedListOpt).isPresent();
              PremiumList updatedList = updatedListOpt.get();
              assertThat(updatedList.getLabelsToPrices())
                  .containsExactlyEntriesIn(
                      ImmutableMap.of(
                          "update",
                          BigDecimal.valueOf(55343.12),
                          "new",
                          BigDecimal.valueOf(0.01),
                          "silver",
                          BigDecimal.valueOf(30.03)));
              assertThat(updatedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
              assertThat(updatedList.getRevisionId()).isGreaterThan(firstRevisionId);
              assertThat(updatedList.getCreationTimestamp()).isEqualTo(fakeClock.nowUtc());
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

  // TODO(b/147246613): Un-ignore this.
  @Test
  @Ignore
  public void update_throwsWhenListDoesntExist() {
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

  @Test
  public void testGetPremiumPrice_worksForJPY() {
    persistResource(
        newRegistry("foobar", "FOOBAR")
            .asBuilder()
            .setPremiumListKey(
                Key.create(
                    getCrossTldKey(),
                    google.registry.model.registry.label.PremiumList.class,
                    "premlist"))
            .build());
    PremiumListDao.saveNew(
        PremiumList.create(
            "premlist",
            JPY,
            ImmutableMap.of(
                "silver",
                BigDecimal.valueOf(10.00),
                "gold",
                BigDecimal.valueOf(1000.0),
                "palladium",
                BigDecimal.valueOf(15000))));
    assertThat(PremiumListDao.getPremiumPrice("silver", Registry.get("foobar")))
        .hasValue(moneyOf(JPY, 10));
    assertThat(PremiumListDao.getPremiumPrice("gold", Registry.get("foobar")))
        .hasValue(moneyOf(JPY, 1000));
    assertThat(PremiumListDao.getPremiumPrice("palladium", Registry.get("foobar")))
        .hasValue(moneyOf(JPY, 15000));
  }

  private static Money moneyOf(CurrencyUnit unit, double amount) {
    return Money.of(unit, BigDecimal.valueOf(amount).setScale(unit.getDecimalPlaces()));
  }
}
