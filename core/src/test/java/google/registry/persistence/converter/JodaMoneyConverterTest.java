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
package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.model.replay.EntityTest.EntityForTesting;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;
import org.hibernate.annotations.Columns;
import org.hibernate.annotations.Type;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for embeddable {@link JodaMoneyType}. */
public class JodaMoneyConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(TestEntity.class, ComplexTestEntity.class)
          .buildUnitTestExtension();

  @Test
  void roundTripConversion() {
    Money money = Money.of(CurrencyUnit.USD, 100);
    TestEntity entity = new TestEntity(money);
    insertInDb(entity);
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result.size()).isEqualTo(1);
    // The amount property, when loaded as a raw value, has the same scale as the table column,
    // which is 2.
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(100).setScale(2), "USD")
        .inOrder();
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
    assertThat(persisted.money.getAmount().scale()).isEqualTo(2);
  }

  @Test
  void roundTripConversion_scale0() {
    Money money = Money.ofMajor(CurrencyUnit.JPY, 100);
    assertThat(money.getAmount().scale()).isEqualTo(0); // JPY's amount has scale at 0.
    TestEntity entity = new TestEntity(money);
    insertInDb(entity);
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result.size()).isEqualTo(1);
    /* The amount property, when loaded as a raw value, has the same scale as the table column,
    which is 2. */
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(100).setScale(2), "JPY")
        .inOrder();

    result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createQuery("SELECT money FROM TestEntity WHERE name" + " = 'id'")
                        .getResultList());

    // When the money field is loaded as an embedded entity, it has the desired scale (0).
    assertThat(result.get(0)).isEqualTo(money);

    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
  }

  @Test
  void roundTripConversionWithComplexEntity() {
    Money myMoney = Money.of(CurrencyUnit.USD, 100);
    Money yourMoney = Money.of(CurrencyUnit.GBP, 80);
    ImmutableMap<String, Money> moneyMap =
        ImmutableMap.of(
            "uno", Money.of(CurrencyUnit.EUR, 500),
            "dos", Money.ofMajor(CurrencyUnit.JPY, 2000),
            "tres", Money.of(CurrencyUnit.GBP, 20));
    ComplexTestEntity entity = new ComplexTestEntity(moneyMap, myMoney, yourMoney);
    insertInDb(entity);
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT my_amount, my_currency, your_amount, your_currency FROM"
                                + " \"ComplexTestEntity\" WHERE name = 'id'")
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
                            "SELECT map_amount, map_currency FROM \"MoneyMap\""
                                + " WHERE entity_name = 'id' AND map_key = 'dos'")
                        .getResultList());
    ComplexTestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ComplexTestEntity.class, "id"));
    assertThat(result.size()).isEqualTo(1);

    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(2000).setScale(2), "JPY")
        .inOrder();
    // Make sure that the loaded entity contains the fields exactly as they are persisted.
    assertThat(persisted.myMoney).isEqualTo(myMoney);
    assertThat(persisted.yourMoney).isEqualTo(yourMoney);
    assertThat(persisted.moneyMap).containsExactlyEntriesIn(moneyMap);
  }

  // Override entity name to exclude outer-class name in table name. Not necessary if class is not
  // inner class.
  @Entity(name = "TestEntity")
  @EntityForTesting
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(columns = {@Column(name = "amount"), @Column(name = "currency")})
    Money money;

    public TestEntity() {}

    TestEntity(Money money) {
      this.money = money;
    }
  }

  // See comments on the annotation for TestEntity above for reason.
  @Entity(name = "ComplexTestEntity")
  @EntityForTesting
  // This entity is used to test column override for embedded fields and collections.
  public static class ComplexTestEntity extends ImmutableObject {

    @Id String name = "id";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "MoneyMap", joinColumns = @JoinColumn(name = "entity_name"))
    @MapKeyColumn(name = "map_key")
    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(columns = {@Column(name = "map_amount"), @Column(name = "map_currency")})
    Map<String, Money> moneyMap;

    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(columns = {@Column(name = "my_amount"), @Column(name = "my_currency")})
    Money myMoney;

    @Type(type = JodaMoneyType.TYPE_NAME)
    @Columns(columns = {@Column(name = "your_amount"), @Column(name = "your_currency")})
    Money yourMoney;

    public ComplexTestEntity() {}

    ComplexTestEntity(ImmutableMap<String, Money> moneyMap, Money myMoney, Money yourMoney) {
      this.moneyMap = moneyMap;
      this.myMoney = myMoney;
      this.yourMoney = yourMoney;
    }
  }
}
