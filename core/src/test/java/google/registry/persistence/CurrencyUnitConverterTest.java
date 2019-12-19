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
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaUnitTestRule;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PersistenceException;
import org.joda.money.CurrencyUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CurrencyUnitConverter}. */
@RunWith(JUnit4.class)
public class CurrencyUnitConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void roundTripConversion() {
    TestEntity entity = new TestEntity(CurrencyUnit.EUR);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(entity));
    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createNativeQuery(
                                "SELECT currency FROM \"TestEntity\" WHERE name = 'id'")
                            .getResultList()))
        .containsExactly("EUR");
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.currency).isEqualTo(CurrencyUnit.EUR);
  }

  @Test
  public void invalidCurrency() {
    jpaTm()
        .transact(
            () ->
                jpaTm()
                    .getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"TestEntity\" (name, currency) VALUES('id', 'XXXX')")
                    .executeUpdate());
    PersistenceException thrown =
        assertThrows(
            PersistenceException.class,
            () ->
                jpaTm()
                    .transact(
                        () -> jpaTm().getEntityManager().find(TestEntity.class, "id").currency));
    assertThat(thrown).hasCauseThat().hasMessageThat().isEqualTo("Unknown currency 'XXXX'");
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    CurrencyUnit currency;

    public TestEntity() {}

    TestEntity(CurrencyUnit currency) {
      this.currency = currency;
    }
  }
}
