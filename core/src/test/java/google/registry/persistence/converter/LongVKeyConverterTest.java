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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import google.registry.persistence.VKey;
import google.registry.persistence.WithLongVKey;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test SQL persistence of VKey. */
public class LongVKeyConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder()
          .withEntityClass(TestEntity.class, VKeyConverter_LongType.class)
          .buildUnitTestRule();

  @Test
  void testRoundTrip() {
    TestEntity original = new TestEntity(VKey.createSql(TestEntity.class, 10L));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(original));

    TestEntity retrieved =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(retrieved.number.getSqlKey()).isEqualTo(10L);
  }

  @Entity(name = "TestEntity")
  @WithLongVKey(classNameSuffix = "LongType")
  static class TestEntity {
    @Id String id = "id";

    VKey<TestEntity> number;

    TestEntity(VKey<TestEntity> number) {
      this.number = number;
    }

    /** Default constructor, needed for hibernate. */
    public TestEntity() {}
  }
}
