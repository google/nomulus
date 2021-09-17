// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Charsets.US_ASCII;
import static com.google.common.hash.Funnels.stringFunnel;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.jpaTm;
import static google.registry.testing.DatabaseHelper.insertInDb;

import com.google.common.collect.ImmutableSet;
import com.google.common.hash.BloomFilter;
import google.registry.model.ImmutableObject;
import google.registry.model.replay.EntityTest.EntityForTesting;
import google.registry.persistence.transaction.JpaTestRules;
import google.registry.persistence.transaction.JpaTestRules.JpaUnitTestExtension;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link BloomFilterConverter}. */
class BloomFilterConverterTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestRules.Builder().withEntityClass(TestEntity.class).buildUnitTestRule();

  @Test
  void roundTripConversion_returnsSameBloomFilter() {
    BloomFilter<String> bloomFilter = BloomFilter.create(stringFunnel(US_ASCII), 3);
    ImmutableSet.of("foo", "bar", "baz").forEach(bloomFilter::put);
    TestEntity entity = new TestEntity(bloomFilter);
    insertInDb(entity);
    TestEntity persisted =
        jpaTm().transact(() -> jpaTm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.bloomFilter).isEqualTo(bloomFilter);
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  @EntityForTesting
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    BloomFilter<String> bloomFilter;

    public TestEntity() {}

    TestEntity(BloomFilter<String> bloomFilter) {
      this.bloomFilter = bloomFilter;
    }
  }
}
