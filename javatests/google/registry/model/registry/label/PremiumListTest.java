// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.PremiumList.getPremiumPrice;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.googlecode.objectify.Key;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PremiumList}. */
@RunWith(JUnit4.class)
public class PremiumListTest {

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Before
  public void before() throws Exception {
    PremiumList pl = persistPremiumList(
        "tld",
        "lol,USD 999 # yup",
        "rich,USD 1999 #tada",
        "icann,JPY 100",
        "johnny-be-goode,USD 20.50");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
  }

  @Test
  public void testGetPremiumPrice_returnsNoPriceWhenNoPremiumListConfigured() throws Exception {
    createTld("ghost");
    persistResource(
        new Registry.Builder()
            .setTldStr("ghost")
            .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
            .build());
    assertThat(Registry.get("ghost").getPremiumList()).isNull();
    assertThat(getPremiumPrice("blah", "ghost")).isAbsent();
  }

  @Test
  public void testGetPremiumPrice_throwsExceptionWhenNonExistentPremiumListConfigured()
      throws Exception {
    PremiumList.get("tld").get().delete();
    thrown.expect(IllegalStateException.class, "Could not load premium list named tld");
    getPremiumPrice("blah", "tld");
  }

  @Test
  public void testSave_largeNumberOfEntries_succeeds() throws Exception {
    PremiumList premiumList = persistHumongousPremiumList("tld", 2500);
    assertThat(premiumList.getPremiumListEntries()).hasSize(2500);
    assertThat(premiumList.getPremiumPrice("7")).hasValue(Money.parse("USD 100"));
  }

  @Test
  public void testSave_updateTime_isUpdatedOnEverySave() throws Exception {
    PremiumList pl =  new PremiumList.Builder()
        .setName("tld3")
        .setPremiumListMapFromLines(ImmutableList.of("slime,USD 10"))
        .build()
        .saveAndUpdateEntries();
    PremiumList newPl = new PremiumList.Builder()
        .setName(pl.getName())
        .setPremiumListMapFromLines(ImmutableList.of("mutants,USD 20"))
        .build()
        .saveAndUpdateEntries();
    assertThat(newPl.getLastUpdateTime()).isGreaterThan(pl.getLastUpdateTime());
  }

  @Test
  public void testSave_creationTime_onlyUpdatedOnFirstCreation() throws Exception {
    PremiumList pl = persistPremiumList("tld3", "sludge,JPY 1000");
    DateTime creationTime = pl.creationTime;
    pl = pl.asBuilder()
        .setPremiumListMapFromLines(ImmutableList.of("sleighbells,CHF 2000"))
        .build();
    assertThat(pl.creationTime).isEqualTo(creationTime);
  }

  @Test
  public void testSave_removedPremiumListEntries_areNoLongerInDatastore() throws Exception {
    PremiumList pl = persistPremiumList("tld", "genius,USD 10", "dolt,JPY 1000");
    assertThat(getPremiumPrice("genius", "tld")).hasValue(Money.parse("USD 10"));
    assertThat(getPremiumPrice("dolt", "tld")).hasValue(Money.parse("JPY 1000"));
    assertThat(ofy()
            .load()
            .type(PremiumListEntry.class)
            .parent(pl.getRevisionKey())
            .id("dolt")
            .now()
            .price)
        .isEqualTo(Money.parse("JPY 1000"));
    PremiumList pl2 = pl.asBuilder()
        .setPremiumListMapFromLines(ImmutableList.of("genius,USD 10", "savant,USD 90"))
        .build()
        .saveAndUpdateEntries();
    assertThat(getPremiumPrice("genius", "tld")).hasValue(Money.parse("USD 10"));
    assertThat(getPremiumPrice("savant", "tld")).hasValue(Money.parse("USD 90"));
    assertThat(getPremiumPrice("dolt", "tld")).isAbsent();
    assertThat(ofy()
            .load()
            .type(PremiumListEntry.class)
            .parent(pl.getRevisionKey())
            .id("dolt")
            .now())
        .isNull();
    assertThat(ofy()
            .load()
            .type(PremiumListEntry.class)
            .parent(pl2.getRevisionKey())
            .id("dolt")
            .now())
        .isNull();
  }

  @Test
  public void testGetPremiumPrice_allLabelsAreNonPremium_whenNotInList() throws Exception {
    assertThat(getPremiumPrice("blah", "tld")).isAbsent();
    assertThat(getPremiumPrice("slinge", "tld")).isAbsent();
  }

