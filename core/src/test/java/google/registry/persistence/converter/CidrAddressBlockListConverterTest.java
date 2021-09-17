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

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import google.registry.util.CidrAddressBlock;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link CidrAddressBlockListConverter}. */
public class CidrAddressBlockListConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

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
    insertInDb(testEntity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.addresses).isEqualTo(addresses);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    List<CidrAddressBlock> addresses;

    private TestEntity() {}

    private TestEntity(List<CidrAddressBlock> addresses) {
      this.addresses = addresses;
    }
  }
}
