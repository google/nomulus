// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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
import google.registry.model.registrar.Registrar.Type;
import google.registry.model.transaction.JpaTestRules;
import google.registry.model.transaction.JpaTestRules.JpaUnitTestRule;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link RegistrarTypeConverter}. */
@RunWith(JUnit4.class)
public class RegistrarTypeConverterTest {
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void roundTripConversion_returnsSameEnum() {
    TestEntity testEntity = new TestEntity(Type.MONITORING);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.type).isEqualTo(Type.MONITORING);
  }

  @Test
  public void testNativeQuery_succeeds() {
    TestEntity testEntity = new TestEntity(Type.MONITORING);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));

    assertThat(
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createNativeQuery("SELECT type FROM \"TestEntity\" WHERE name = 'id'")
                            .getSingleResult()))
        .isEqualTo("MONITORING");
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Type type;

    private TestEntity() {}

    private TestEntity(Type type) {
      this.type = type;
    }
  }
}
