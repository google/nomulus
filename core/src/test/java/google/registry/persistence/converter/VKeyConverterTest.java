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
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import javax.persistence.Convert;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test SQL persistence of VKey. */
@RunWith(JUnit4.class)
public class VKeyConverterTest {

  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  public VKeyConverterTest() {}

  @Test
  public void testRoundTrip() {
    TestEntity original =
        new TestEntity("TheRealSpartacus", VKey.createSql(TestEntity.class, "ImSpartacus!"));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(original));

    TestEntity retrieved =
        jpaTm()
            .transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "TheRealSpartacus"));
    assertThat(retrieved.other.getSqlKey()).isEqualTo("ImSpartacus!");
  }

  static class TestEntityVKeyConverter extends VKeyConverter<TestEntity> {

    @Override
    protected Class<TestEntity> getAttributeClass() {
      return TestEntity.class;
    }
  }

  @Entity(name = "TestEntity")
  static class TestEntity {
    @Id String id;

    // Specifying "@Converter(autoApply = true) on TestEntityVKeyConverter this doesn't seem to
    // work.
    @Convert(converter = TestEntityVKeyConverter.class)
    VKey<TestEntity> other;

    TestEntity(String id, VKey<TestEntity> other) {
      this.id = id;
      this.other = other;
    }

    /** Default constructor, needed for hibernate. */
    public TestEntity() {}
  }
}
