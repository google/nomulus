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
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;

import google.registry.model.ImmutableObject;
import google.registry.model.domain.token.AllocationToken;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import java.util.Set;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.com.google.common.collect.ImmutableSet;

/** Unit tests for {@link google.registry.persistence.converter.AllocationTokenSetConverter}. */
public class AllocationTokenSetConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(TestAllocationTokenVKeySet.class)
          .buildUnitTestExtension();

  @Test
  void testRoundTrip() {
    AllocationToken token1 =
        new AllocationToken().asBuilder().setToken("abc123").setTokenType(SINGLE_USE).build();
    AllocationToken token2 =
        new AllocationToken().asBuilder().setToken("token").setTokenType(UNLIMITED_USE).build();
    Set<VKey<AllocationToken>> tokens = ImmutableSet.of(token1.createVKey(), token2.createVKey());
    TestAllocationTokenVKeySet testAllocationTokenVKeySet = new TestAllocationTokenVKeySet(tokens);
    insertInDb(testAllocationTokenVKeySet);
    TestAllocationTokenVKeySet persisted =
        jpaTm()
            .transact(
                () -> jpaTm().getEntityManager().find(TestAllocationTokenVKeySet.class, "id"));
    assertThat(persisted.tokenSet).isEqualTo(tokens);
  }

  @Entity(name = "TestAllocationTokenVKeySet")
  static class TestAllocationTokenVKeySet extends ImmutableObject {
    @Id String id = "id";

    Set<VKey<AllocationToken>> tokenSet;

    TestAllocationTokenVKeySet() {}

    TestAllocationTokenVKeySet(Set<VKey<AllocationToken>> tokenSet) {
      this.tokenSet = tokenSet;
    }
  }
}
