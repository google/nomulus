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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import google.registry.model.ImmutableObject;
import google.registry.model.console.RegistrarRole;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.testing.DatabaseHelper;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.Map;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for {@link RegistrarToRoleMapUserType}. */
public class RegistrarToRoleMapUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpa =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void testRoundTripConversion() {
    Map<String, RegistrarRole> map =
        ImmutableMap.of(
            "TheRegistrar",
            RegistrarRole.ACCOUNT_MANAGER,
            "NewRegistrar",
            RegistrarRole.PRIMARY_CONTACT,
            "FooRegistrar",
            RegistrarRole.TECH_CONTACT);
    TestEntity entity = new TestEntity(map);
    DatabaseHelper.persistResource(entity);
    TestEntity persisted = Iterables.getOnlyElement(DatabaseHelper.loadAllOf(TestEntity.class));
    assertThat(persisted.map).isEqualTo(map);
  }

  @Entity(name = "TestEntity")
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(RegistrarToRoleMapUserType.class)
    Map<String, RegistrarRole> map;

    private TestEntity() {}

    private TestEntity(Map<String, RegistrarRole> map) {
      this.map = map;
    }
  }
}
