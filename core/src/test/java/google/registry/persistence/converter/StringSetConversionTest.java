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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@code Set<String>} modeling using Hibernate 6 builtin support. */
public class StringSetConversionTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameStringList() {
    Set<String> tlds = ImmutableSet.of("app", "dev", "how");
    TestEntity testEntity = new TestEntity(tlds);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).containsExactly("app", "dev", "how");
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isNull();
  }

  @Test
  void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableSet.of());
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isEmpty();
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Set<String> tlds;

    private TestEntity() {}

    private TestEntity(Set<String> tlds) {
      this.tlds = tlds;
    }
  }
}
