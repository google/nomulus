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
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistResource;

import com.google.common.collect.ImmutableList;
import google.registry.model.EntityTestCase;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.index.ForeignKeyIndex.ForeignKeyHostIndex;
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
  void testModifyForeignKeyIndex_notThrowExceptionInSql() {
    DomainBase domainBase = newDomainBase("test.com");
    ForeignKeyIndex<DomainBase> fki = ForeignKeyIndex.create(domainBase, fakeClock.nowUtc());
    tm().transact(() -> tm().insert(fki));
    tm().transact(() -> tm().put(fki));
    tm().transact(() -> tm().delete(fki));
    tm().transact(() -> tm().update(fki));
  }

  @Test
  void testLoadForNonexistentForeignKey_returnsNull() {
    assertThat(ForeignKeyIndex.load(HostResource.class, "ns1.example.com", fakeClock.nowUtc()))
        .isNull();
  }

  @Test
  void testLoadForDeletedForeignKey_returnsNull() {
    HostResource host = persistActiveHost("ns1.example.com");
    if (tm().isOfy()) {
      persistResource(ForeignKeyIndex.create(host, fakeClock.nowUtc().minusDays(1)));
    } else {
      persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    }
    assertThat(ForeignKeyIndex.load(HostResource.class, "ns1.example.com", fakeClock.nowUtc()))
        .isNull();
  }

  @Test
  void testLoad_newerKeyHasBeenSoftDeleted() {
    HostResource host1 = persistActiveHost("ns1.example.com");
    fakeClock.advanceOneMilli();
    if (tm().isOfy()) {
      ForeignKeyHostIndex fki = new ForeignKeyHostIndex();
      fki.foreignKey = "ns1.example.com";
      fki.topReference = host1.createVKey();
      fki.deletionTime = fakeClock.nowUtc();
      persistResource(fki);
    } else {
      persistResource(host1.asBuilder().setDeletionTime(fakeClock.nowUtc()).build());
    }
    assertThat(ForeignKeyIndex.load(HostResource.class, "ns1.example.com", fakeClock.nowUtc()))
        .isNull();
  }

  @Test
  void testBatchLoad_skipsDeletedAndNonexistent() {
    persistActiveHost("ns1.example.com");
    HostResource host = persistActiveHost("ns2.example.com");
    if (tm().isOfy()) {
      persistResource(ForeignKeyIndex.create(host, fakeClock.nowUtc().minusDays(1)));
    } else {
      persistResource(host.asBuilder().setDeletionTime(fakeClock.nowUtc().minusDays(1)).build());
    }
    assertThat(
            ForeignKeyIndex.load(
                    HostResource.class,
                    ImmutableList.of("ns1.example.com", "ns2.example.com", "ns3.example.com"),
                    fakeClock.nowUtc())
                .keySet())
        .containsExactly("ns1.example.com");
  }

  @Test
  void testDeadCodeThatDeletedScrapCommandsReference() {
    persistActiveHost("omg");
    assertThat(ForeignKeyIndex.load(HostResource.class, "omg", fakeClock.nowUtc()).getForeignKey())
        .isEqualTo("omg");
  }
}
