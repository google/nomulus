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

package google.registry.persistence;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestRule;
import google.registry.util.CidrAddressBlock;
import java.util.List;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.hibernate.annotations.Type;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CidrAddressBlockListUserType}. */
@RunWith(JUnit4.class)
public class CidrAddressBlockListUserTypeTest {
  @Rule
  public final JpaUnitTestRule jpaRule =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  public void roundTripConversion_returnsSameCidrAddressBlock() {
    List<CidrAddressBlock> addresses =
        ImmutableList.of(
            CidrAddressBlock.create("0.0.0.0/32"),
            CidrAddressBlock.create("255.255.255.254/31"),
            CidrAddressBlock.create("::"),
            CidrAddressBlock.create("8000::/1"),
            CidrAddressBlock.create("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128"));
    TestEntity testEntity = new TestEntity(addresses);
    jpaTm().transact(() -> jpaTm().getEntityManager().persist(testEntity));
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.addresses).isEqualTo(addresses);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    @Type(type = "google.registry.persistence.CidrAddressBlockListUserType")
    List<CidrAddressBlock> addresses;

    private TestEntity() {}

    private TestEntity(List<CidrAddressBlock> addresses) {
      this.addresses = addresses;
    }
  }
}
