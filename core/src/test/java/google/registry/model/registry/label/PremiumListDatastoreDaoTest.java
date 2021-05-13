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

package google.registry.model.registry.label;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth8.assertThat;
import static com.google.monitoring.metrics.contrib.DistributionMetricSubject.assertThat;
import static com.google.monitoring.metrics.contrib.LongMetricSubject.assertThat;
import static google.registry.model.ofy.ObjectifyService.auditedOfy;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.BLOOM_FILTER_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.CACHED_POSITIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_NEGATIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.PremiumListCheckOutcome.UNCACHED_POSITIVE;
import static google.registry.model.registry.label.DomainLabelMetrics.premiumListChecks;
import static google.registry.model.registry.label.DomainLabelMetrics.premiumListProcessingTime;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadPremiumListEntries;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.joda.time.Duration.standardDays;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.googlecode.objectify.Key;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumList.PremiumListRevision;
import google.registry.testing.AppEngineExtension;
import google.registry.testing.TestCacheExtension;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumListDatastoreDao}. */
public class PremiumListDatastoreDaoTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  // Set long persist times on caches so they can be tested (cache times default to 0 in tests).
  @RegisterExtension
  public final TestCacheExtension testCacheExtension =
      new TestCacheExtension.Builder()
          .withPremiumListsCache(standardDays(1))
          .withPremiumListEntriesCache(standardDays(1))
          .build();

  private PremiumList pl;

  @BeforeEach
  void before() {
    // createTld() overwrites the premium list, so call it before persisting pl.
    createTld("tld");
    pl =
        persistPremiumList(
            "tld",
            "lol,USD 999 # yup",
            "rich,USD 1999 #tada",
            "icann,JPY 100",
            "johnny-be-goode,USD 20.50");
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
    premiumListChecks.reset();
    premiumListProcessingTime.reset();
  }

  void assertMetricOutcomeCount(
      int checkCount, DomainLabelMetrics.PremiumListCheckOutcome outcome) {
    assertThat(premiumListChecks)
        .hasValueForLabels(checkCount, "tld", "tld", outcome.toString())
        .and()
        .hasNoOtherValues();
    assertThat(premiumListProcessingTime)
        .hasAnyValueForLabels("tld", "tld", outcome.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSave_largeNumberOfEntries_succeeds() {
    PremiumList premiumList = persistHumongousPremiumList("tld", 2500);
    assertThat(loadPremiumListEntries(premiumList)).hasSize(2500);
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "7", "tld"))
        .hasValue(Money.parse("USD 100"));
    assertMetricOutcomeCount(1, UNCACHED_POSITIVE);
  }

  @Test
  void testSave_updateTime_isUpdatedOnEverySave() {
    PremiumList pl = PremiumListDatastoreDao.save("tld3", ImmutableList.of("slime,USD 10"));
    PremiumList newPl = PremiumListDatastoreDao.save("tld3", ImmutableList.of("mutants,USD 20"));
    assertThat(newPl.getLastUpdateTime()).isGreaterThan(pl.getLastUpdateTime());
  }

  @Test
  void testSave_creationTime_onlyUpdatedOnFirstCreation() {
    PremiumList pl = persistPremiumList("tld3", "sludge,JPY 1000");
    PremiumList newPl =
        PremiumListDatastoreDao.save("tld3", ImmutableList.of("sleighbells,CHF 2000"));
    assertThat(newPl.creationTime).isEqualTo(pl.creationTime);
  }

  @Test
  void testExists() {
    assertThat(PremiumListDatastoreDao.getLatestRevision("tld")).isPresent();
    assertThat(PremiumListDatastoreDao.getLatestRevision("nonExistentPremiumList")).isEmpty();
  }

  @Test
  void testGetPremiumPrice_comesFromBloomFilter() throws Exception {
    PremiumList pl = PremiumListDatastoreDao.getLatestRevision("tld").get();
    PremiumListEntry entry =
        persistResource(
            new PremiumListEntry.Builder()
                .setParent(pl.getRevisionKey())
                .setLabel("missingno")
                .setPrice(Money.parse("USD 1000"))
                .build());
    // "missingno" shouldn't be in the Bloom filter, thus it should return not premium without
    // attempting to load the entity that is actually present.
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "missingno", "tld")).isEmpty();
    // However, if we manually query the cache to force an entity load, it should be found.
    assertThat(
            PremiumListDatastoreDao.premiumListEntriesCache.get(
                Key.create(pl.getRevisionKey(), PremiumListEntry.class, "missingno")))
        .hasValue(entry);
    assertMetricOutcomeCount(1, BLOOM_FILTER_NEGATIVE);
  }

  @Test
  void testGetPremiumPrice_cachedSecondTime() {
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "rich", "tld"))
        .hasValue(Money.parse("USD 1999"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "rich", "tld"))
        .hasValue(Money.parse("USD 1999"));
    assertThat(premiumListChecks)
        .hasValueForLabels(1, "tld", "tld", UNCACHED_POSITIVE.toString())
        .and()
        .hasValueForLabels(1, "tld", "tld", CACHED_POSITIVE.toString())
        .and()
        .hasNoOtherValues();
    assertThat(premiumListProcessingTime)
        .hasAnyValueForLabels("tld", "tld", UNCACHED_POSITIVE.toString())
        .and()
        .hasAnyValueForLabels("tld", "tld", CACHED_POSITIVE.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testGetPremiumPrice_bloomFilterFalsePositive() {
    // Remove one of the premium list entries from behind the Bloom filter's back.
    tm().transactNew(
            () ->
                auditedOfy()
                    .delete()
                    .keys(Key.create(pl.getRevisionKey(), PremiumListEntry.class, "rich")));
    auditedOfy().clearSessionCache();

    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "rich", "tld")).isEmpty();
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "rich", "tld")).isEmpty();

    assertThat(premiumListChecks)
        .hasValueForLabels(1, "tld", "tld", UNCACHED_NEGATIVE.toString())
        .and()
        .hasValueForLabels(1, "tld", "tld", CACHED_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
    assertThat(premiumListProcessingTime)
        .hasAnyValueForLabels("tld", "tld", UNCACHED_NEGATIVE.toString())
        .and()
        .hasAnyValueForLabels("tld", "tld", CACHED_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testSave_removedPremiumListEntries_areNoLongerInDatastore() {
    PremiumList pl = persistPremiumList("tld", "genius,USD 10", "dolt,JPY 1000");
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "genius", "tld"))
        .hasValue(Money.parse("USD 10"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "dolt", "tld"))
        .hasValue(Money.parse("JPY 1000"));
    assertThat(
            auditedOfy()
                .load()
                .type(PremiumListEntry.class)
                .parent(pl.getRevisionKey())
                .id("dolt")
                .now()
                .price)
        .isEqualTo(Money.parse("JPY 1000"));
    PremiumListDatastoreDao.save("tld", ImmutableList.of("genius,USD 10", "savant,USD 90"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "genius", "tld"))
        .hasValue(Money.parse("USD 10"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "savant", "tld"))
        .hasValue(Money.parse("USD 90"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "dolt", "tld")).isEmpty();
    // TODO(b/79888775): Assert that the old premium list is enqueued for later deletion.
    assertThat(premiumListChecks)
        .hasValueForLabels(4, "tld", "tld", UNCACHED_POSITIVE.toString())
        .and()
        .hasValueForLabels(1, "tld", "tld", BLOOM_FILTER_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
    assertThat(premiumListProcessingTime)
        .hasAnyValueForLabels("tld", "tld", UNCACHED_POSITIVE.toString())
        .and()
        .hasAnyValueForLabels("tld", "tld", BLOOM_FILTER_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void testGetPremiumPrice_allLabelsAreNonPremium_whenNotInList() {
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "blah", "tld")).isEmpty();
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld", "slinge", "tld")).isEmpty();
    assertMetricOutcomeCount(2, BLOOM_FILTER_NEGATIVE);
  }

  @Test
  void testSave_simple() {
    PremiumList pl =
        PremiumListDatastoreDao.save("tld2", ImmutableList.of("lol , USD 999 # yupper rooni "));
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld2", "lol", "tld"))
        .hasValue(Money.parse("USD 999"));
    assertThat(PremiumListDatastoreDao.getPremiumPrice("tld2", "lol ", "tld")).isEmpty();
    ImmutableMap<String, PremiumListEntry> entries =
        Streams.stream(PremiumListDatastoreDao.loadPremiumListEntriesUncached(pl))
            .collect(toImmutableMap(PremiumListEntry::getLabel, Function.identity()));
    assertThat(entries.keySet()).containsExactly("lol");
    assertThat(entries).doesNotContainKey("lol ");
    PremiumListEntry entry = entries.get("lol");
    assertThat(entry.comment).isEqualTo("yupper rooni");
    assertThat(entry.price).isEqualTo(Money.parse("USD 999"));
    assertThat(entry.label).isEqualTo("lol");
    assertThat(premiumListChecks)
        .hasValueForLabels(1, "tld", "tld2", UNCACHED_POSITIVE.toString())
        .and()
        .hasValueForLabels(1, "tld", "tld2", BLOOM_FILTER_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
    assertThat(premiumListProcessingTime)
        .hasAnyValueForLabels("tld", "tld2", UNCACHED_POSITIVE.toString())
        .and()
        .hasAnyValueForLabels("tld", "tld2", BLOOM_FILTER_NEGATIVE.toString())
        .and()
        .hasNoOtherValues();
  }

  @Test
  void test_saveAndUpdateEntriesTwice() {
    PremiumList firstSave = PremiumListDatastoreDao.save("tld", ImmutableList.of("test,USD 1"));
    ImmutableMap<String, PremiumListEntry> entries =
        Streams.stream(PremiumListDatastoreDao.loadPremiumListEntriesUncached(firstSave))
            .collect(toImmutableMap(PremiumListEntry::getLabel, Function.identity()));
    assertThat(entries.keySet()).containsExactly("test");
    // Save again with no changes, and clear the cache to force a re-load from Datastore.
    PremiumList resaved = PremiumListDatastoreDao.save("tld", ImmutableList.of("test,USD 1"));
    auditedOfy().clearSessionCache();
    Map<String, PremiumListEntry> entriesReloaded =
        Streams.stream(PremiumListDatastoreDao.loadPremiumListEntriesUncached(resaved))
            .collect(toImmutableMap(PremiumListEntry::getLabel, Function.identity()));
    assertThat(entriesReloaded).hasSize(1);
    assertThat(entriesReloaded).containsKey("test");
    assertThat(entriesReloaded.get("test").parent).isEqualTo(resaved.getRevisionKey());
  }

  @Test
  void testDelete() {
    persistPremiumList("gtld1", "trombone,USD 10");
    Optional<PremiumList> gtld1 = PremiumListDatastoreDao.getLatestRevision("gtld1");
    assertThat(gtld1).isPresent();
    Key<PremiumListRevision> parent = gtld1.get().getRevisionKey();
    PremiumListDatastoreDao.delete(gtld1.get());
    assertThat(PremiumListDatastoreDao.getLatestRevision("gtld1")).isEmpty();
    assertThat(auditedOfy().load().type(PremiumListEntry.class).ancestor(parent).list()).isEmpty();
  }

  @Test
  void testDelete_largeNumberOfEntries_succeeds() {
    PremiumList large = persistHumongousPremiumList("ginormous", 2500);
    PremiumListDatastoreDao.delete(large);
    assertThat(PremiumListDatastoreDao.getLatestRevision("ginormous")).isEmpty();
  }

  @Test
  void test_savePremiumList_clearsCache() {
    assertThat(PremiumListDatastoreDao.premiumListCache.getIfPresent("tld")).isNull();
    PremiumList pl = PremiumListDatastoreDao.getLatestRevision("tld").get();
    assertThat(PremiumListDatastoreDao.premiumListCache.getIfPresent("tld").get()).isEqualTo(pl);
    transactIfJpaTm(() -> PremiumListDatastoreDao.save("tld", ImmutableList.of("test,USD 1")));
    assertThat(PremiumListDatastoreDao.premiumListCache.getIfPresent("tld")).isNull();
  }

  /** Persists a premium list with a specified number of nonsense entries. */
  private PremiumList persistHumongousPremiumList(String name, int size) {
    String[] entries = new String[size];
    for (int i = 0; i < size; i++) {
      entries[i] = String.format("%d,USD 100 # blahz", i);
    }
    return persistPremiumList(name, entries);
  }
}
