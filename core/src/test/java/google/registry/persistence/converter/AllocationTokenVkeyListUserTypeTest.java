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

package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.insertInDb;

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import org.hibernate.annotations.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AllocationTokenVkeyListUserType}. */
public class AllocationTokenVkeyListUserTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(TestAllocationTokenVKeyList.class)
          .buildUnitTestExtension();

  @Test
  void testRoundTrip() {
    AllocationToken token1 =
        new AllocationToken().asBuilder().setToken("abc123").setTokenType(SINGLE_USE).build();
    AllocationToken token2 =
        new AllocationToken().asBuilder().setToken("token").setTokenType(UNLIMITED_USE).build();
    List<VKey<AllocationToken>> tokens = ImmutableList.of(token1.createVKey(), token2.createVKey());
    TestAllocationTokenVKeyList testAllocationTokenVKeyList =
        new TestAllocationTokenVKeyList(tokens);
    insertInDb(testAllocationTokenVKeyList);
    TestAllocationTokenVKeyList persisted =
        tm().transact(() -> tm().getEntityManager().find(TestAllocationTokenVKeyList.class, "id"));
    assertThat(persisted.tokenList).isEqualTo(tokens);
  }

  @Entity(name = "TestAllocationTokenVKeyList")
  static class TestAllocationTokenVKeyList extends ImmutableObject {
    @Id String id = "id";

    @Type(AllocationTokenVkeyListUserType.class)
    List<VKey<AllocationToken>> tokenList;

    TestAllocationTokenVKeyList() {}

    TestAllocationTokenVKeyList(List<VKey<AllocationToken>> tokenList) {
      this.tokenList = tokenList;
    }
  }
}