  @Test
  public void testSave_simple() throws Exception {
    PremiumList pl = persistPremiumList("tld2", "lol , USD 999 # yupper rooni ");
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
    assertThat(pl.getPremiumPrice("lol")).hasValue(Money.parse("USD 999"));
    assertThat(getPremiumPrice("lol", "tld")).hasValue(Money.parse("USD 999"));
    assertThat(getPremiumPrice("lol ", "tld")).isAbsent();
    Map<String, PremiumListEntry> entries = PremiumList.get("tld2").get().getPremiumListEntries();
    assertThat(entries.keySet()).containsExactly("lol");
    assertThat(entries).doesNotContainKey("lol ");
    PremiumListEntry entry = entries.values().iterator().next();
    assertThat(entry.comment).isEqualTo("yupper rooni");
    assertThat(entry.price).isEqualTo(Money.parse("USD 999"));
    assertThat(entry.label).isEqualTo("lol");
  }

  @Test
  public void test_saveAndUpdateEntries_twiceOnUnchangedList() throws Exception {
    PremiumList pl =
        new PremiumList.Builder()
            .setName("pl")
            .setPremiumListMapFromLines(ImmutableList.of("test,USD 1"))
            .build()
            .saveAndUpdateEntries();
    Map<String, PremiumListEntry> entries = pl.getPremiumListEntries();
    assertThat(entries.keySet()).containsExactly("test");
    assertThat(PremiumList.get("pl").get().getPremiumListEntries()).isEqualTo(entries);
    // Save again with no changes, and clear the cache to force a re-load from datastore.
    pl.saveAndUpdateEntries();
    ofy().clearSessionCache();
    assertThat(PremiumList.get("pl").get().getPremiumListEntries()).isEqualTo(entries);
  }

  @Test
  public void test_saveAndUpdateEntries_twiceOnListWithOnlyMetadataChanges() throws Exception {
    PremiumList pl =
        new PremiumList.Builder()
            .setName("pl")
            .setPremiumListMapFromLines(ImmutableList.of("test,USD 1"))
            .build()
            .saveAndUpdateEntries();
    Map<String, PremiumListEntry> entries = pl.getPremiumListEntries();
    assertThat(entries.keySet()).containsExactly("test");
    assertThat(PremiumList.get("pl").get().getPremiumListEntries()).isEqualTo(entries);
    // Save again with description changed, and clear the cache to force a re-load from datastore.
    pl.asBuilder().setDescription("foobar").build().saveAndUpdateEntries();
    ofy().clearSessionCache();
    assertThat(PremiumList.get("pl").get().getPremiumListEntries()).isEqualTo(entries);
  }

  @Test
  public void testDelete() throws Exception {
    persistPremiumList("gtld1", "trombone,USD 10");
    assertThat(PremiumList.get("gtld1")).isPresent();
    Key<BasePremiumList.PremiumListRevision> parent = PremiumList.get("gtld1").get().getRevisionKey();
    PremiumList.get("gtld1").get().delete();
    assertThat(PremiumList.get("gtld1")).isAbsent();
    assertThat(ofy().load().type(PremiumListEntry.class).ancestor(parent).list()).isEmpty();
  }

  @Test
  public void testDelete_largeNumberOfEntries_succeeds() {
    persistHumongousPremiumList("ginormous", 2500);
    PremiumList.get("ginormous").get().delete();
    assertThat(PremiumList.get("ginormous")).isAbsent();
  }

  @Test
  public void testDelete_failsWhenListDoesntExist() throws Exception {
    thrown.expect(IllegalStateException.class);
    PremiumList.get("tld-premium-blah").get().delete();
  }

  @Test
  public void testSave_badSyntax() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    persistPremiumList("gtld1", "lol,nonsense USD,e,e # yup");
  }

  @Test
  public void testSave_invalidCurrencySymbol() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    persistReservedList("gtld1", "lol,XBTC 200");
  }

  @Test
  public void testExists() throws Exception {
    assertThat(PremiumList.exists("tld")).isTrue();
    assertThat(PremiumList.exists("nonExistentPremiumList")).isFalse();
  }

  @Test
  public void testAsBuilder_updatingEntitiesreplacesRevisionKey() throws Exception {
    PremiumList pl = PremiumList.get("tld").get();
    assertThat(pl.asBuilder()
        .setPremiumListMapFromLines(ImmutableList.of("qux,USD 123"))
        .build()
        .getRevisionKey())
            .isNotEqualTo(pl.getRevisionKey());
  }

  /** Persists a premium list with a specified number of nonsense entries. */
  private PremiumList persistHumongousPremiumList(String name, int size) {
    String[] entries = new String[size];
    for (int i = 0; i < size; i++) {
      entries[i] = String.format("%d,USD 100 # blahz", i);
    }
    return persistPremiumList(name, entries);
  }

  /** Gets the label of a premium list entry. */
  public static final Function<Key<PremiumListEntry>, String> GET_ENTRY_NAME_FUNCTION =
      new Function<Key<PremiumListEntry>, String>() {
        @Override
        public String apply(final Key<PremiumListEntry> input) {
          return input.getName();
        }};
}
