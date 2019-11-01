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

import google.registry.model.ImmutableObject;
import google.registry.model.transaction.JpaTransactionManagerRule;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
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
 * amount and the currency separately, so that it is easily querable. This requires that we make
 * {@link Money} a nested embeddable object.
 *
 * <p>However becaues {@link Money} is not a class that we control, we cannot use annotation-based
 * mapping. Therefore there is no {@code JodaMoneyConverter} class. Instead, we define the mapping
 * in {@code META-INF/orm.xml}.
 */
@RunWith(JUnit4.class)
public class JodaMoneyConverterTest {

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder()
          .withEntityClass(TestEntity.class)
          .withEntityClass(TestEntityWithOtherAmount.class)
          .withProperty(Environment.HBM2DDL_AUTO, "update")
          .build();

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
                            "SELECT amount, currency FROM TestEntity WHERE name =" + " 'id'")
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
  public void roundTripConversionWithOtherAmount() {
    Money money = Money.of(CurrencyUnit.USD, 100);
    TestEntityWithOtherAmount entity = new TestEntityWithOtherAmount(200, money);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    List<?> result =
        jpaTm()
            .transact(
                () ->
                    jpaTm()
                        .getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, money_amount, currency FROM TestEntityWithOtherAmount"
                                + " WHERE name = 'id'")
                        .getResultList());
    assertThat(result.size()).isEqualTo(1);
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(
            200, BigDecimal.valueOf(100).setScale(CurrencyUnit.USD.getDecimalPlaces()), "USD")
        .inOrder();
    TestEntityWithOtherAmount persisted =
        jpaTm()
            .transact(() -> jpaTm().getEntityManager().find(TestEntityWithOtherAmount.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
    assertThat(persisted.amount).isEqualTo(200);
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

  @Entity(
      name =
          "TestEntityWithOtherAmount") // Override entity name to avoid the nested class reference.
  public static class TestEntityWithOtherAmount extends ImmutableObject {

    @Id String name = "id";

    int amount;

    @AttributeOverrides(
        @AttributeOverride(name = "money.amount", column = @Column(name = "money_amount")))
    Money money;

    public TestEntityWithOtherAmount() {}

    TestEntityWithOtherAmount(int amount, Money money) {
      this.amount = amount;
      this.money = money;
    }
  }
}
