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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * JUnit test for {@link JpaTransactionManagerExtension}, with {@link JpaUnitTestExtension} as
 * proxy.
 */
public class JpaTransactionManagerRuleTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  void verifiesRuleWorks() {
    assertThrows(
        PersistenceException.class,
        () ->
            jpaTm()
                .transact(
                    () ->
                        jpaTm()
                            .getEntityManager()
                            .createNativeQuery("SELECT * FROM NoneExistentTable")
                            .getResultList()));
    jpaTm()
        .transact(
            () -> {
              List<?> results =
                  jpaTm()
                      .getEntityManager()
                      .createNativeQuery("SELECT * FROM \"TestEntity\"")
                      .getResultList();
              assertThat(results).isEmpty();
            });
  }

  @Test
  void testExtraParameters() {
    // This test verifies that 1) withEntityClass() has registered TestEntity and 2) The table
    // has been created, implying withProperty(HBM2DDL_AUTO, "update") worked.
    TestEntity original = new TestEntity("key", "value");
    jpaTm().transact(() -> jpaTm().insert(original));
    TestEntity retrieved =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "key"));
    assertThat(retrieved).isEqualTo(original);
  }

  @Entity(name = "TestEntity") // Specify name to avoid nested class naming issues.
  static class TestEntity extends ImmutableObject {
    @Id String key;
    String value;

    TestEntity(String key, String value) {
      this.key = key;
      this.value = value;
    }

    TestEntity() {}
  }
}
