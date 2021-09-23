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

import com.google.common.collect.ImmutableSet;
import google.registry.model.ImmutableObject;
import google.registry.model.eppcommon.StatusValue;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.util.Set;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link StatusValueSetConverter}. */
public class StatusValueSetConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void testRoundTrip() {
    Set<StatusValue> enums = ImmutableSet.of(StatusValue.INACTIVE, StatusValue.PENDING_DELETE);
    TestEntity obj = new TestEntity("foo", enums);

    insertInDb(obj);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "foo"));
    assertThat(persisted.data).isEqualTo(enums);
  }

  @Entity(name = "TestEntity")
  static class TestEntity extends ImmutableObject {
    @Id String name;

    Set<StatusValue> data;

    TestEntity() {}

    TestEntity(String name, Set<StatusValue> data) {
      this.name = name;
      this.data = data;
    }
  }
}
