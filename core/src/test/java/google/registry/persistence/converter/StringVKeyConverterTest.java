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
import static google.registry.testing.DatabaseHelper.insertInDb;

import google.registry.model.ImmutableObject;
import google.registry.persistence.VKey;
import google.registry.persistence.WithStringVKey;
import google.registry.testing.AppEngineExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Test SQL persistence of VKey. */
public class StringVKeyConverterTest {

  @RegisterExtension
  public final AppEngineExtension appEngineExtension =
      new AppEngineExtension.Builder()
          .withDatastoreAndCloudSql()
          .withoutCannedData()
          .withJpaUnitTestEntities(
              TestStringEntity.class,
              VKeyConverter_StringType.class,
              VKeyConverter_CompositeStringType.class)
          .withOfyTestEntities(TestStringEntity.class, CompositeKeyTestStringEntity.class)
          .build();

  @Test
  void testRoundTrip() {
    TestStringEntity original =
        new TestStringEntity(
            "TheRealSpartacus",
            VKey.createSql(TestStringEntity.class, "ImSpartacus!"),
            VKey.createSql(CompositeKeyTestStringEntity.class, "NoImSpartacus!"));
    insertInDb(original);

    TestStringEntity retrieved =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(TestStringEntity.class, "TheRealSpartacus"));
    assertThat(retrieved.other.getSqlKey()).isEqualTo("ImSpartacus!");
    assertThat(retrieved.other.getOfyKey().getName()).isEqualTo("ImSpartacus!");

    assertThat(retrieved.composite.getSqlKey()).isEqualTo("NoImSpartacus!");
    assertThat(retrieved.composite.maybeGetOfyKey().isPresent()).isFalse();
  }

  @Entity(name = "TestStringEntity")
  @com.googlecode.objectify.annotation.Entity
  @WithStringVKey(classNameSuffix = "StringType")
  static class TestStringEntity extends ImmutableObject {
    @com.googlecode.objectify.annotation.Id @Id String id;

    VKey<TestStringEntity> other;
    VKey<CompositeKeyTestStringEntity> composite;

    TestStringEntity(
        String id, VKey<TestStringEntity> other, VKey<CompositeKeyTestStringEntity> composite) {
      this.id = id;
      this.other = other;
      this.composite = composite;
    }

    /** Default constructor, needed for hibernate. */
    public TestStringEntity() {}
  }

  @Entity(name = "CompositeKeyTestStringEntity")
  @com.googlecode.objectify.annotation.Entity
  @WithStringVKey(classNameSuffix = "CompositeStringType", compositeKey = true)
  static class CompositeKeyTestStringEntity {
    @com.googlecode.objectify.annotation.Id @Id String id = "id";
  }
}
