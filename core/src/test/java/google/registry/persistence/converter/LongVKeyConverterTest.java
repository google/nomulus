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
import google.registry.testing.AppEngineExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test SQL persistence of VKey. */
public class LongVKeyConverterTest {

  @RegisterExtension
  public final AppEngineExtension appEngineExtension =
      new AppEngineExtension.Builder()
          .withDatastoreAndCloudSql()
          .withoutCannedData()
          .withJpaUnitTestEntities(
              TestLongEntity.class,
              VKeyConverter_LongType.class,
              VKeyConverter_CompositeLongType.class)
          .withOfyTestEntities(TestLongEntity.class, CompositeKeyTestLongEntity.class)
          .build();

  @Test
  void testRoundTrip() {
    TestLongEntity original =
        new TestLongEntity(
            VKey.createSql(TestLongEntity.class, 10L),
            VKey.createSql(CompositeKeyTestLongEntity.class, 20L));
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(original));

    TestLongEntity retrieved =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestLongEntity.class, "id"));
    assertThat(retrieved.number.getSqlKey()).isEqualTo(10L);
    assertThat(retrieved.number.getOfyKey().getId()).isEqualTo(10L);

    assertThat(retrieved.composite.getSqlKey()).isEqualTo(20L);
    assertThat(retrieved.composite.maybeGetOfyKey().isPresent()).isFalse();
  }

  @Entity(name = "TestLongEntity")
  @com.googlecode.objectify.annotation.Entity
  @WithLongVKey(classNameSuffix = "LongType")
  static class TestLongEntity {
    @com.googlecode.objectify.annotation.Id @Id String id = "id";

    VKey<TestLongEntity> number;
    VKey<CompositeKeyTestLongEntity> composite;

    TestLongEntity(VKey<TestLongEntity> number, VKey<CompositeKeyTestLongEntity> composite) {
      this.number = number;
      this.composite = composite;
    }

    /** Default constructor, needed for hibernate. */
    public TestLongEntity() {}
  }

  @Entity(name = "CompositeKeyTestLongEntity")
  @com.googlecode.objectify.annotation.Entity
  @WithLongVKey(classNameSuffix = "CompositeLongType", compositeKey = true)
  static class CompositeKeyTestLongEntity {
    @com.googlecode.objectify.annotation.Id @Id String id = "id";
  }
}
