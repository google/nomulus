// Copyright 2017 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.index;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import google.registry.model.EntityTestCase;
import google.registry.model.host.Host;
import google.registry.testing.TestCacheExtension;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link ForeignKeyIndex}. */
class ForeignKeyIndexTest extends EntityTestCase {

  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder().withForeignIndexKeyCache(Duration.ofDays(1)).build();

  @BeforeEach
  void setUp() {
    createTld("com");
  }

  @Test
  void testLoadForNonexistentForeignKey_returnsNull() {
    assertThat(ForeignKeyIndex.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testLoadForDeletedForeignKey_returnsNull() {
    Host host = persistActiveHost("ns1.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(ForeignKeyIndex.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testLoad_newerKeyHasBeenSoftDeleted() {
    Host host1 = persistActiveHost("ns1.example.com");
    fakeClock.advanceOneMilli();
    persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    assertThat(ForeignKeyIndex.load(Host.class, "ns1.example.com", fakeClock.nowUtc())).isNull();
  }

  @Test
  void testBatchLoad_skipsDeletedAndNonexistent() {
    persistActiveHost("ns1.example.com");
    Host host = persistActiveHost("ns2.example.com");
    persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    assertThat(
            ForeignKeyIndex.load(
                    Host.class,
                    ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                    fakeClock.nowUtc())
                .keySet())
        .containsExactly("ns1.example.com");
  }

  @Test
  void testDeadCodeThatDeletedScrapCommandsReference() {
    persistActiveHost("omg");
    assertThat(ForeignKeyIndex.load(Host.class, "omg", fakeClock.nowUtc()).getForeignKey())
        .isEqualTo("omg");
  }
}
