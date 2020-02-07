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
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableSet;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import java.util.Set;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.hibernate.annotations.Type;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EnumSetUserType}. */
@RunWith(JUnit4.class)
public class EnumSetUserTypeTest {
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  public EnumSetUserTypeTest() {}

  @Test
  public void testRoundTrip() {
    Set<TestEnum> enums = ImmutableSet.of(TestEnum.BAR, TestEnum.FOO);
    TestEntity obj = new TestEntity("foo", enums);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(obj));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "foo"));
    assertThat(persisted.data).isEqualTo(enums);
  }

  @Test
  public void testNativeQuery_succeeds() {
    Set<TestEnum> enums = ImmutableSet.of(TestEnum.BAR, TestEnum.FOO);
    TestEntity obj = new TestEntity("foo", enums);

    jpaTm().transact(() -> jpaTm().getEntityManager().persist(obj));

    assertThat(
            ImmutableSet.of(
                getSingleResultFromNativeQuery(
                    "SELECT data[1] FROM \"TestEntity\" WHERE name = 'foo'"),
                getSingleResultFromNativeQuery(
                    "SELECT data[2] FROM \"TestEntity\" WHERE name = 'foo'")))
        .containsExactly("BAR", "FOO");
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return jpaTm()
        .transact(() -> jpaTm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  enum TestEnum {
    FOO,
    BAR,
    BAZ;

    public static class TestEnumType extends EnumSetUserType<TestEnum> {}
  }

  @Entity(name = "TestEntity")
  static class TestEntity {
    @Id String name;

    @Type(type = "google.registry.persistence.EnumSetUserTypeTest$TestEnum$TestEnumType")
    Set<TestEnum> data;

    TestEntity() {}

    TestEntity(String name, Set<TestEnum> data) {
      this.name = name;
      this.data = data;
    }
  }
}
