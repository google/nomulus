// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

package google.registry.model.registry.label;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.Duration.standardDays;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.truth.Truth8;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.EntityTestCase;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registry.Registry;
import google.registry.testing.TestCacheExtension;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumListDualDao}. */
public class PremiumListDualDaoTest extends EntityTestCase {

  // Set long persist times on caches so they can be tested (cache times default to 0 in tests).
  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder()
          .withPremiumListsCache(standardDays(1))
          .withPremiumListEntriesCache(standardDays(1))
          .build();

  @BeforeEach
  void before() {
    createTld("tld");
    fakeClock.setAutoIncrementStep(Duration.millis(1));
  }

  @AfterEach
  void after() {
    fakeClock.setAutoIncrementStep(Duration.ZERO);
  }

  @Test
  void testGetPremiumPrice_secondaryLoadMissingOfy() {
    PremiumList premiumList = PremiumListDatastoreDao.getLatestRevision("tld").get();
    PremiumListDatastoreDao.delete(premiumList);
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () -> PremiumListDualDao.getPremiumPrice("brass", Registry.get("tld"))))
        .hasMessageThat()
        .isEqualTo(
            "Unequal prices for domain brass.tld from primary SQL DB (Optional[USD 20.00]) "
                + "and secondary Datastore db (Optional.empty).");
  }

  @Test
  void testGetPremiumPrice_secondaryDifferentOfy() {
    PremiumListDatastoreDao.save("tld", ImmutableList.of("brass,USD 50"));
    assertThat(
            assertThrows(
                IllegalStateException.class,
                () -> PremiumListDualDao.getPremiumPrice("brass", Registry.get("tld"))))
        .hasMessageThat()
        .isEqualTo(
            "Unequal prices for domain brass.tld from primary SQL DB "
                + "(Optional[USD 20.00]) and secondary Datastore db (Optional[USD 50.00]).");
  }

  @Test
  void testGetPremiumPrice_returnsNoPriceWhenNoPremiumListConfigured() {
    createTld("ghost");
    persistResource(
        new Registry.Builder()
            .setTldStr("ghost")
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
            .build());
    assertThat(Registry.get("ghost").getPremiumList()).isNull();
    Truth8.assertThat(PremiumListDualDao.getPremiumPrice("blah", Registry.get("ghost"))).isEmpty();
  }

  @Test
  void testGetPremiumPrice_emptyWhenPremiumListDeleted() {
    PremiumList toDelete = PremiumListDualDao.getLatestRevision("tld").get();
    PremiumListDualDao.delete(toDelete);
    Truth8.assertThat(PremiumListDualDao.getPremiumPrice("blah", Registry.get("tld"))).isEmpty();
  }

  @Test
  void getPremiumPrice_returnsNoneWhenNoPremiumListConfigured() {
    persistResource(newRegistry("foobar", "FOOBAR").asBuilder().setPremiumList(null).build());
    Truth8.assertThat(PremiumListDualDao.getPremiumPrice("rich", Registry.get("foobar"))).isEmpty();
  }
}
