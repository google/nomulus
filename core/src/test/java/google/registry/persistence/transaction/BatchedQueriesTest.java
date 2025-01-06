// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.persistence.transaction.BatchedQueries.loadAllOf;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class BatchedQueriesTest {

  @RegisterExtension
  final JpaTestExtensions.JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder()
          .withEntityClass(LongIdEntity.class, StringIdEntity.class)
          .buildUnitTestExtension();

  @Test
  void loadAllOf_noData() {
    assertThat(loadAllOf(StringIdEntity.class)).isEmpty();
  }

  @Test
  void loadAllOf_oneEntry() {
    StringIdEntity entity = persistResource(new StringIdEntity("C1"));
    assertThat(loadAllOf(StringIdEntity.class)).containsExactly(ImmutableList.of(entity));
  }

  @Test
  void loadAllOf_multipleEntries_fullBatches() {
    // Insert in reverse order. In practice the result of "FROM Contact" will be in this order.
    // This tests that the `order by` clause is present in the query.
    StringIdEntity entity4 = persistResource(new StringIdEntity("C4"));
    StringIdEntity entity3 = persistResource(new StringIdEntity("C3"));
    StringIdEntity entity2 = persistResource(new StringIdEntity("C2"));
    StringIdEntity entity1 = persistResource(new StringIdEntity("C1"));
    assertThat(loadAllOf(StringIdEntity.class, 2))
        .containsExactly(ImmutableList.of(entity1, entity2), ImmutableList.of(entity3, entity4))
        .inOrder();
  }

  @Test
  void loadAllOf_multipleEntries_withPartialBatch() {
    StringIdEntity entity1 = persistResource(new StringIdEntity("C1"));
    StringIdEntity entity2 = persistResource(new StringIdEntity("C2"));
    StringIdEntity entity3 = persistResource(new StringIdEntity("C3"));
    StringIdEntity entity4 = persistResource(new StringIdEntity("C4"));
    assertThat(loadAllOf(StringIdEntity.class, 3))
        .containsExactly(ImmutableList.of(entity1, entity2, entity3), ImmutableList.of(entity4))
        .inOrder();
  }

  @Test
  void loadAllOf_multipleEntries_withLongNumberAsId() {
    LongIdEntity testEntity2 = new LongIdEntity(2L);
    LongIdEntity testEntity10 = new LongIdEntity(10L);
    tm().transact(() -> tm().put(testEntity2));
    tm().transact(() -> tm().put(testEntity10));

    assertThat(loadAllOf(LongIdEntity.class, 1))
        .containsExactly(ImmutableList.of(testEntity2), ImmutableList.of(testEntity10))
        .inOrder();
  }

  @Entity(name = "StringIdEntity")
  static class StringIdEntity extends ImmutableObject {
    @Id String id;

    StringIdEntity() {}

    private StringIdEntity(String id) {
      this.id = id;
    }
  }

  @Entity(name = "LongIdEntity")
  private static class LongIdEntity extends ImmutableObject {
    @Id long entityId;

    LongIdEntity() {}

    private LongIdEntity(long id) {
      this.entityId = id;
    }
  }
}
