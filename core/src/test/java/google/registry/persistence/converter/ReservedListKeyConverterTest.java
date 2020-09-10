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
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ReservedListKeyConverter}. */
class ReservedListKeyConverterTest {

  @RegisterExtension
  final AppEngineExtension appEngine =
      AppEngineExtension.builder()
          .withDatastoreAndCloudSql()
          .withJpaUnitTestEntities(ReservedListEntity.class)
          .build();

  private final ReservedListKeyConverter converter = new ReservedListKeyConverter();

  @Test
  void convertToDatabaseColumn_returnsNullIfInputIsNull() {
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
  }

  @Test
  void convertToDatabaseColumn_convertsCorrectly() {
    assertThat(
            converter.convertToDatabaseColumn(
                Key.create(getCrossTldKey(), ReservedList.class, "testList")))
        .isEqualTo("testList");
  }

  @Test
  void convertToEntityAttribute_returnsNullIfInputIsNull() {
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  @Test
  void convertToEntityAttribute_convertsCorrectly() {
    assertThat(converter.convertToEntityAttribute("testList"))
        .isEqualTo(Key.create(getCrossTldKey(), ReservedList.class, "testList"));
  }

  @Test
  void testRoundTrip() {
    Key<ReservedList> key = Key.create(getCrossTldKey(), ReservedList.class, "test");
    ReservedListEntity testEntity = new ReservedListEntity(key);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    ReservedListEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(ReservedListEntity.class, "test"));
    assertThat(persisted.reservedList).isEqualTo(key);
  }

  @Entity(name = "ReservedListEntity")
  private static class ReservedListEntity extends ImmutableObject {

    @Id String name;

    Key<ReservedList> reservedList;

    public ReservedListEntity() {}

    ReservedListEntity(Key<ReservedList> reservedList) {
      this.name = reservedList.getName();
      this.reservedList = reservedList;
    }
  }
}
