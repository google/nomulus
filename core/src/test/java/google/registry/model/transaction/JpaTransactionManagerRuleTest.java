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

package google.registry.model.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.JUnitBackports.assertThrows;

import google.registry.model.ImmutableObject;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.PersistenceException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** JUnit test for {@link JpaTransactionManagerRule} */
@RunWith(JUnit4.class)
public class JpaTransactionManagerRuleTest {

  @Rule
  public final JpaTransactionManagerRule jpaTmRule =
      new JpaTransactionManagerRule.Builder()
          .withEntityClass(TestEntity.class)
          .build();

  @Test
  public void verifiesRuleWorks() {
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
              List results =
                  jpaTm()
                      .getEntityManager()
                      .createNativeQuery("SELECT * FROM \"ClaimsList\"")
                      .getResultList();
              assertThat(results).isEmpty();
            });
  }

  @Test
  public void testExtraParameters() {
    // This test verifies that 1) withEntityClass() has registered TestEntity and 2) The table
    // has been created, implying withProperty(HBM2DDL_AUTO, "update") worked.
    TestEntity original = new TestEntity("key", "value");
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(original));
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
