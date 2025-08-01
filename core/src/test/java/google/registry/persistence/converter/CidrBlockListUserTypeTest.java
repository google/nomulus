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

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import google.registry.util.CidrAddressBlock;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CidrBlockListUserType}. */
public class CidrBlockListUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameCidrAddressBlock() {
    List<CidrAddressBlock> addresses =
        ImmutableList.of(
            CidrAddressBlock.create("0.0.0.0/32"),
            CidrAddressBlock.create("255.255.255.254/31"),
            CidrAddressBlock.create("::"),
            CidrAddressBlock.create("8000::/1"),
            CidrAddressBlock.create("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128"));
    TestEntity testEntity = new TestEntity(addresses);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.addresses).isEqualTo(addresses);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(CidrBlockListUserType.class)
    List<CidrAddressBlock> addresses;

    private TestEntity() {}

    private TestEntity(List<CidrAddressBlock> addresses) {
      this.addresses = addresses;
    }
  }
}
