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
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.googlecode.objectify.Key;
import google.registry.model.ImmutableObject;
import google.registry.model.registry.label.ReservedList;
import google.registry.testing.AppEngineExtension;
import java.util.Set;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

/** Unit tests for {@link ReservedListKeySetConverter}. */
class ReservedListKeySetConverterTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withJpaUnitTestEntities(ReservedListSetEntity.class)
          .build();

  @Test
  void roundTripConversion_returnsSameSet() {
    Key<ReservedList> key1 = Key.create(getCrossTldKey(), ReservedList.class, "test1");
    Key<ReservedList> key2 = Key.create(getCrossTldKey(), ReservedList.class, "test2");
    Key<ReservedList> key3 = Key.create(getCrossTldKey(), ReservedList.class, "test3");

    Set<Key<ReservedList>> reservedLists = ImmutableSet.of(key1, key2, key3);
    ReservedListSetEntity testEntity = new ReservedListSetEntity(reservedLists);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    ReservedListSetEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ReservedListSetEntity.class, "id"));
    assertThat(persisted.reservedList).containsExactly(key1, key2, key3);
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    ReservedListSetEntity testEntity = new ReservedListSetEntity(null);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    ReservedListSetEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ReservedListSetEntity.class, "id"));
    assertThat(persisted.reservedList).isNull();
  }

  @Test
  void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    ReservedListSetEntity testEntity = new ReservedListSetEntity(ImmutableSet.of());
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    ReservedListSetEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ReservedListSetEntity.class, "id"));
    assertThat(persisted.reservedList).isEmpty();
  }

  @Entity(name = "ReservedListSetEntity")
  private static class ReservedListSetEntity extends ImmutableObject {

    @Id String name = "id";

    Set<Key<ReservedList>> reservedList;

    public ReservedListSetEntity() {}

    ReservedListSetEntity(Set<Key<ReservedList>> reservedList) {
      this.reservedList = reservedList;
    }
  }
}
