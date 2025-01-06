// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.insertInDb;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import org.joda.money.CurrencyUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CurrencyUnitConverter}. */
public class CurrencyUnitConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion() {
    TestEntity entity = new TestEntity(CurrencyUnit.EUR);
    insertInDb(entity);
    assertThat(
            tm().transact(
                    () ->
                        tm().getEntityManager()
                            .createNativeQuery(
                                "SELECT currency FROM \"TestEntity\" WHERE name = 'id'")
                            .getResultList()))
        .containsExactly("EUR");
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.currency).isEqualTo(CurrencyUnit.EUR);
  }

  @Test
  void invalidCurrency() {
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"TestEntity\" (name, currency) VALUES('id', 'XXXX')")
                    .executeUpdate());
    PersistenceException thrown =
        assertThrows(
            PersistenceException.class,
            () ->
                tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id").currency));
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
