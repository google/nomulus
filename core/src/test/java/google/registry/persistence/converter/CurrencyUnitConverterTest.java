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
import static org.junit.Assert.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import google.registry.schema.replay.EntityTest.EntityForTesting;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PersistenceException;
import org.joda.money.CurrencyUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CurrencyUnitConverter}. */
public class CurrencyUnitConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  void roundTripConversion() {
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
  void invalidCurrency() {
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
  @EntityForTesting
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    CurrencyUnit currency;

    public TestEntity() {}

    TestEntity(CurrencyUnit currency) {
      this.currency = currency;
    }
  }
}
