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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.testing.AppEngineExtension;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link PremiumList}. */
public class PremiumListTest {

  @RegisterExtension
  public final AppEngineExtension appEngine =
      AppEngineExtension.builder().withDatastoreAndCloudSql().build();

  @BeforeEach
  void before() {
    // createTld() overwrites the premium list, so call it first.
    createTld("tld");
    PremiumList pl =
        persistPremiumList(
            "tld",
            "lol,USD 999 # yup",
            "rich,USD 1999 #tada",
            "icann,JPY 100",
            "johnny-be-goode,USD 20.50");
    persistResource(Registry.get("tld").asBuilder().setPremiumList(pl).build());
  }

  @Test
  void testSave_badSyntax() {
    assertThrows(
        IllegalArgumentException.class,
        () -> persistPremiumList("gtld1", "lol,nonsense USD,e,e # yup"));
  }

  @Test
  void testSave_invalidCurrencySymbol() {
    assertThrows(
        IllegalArgumentException.class, () -> persistReservedList("gtld1", "lol,XBTC 200"));
  }

  @Test
  void testBloomFilter() {
    PremiumList pl = PremiumListDualDao.getLatestRevision("tld").get();
    BloomFilter<String> bloomFilter = pl.getBloomFilter();
    assertThat(bloomFilter.mightContain("notpremium")).isFalse();
    for (String label : ImmutableList.of("rich", "lol", "johnny-be-goode", "icann")) {
      assertWithMessage(label + " should be a probable premium")
          .that(bloomFilter.mightContain(label))
          .isTrue();
    }
  }

  @Test
  void testParse_cannotIncludeDuplicateLabels() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                PremiumListDualDao.getLatestRevision("tld")
                    .get()
                    .parse(
                        ImmutableList.of(
                            "lol,USD 100",
                            "rofl,USD 90",
                            "paper,USD 80",
                            "wood,USD 70",
                            "lol,USD 200")));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "List 'tld' cannot contain duplicate labels. Dupes (with counts) were: [lol x 2]");
  }

  @Test
  void testValidation_labelMustBeLowercase() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PremiumListEntry.Builder()
                    .setPrice(Money.parse("USD 399"))
                    .setLabel("UPPER.tld")
                    .build());
    assertThat(e).hasMessageThat().contains("must be in puny-coded, lower-case form");
  }

  @Test
  void testValidation_labelMustBePunyCoded() {
    Exception e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PremiumListEntry.Builder()
                    .setPrice(Money.parse("USD 399"))
                    .setLabel("lower.みんな")
                    .build());
    assertThat(e).hasMessageThat().contains("must be in puny-coded, lower-case form");
  }
}
