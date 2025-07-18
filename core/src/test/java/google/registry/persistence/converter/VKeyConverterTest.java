// Copyright 2022 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.testing.DatabaseHelper.persistResources;

import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.WithVKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test SQL persistence of {@link VKey}. */
public class VKeyConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withoutCannedData()
          .withEntityClass(
              TestEntity.class,
              TestStringEntity.class,
              TestLongEntity.class,
              VKeyConverter_TestStringEntity.class,
              VKeyConverter_TestLongEntity.class)
          .buildUnitTestExtension();

  @Test
  void testRoundTrip() {
    TestStringEntity stringEntity = new TestStringEntity("TheRealSpartacus");
    VKey<TestStringEntity> stringKey = VKey.create(TestStringEntity.class, "TheRealSpartacus");
    TestLongEntity longEntity = new TestLongEntity(300L);
    VKey<TestLongEntity> longKey = VKey.create(TestLongEntity.class, 300L);
    TestEntity original = new TestEntity(1984L, stringKey, longKey);
    persistResources(stringEntity, longEntity, original);

    TestEntity retrieved =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, 1984L));
    assertThat(retrieved.stringKey).isEqualTo(stringKey);
    assertThat(retrieved.longKey).isEqualTo(longKey);
  }

  @Entity(name = "TestStringEntity")
  @WithVKey(String.class)
  protected static class TestStringEntity extends ImmutableObject {
    @Id String id;

    TestStringEntity(String id) {
      this.id = id;
    }

    /** Default constructor, needed for hibernate. */
    public TestStringEntity() {}
  }

  @Entity(name = "TestLongEntity")
  @WithVKey(Long.class)
  protected static class TestLongEntity extends ImmutableObject {
    @Id Long id;

    TestLongEntity(Long id) {
      this.id = id;
    }

    /** Default constructor, needed for hibernate. */
    public TestLongEntity() {}
  }

  @Entity(name = "TestEntity")
  @WithVKey(String.class)
  protected static class TestEntity extends ImmutableObject {
    @Id Long id;
    VKey<TestStringEntity> stringKey;
    VKey<TestLongEntity> longKey;

    TestEntity(Long id, VKey<TestStringEntity> stringKey, VKey<TestLongEntity> longKey) {
      this.id = id;
      this.stringKey = stringKey;
      this.longKey = longKey;
    }

    /** Default constructor, needed for hibernate. */
    public TestEntity() {}
  }
}
