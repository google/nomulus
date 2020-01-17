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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.model.ImmutableObject;
import google.registry.model.UpdateAutoTimestamp;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link UpdateAutoTimestampConverter}. */
@RunWith(JUnit4.class)
public class UpdateAutoTimestampConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void testTypeConversion() {
    TestEntity ent = new TestEntity("myinst", null);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(ent));

    TestEntity result =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "myinst"));

    assertThat(result.name).isEqualTo("myinst");
    assertThat(result.uat.getTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
  }

  @Test
  public void testTimeChangesOnSubsequentTransactions() {
    TestEntity ent1 = new TestEntity("myinst1", null);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(ent1));

    TestEntity result1 =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "myinst1"));

    jpaRule.getTxnClock().advanceOneMilli();

    TestEntity ent2 = new TestEntity("myinst2", result1.uat);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(ent2));

    TestEntity result2 =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "myinst2"));

    assertThat(result1.uat.getTimestamp()).isNotEqualTo(result2.uat.getTimestamp());
    assertThat(result2.uat.getTimestamp()).isEqualTo(jpaRule.getTxnClock().nowUtc());
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  public static class TestEntity extends ImmutableObject {

    @Id String name;

    UpdateAutoTimestamp uat;

    public TestEntity() {}

    TestEntity(String name, UpdateAutoTimestamp uat) {
      this.name = name;
      this.uat = uat;
    }
  }
}
