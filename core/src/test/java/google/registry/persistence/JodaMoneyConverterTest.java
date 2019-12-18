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
package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaUnitTestRule;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import javax.persistence.PostLoad;
import org.hibernate.cfg.Environment;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for embeddable {@link Money}.
 *
 * <p>{@link Money} is a wrapper around {@link org.joda.money.BigMoney} which itself contains two
 * fields: a {@link BigDecimal} {@code amount} and a {@link CurrencyUnit} {@code currency}. When we
 * store an entity with a {@link Money} field, we would like to store it in two columns, for the
 * amount and the currency separately, so that it is easily queryable. This requires that we make
 * {@link Money} a nested embeddable object.
 *
 * <p>However becaues {@link Money} is not a class that we control, we cannot use annotation-based
 * mapping. Therefore there is no {@code JodaMoneyConverter} class. Instead, we define the mapping
 * in {@code META-INF/orm.xml}.
 *
 * <p>Also note that any entity that contains a {@link Money} should should implement a
 * {@link @PostLoad} callback that converts the amount in the {@link Money} to a scale that is
 * appropriate for the currency. This is espcially necessary for currencies like JPY where the scale
 * is 0, which is different from the default scale that {@link BigDecimal} is persisted in database.
 */
@RunWith(JUnit4.class)
public class JodaMoneyConverterTest {
  @Rule
  public final JpaUnitTestRule jpaTmRule =
      new JpaTestRules.Builder()
          .withEntityClass(TestEntity.class, ComplexTestEntity.class)
          .withProperty(Environment.HBM2DDL_AUTO, "update")
          .buildUnitTestRule();

  @Test
  public void roundTripConversion() {
    Money money = Money.of(CurrencyUnit.USD, 100);
    TestEntity entity = new TestEntity(money);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM TestEntity WHERE name = 'id'")
                        .getResultList());
    assertThat(result.size()).isEqualTo(1);
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(
            BigDecimal.valueOf(100).setScale(CurrencyUnit.USD.getDecimalPlaces()), "USD")
        .inOrder();
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
  }

  @Test
  public void roundTripConversionWithComplexEntity() {
    Money myMoney = Money.of(CurrencyUnit.USD, 100);
    Money yourMoney = Money.of(CurrencyUnit.GBP, 80);
    ImmutableMap<String, Money> moneyMap =
        ImmutableMap.of(
            "uno", Money.of(CurrencyUnit.EUR, 500),
            "dos", Money.ofMajor(CurrencyUnit.JPY, 2000),
            "tres", Money.of(CurrencyUnit.GBP, 20));
    ComplexTestEntity entity = new ComplexTestEntity(moneyMap, myMoney, yourMoney);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT my_amount, my_currency, your_amount, your_currency FROM"
                                + " ComplexTestEntity WHERE name = 'id'")
                        .getResultList());
    assertThat(result.size()).isEqualTo(1);
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(
            BigDecimal.valueOf(100).setScale(2), "USD", BigDecimal.valueOf(80).setScale(2), "GBP")
        .inOrder();
    result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT map_amount, map_currency FROM MoneyMap"
                                + " WHERE entity_name = 'id' AND map_key = 'dos'")
                        .getResultList());
    ComplexTestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ComplexTestEntity.class, "id"));
    assertThat(result.size()).isEqualTo(1);

    // Note that the amount has two decimal places even though JPY is supposed to have scale 0.
    // This is due to the unfournate fact that we need to accommodate differet currencies stored
    // in the same table so that the scale has to be set to the largest (2). When a Money field is
    // persisted in an entity, the entity should always have a @PostLoad callback to convert the
    // Money to the correct scale.
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(2000).setScale(2), "JPY")
        .inOrder();
    // Make sure that the loaded entity contains the fields exactly as they are persisted.
    assertThat(persisted.myMoney).isEqualTo(myMoney);
    assertThat(persisted.yourMoney).isEqualTo(yourMoney);
    assertThat(persisted.moneyMap).containsExactlyEntriesIn(moneyMap);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Money money;

    public TestEntity() {}

    TestEntity(Money money) {
      this.money = money;
    }
  }

  @Entity(name = "ComplexTestEntity") // Override entity name to avoid the nested class reference.
  // This entity is used to test column override for embedded fields and collections.
  public static class ComplexTestEntity extends ImmutableObject {

    // After the entity is loaded from the database, go through the money map and make sure that
    // the scale is consistent with the currency. This is necessary for currency like JPY where
    // the scale is 0 but the amount is persisteted as BigDecimal with scale 2.
    @PostLoad
    void setCurrencyScale() {
      moneyMap
          .entrySet()
          .forEach(
              entry -> {
                Money money = entry.getValue();
                if (!money.toBigMoney().isCurrencyScale()) {
                  CurrencyUnit currency = money.getCurrencyUnit();
                  BigDecimal amount = money.getAmount().setScale(currency.getDecimalPlaces());
                  entry.setValue(Money.of(currency, amount));
                }
              });
    }

    @Id String name = "id";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "MoneyMap", joinColumns = @JoinColumn(name = "entity_name"))
    @MapKeyColumn(name = "map_key")
    @AttributeOverrides({
      @AttributeOverride(name = "value.money.amount", column = @Column(name = "map_amount")),
      @AttributeOverride(name = "value.money.currency", column = @Column(name = "map_currency"))
    })
    Map<String, Money> moneyMap;

    @AttributeOverrides({
      @AttributeOverride(name = "money.amount", column = @Column(name = "my_amount")),
      @AttributeOverride(name = "money.currency", column = @Column(name = "my_currency"))
    })
    Money myMoney;

    @AttributeOverrides({
      @AttributeOverride(name = "money.amount", column = @Column(name = "your_amount")),
      @AttributeOverride(name = "money.currency", column = @Column(name = "your_currency"))
    })
    Money yourMoney;

    public ComplexTestEntity() {}

    ComplexTestEntity(ImmutableMap<String, Money> moneyMap, Money myMoney, Money yourMoney) {
      this.moneyMap = moneyMap;
      this.myMoney = myMoney;
      this.yourMoney = yourMoney;
    }
  }
}
