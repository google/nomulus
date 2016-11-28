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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;

import google.registry.model.pricing.PricingCategory;
import google.registry.model.registry.Registry;
import google.registry.model.registry.label.CategorizedPremiumList.CategorizedListEntry;
import google.registry.testing.AppEngineRule;
import google.registry.testing.DatastoreHelper;
import google.registry.testing.ExceptionRule;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.common.EntityGroupRoot.getCrossTldKey;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.model.registry.label.CategorizedPremiumList.CategorizedListEntry.createEntry;
import static google.registry.testing.DatastoreHelper.persistCategorizedPremiumList;
import static google.registry.testing.DatastoreHelper.persistPricingCategory;
import static google.registry.util.DateTimeUtils.START_OF_TIME;

/** Unit tests for {@link CategorizedPremiumList} */
public class CategorizedPremiumListTest {

  private static final String JAPANESE_PRICE = CurrencyUnit.JPY + " 511";
  private static final String USD_PRICE = CurrencyUnit.USD + " 5.00";
  private static final String EURO_PRICE = CurrencyUnit.EUR + " 4.48";
  private static final String LABEL_ONE = "label_one";
  private static final String LABEL_TWO = "label_two";
  private static final String TLD_ONE = "tld_one";
  private static final String TLD_TWO = "tld_two";
  private static final String CATEGORY_NAME_ONE = "CATEGORY_NAME_ONE";
  private static final String CATEGORY_NAME_TWO = "CATEGORY_NAME_TWO";
  private static final String CATEGORY_NAME_THREE = "CATEGORY_NAME_THREE";
  private static final String CATEGORY_NAME_FOUR = "CATEGORY_NAME_FOUR";
  private static final String CATEGORY_NAME_FIVE = "CATEGORY_NAME_FIVE";

  // Create three future dates
  private final DateTime THREE_DAYS_AHEAD_UTC = DateTime.now()
      .withTimeAtStartOfDay()
      .plusDays(3)
      .toDateTime(DateTimeZone.UTC);
  private final DateTime FIVE_DAYS_AHEAD_UTC = DateTime.now()
      .withTimeAtStartOfDay()
      .plusDays(5)
      .toDateTime(DateTimeZone.UTC);

  private final DateTime EIGHT_MONTHS_PRIOR_UTC = DateTime.now()
      .withTimeAtStartOfDay()
      .minusMonths(8)
      .toDateTime(DateTimeZone.UTC);

  private final DateTime SIX_MONTHS_AHEAD_UTC = DateTime.now()
      .withTimeAtStartOfDay()
      .plusMonths(6)
      .toDateTime(DateTimeZone.UTC);

  @Rule public final ExceptionRule thrown = new ExceptionRule();
  @Rule public final AppEngineRule appEngine = AppEngineRule.builder().withDatastore().build();

  private CategorizedPremiumList premiumList;
  private CategorizedPremiumList nullablePremiumList;


  @Before
  public void before() throws Exception {
    persistPricingCategory(CATEGORY_NAME_ONE, USD_PRICE);
    persistPricingCategory(CATEGORY_NAME_TWO, USD_PRICE);
    persistPricingCategory(CATEGORY_NAME_THREE, USD_PRICE);
    persistPricingCategory(CATEGORY_NAME_FOUR, USD_PRICE);
    persistPricingCategory(CATEGORY_NAME_FIVE, USD_PRICE);

    PricingCategory fiveDaysAheadCategory = persistPricingCategory(CATEGORY_NAME_THREE, USD_PRICE);
    PricingCategory startOfTimeCategory = persistPricingCategory(CATEGORY_NAME_ONE, EURO_PRICE);
    PricingCategory threeDaysAheadCategory = persistPricingCategory(CATEGORY_NAME_TWO, JAPANESE_PRICE);

    // Adding three pricing categories with three different dates purposely to verify date
    // ordering for testing method 'getNextTransitionDateTime'
    premiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, fiveDaysAheadCategory.getName(),
                START_OF_TIME, startOfTimeCategory.getName(),
                THREE_DAYS_AHEAD_UTC, threeDaysAheadCategory.getName()),
            TLD_ONE,
            LABEL_ONE);

    // Second Premium List for testing for the @Nullable of method 'getNextTransitionDateTime'
    nullablePremiumList = persistCategorizedPremiumList(
        ImmutableSortedMap.of(START_OF_TIME, fiveDaysAheadCategory.getName()), TLD_TWO, LABEL_TWO);

    // Initial persistence of PricingCategory - without activation date
    DatastoreHelper.persistResource(new PricingCategory.Builder()
        .setName(CATEGORY_NAME_ONE)
        .setPrice(Money.parse(USD_PRICE))
        .build());

    DatastoreHelper.persistResource(new PricingCategory.Builder()
        .setName(CATEGORY_NAME_TWO)
        .setPrice(Money.parse(USD_PRICE))
        .activate()
        .build());

    DatastoreHelper.persistResource(new PricingCategory.Builder()
        .setName(CATEGORY_NAME_THREE)
        .setPrice(Money.parse(USD_PRICE))
        .activate()
        .build());

    DatastoreHelper.persistResource(new PricingCategory.Builder()
        .setName(CATEGORY_NAME_FOUR)
        .setPrice(Money.parse(USD_PRICE))
        .activate()
        .build());
  }

  @Test
  public void testCreateFromLine_shouldHandleCreate() {
    CategorizedListEntry entry =
        premiumList.createFromLine("A, car, " + CATEGORY_NAME_FIVE);
    assertThat(entry.getValue()).isEqualTo(CATEGORY_NAME_FIVE);
  }

  @Test
  public void testCreateFromLine_shouldThrowIllegalArgument_whenLessThan3Fields() {
    thrown.expect(IllegalArgumentException.class, "Missing pricing category argument");
    premiumList.createFromLine("A, car");
  }

  @Test
  public void testCreateFromLine_shouldThrowIllegalArgument_whenPricingCategoryDoesNotExist() {
    thrown.expect(
        IllegalArgumentException.class, "The pricing category 'DoesNotExist' doesn't exist");
    premiumList.createFromLine("A, car, DoesNotExist");
  }

  @Test
  public void testGetNextTransitionDateTime_futureDate() {
    CategorizedListEntry entry =
        premiumList.getPremiumListEntries().get(LABEL_ONE);
    DateTime nextTransitionDateTime = entry.getNextTransitionDateTime();
    PricingCategory futurePriceCategory = entry.getValueAtTime(nextTransitionDateTime);

    assertThat(nextTransitionDateTime).isEqualTo(THREE_DAYS_AHEAD_UTC);
    assertThat(futurePriceCategory.getName()).isEqualTo(CATEGORY_NAME_TWO);
    assertThat(futurePriceCategory.getPrice().toString()).isEqualTo(USD_PRICE);
  }

  @Test
  public void testGetNextTransitionDateTime_whenTransitionDateTimeIsNullable() {
    CategorizedListEntry entry =
        nullablePremiumList.getPremiumListEntries().get(LABEL_ONE);
    thrown.expect(NullPointerException.class);
    entry.getNextTransitionDateTime();
  }

  @Test
  public void testGetPremiumPrice_shouldReturnCurrentPriceForLabel() {
    Registry registry = Registry.get(TLD_ONE);
    CategorizedPremiumList premiumList = CategorizedPremiumList.get(
        registry.getPremiumList().getName()).get();
    Optional<Money> result = premiumList.getPremiumPrice(LABEL_ONE);
    Money expected = Money.parse(USD_PRICE);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(expected);
  }

  @Test
  public void testUpdateEntitiesSameRevision() {
    // Add to the existing entries
    final ImmutableMap<String, CategorizedListEntry> updatedEntries =
        ImmutableMap.<String, CategorizedListEntry>builder()
            .putAll(premiumList.getPremiumListEntries())
            .put("test",
                new CategorizedListEntry.Builder()
                    .setLabel("test")
                    .setPricingCategoryTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, CATEGORY_NAME_FIVE))
                    .build())
            .build();

    // Save and update should save the new entries while persisting the revision number
    final CategorizedPremiumList updatedPremiumList = premiumList.asBuilder()
        .setPremiumListMap(updatedEntries)
        .build()
        .saveAndUpdateEntries();

    // Verify the revision key has not changed
    assertThat(premiumList.getRevisionKey()).isEqualTo(updatedPremiumList.getRevisionKey());

    // Verify the new entity has been added to the existing revision
    assertThat(updatedPremiumList.getPremiumListEntries().size())
        .isEqualTo(premiumList.getPremiumListEntries().size() + 1);
  }

  @Test
  public void testVerifiesAddEntry() throws Exception {
    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final CategorizedListEntry us_entry = createEntry(sld1, CATEGORY_NAME_FIVE);
    final CategorizedListEntry japanese_entry = createEntry(sld2, CATEGORY_NAME_TWO);


    final CategorizedPremiumList result = new CategorizedPremiumList.Builder()
        .setName("tld")
        .addEntry(us_entry)
        .addEntry(sld2, CATEGORY_NAME_TWO)
        .build();

    assertThat(result.getPremiumListEntries()).containsExactly(
        sld1,
        us_entry.asBuilder()
            .setParent(result.getRevisionKey())
            .build(),
        sld2,
        japanese_entry.asBuilder()
            .setParent(result.getRevisionKey())
            .build());
  }

  @Test
  public void testVerifiesCannotAddDuplicateEntries() throws Exception {
    final String sld1 = "sld1";
    final String sld2 = "sld2";

    thrown.expect(IllegalStateException.class, "Entry [sld1] already exists");

    premiumList.asBuilder()
        .addEntry(sld1, CATEGORY_NAME_ONE)
        .addEntry(sld2, CATEGORY_NAME_FOUR)
        .addEntry(sld1, CATEGORY_NAME_ONE)
        .build();
  }

  @Test
  public void testVerifiesAddingSecondEntry() {
    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final String tld = "tld";

    final CategorizedPremiumList initialPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, CATEGORY_NAME_ONE,
                START_OF_TIME, CATEGORY_NAME_FIVE,
                THREE_DAYS_AHEAD_UTC, CATEGORY_NAME_FOUR),
            tld,
            sld1);

    final CategorizedListEntry secondEntry = createEntry(sld2, CATEGORY_NAME_FIVE);

    final CategorizedPremiumList twoEntriesPremiumList = initialPremiumList.asBuilder()
        .addEntry(sld2, CATEGORY_NAME_FIVE)
        .build();

    assertThat(twoEntriesPremiumList.getPremiumListEntries().size()).isEqualTo(2);

    assertThat(twoEntriesPremiumList.getPremiumListEntries().get(sld2))
        .isEquivalentAccordingToCompareTo(secondEntry);
  }

  @Test
  public void testVerifiesDeletingEntry() throws Exception {
    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final String sld3 = "sld3";
    final String tld = "tld";
    
    final CategorizedPremiumList initiallPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, CATEGORY_NAME_ONE,
                START_OF_TIME, CATEGORY_NAME_FIVE,
                THREE_DAYS_AHEAD_UTC, CATEGORY_NAME_FOUR),
            tld,
            sld1);

    // Add more CategorizedListEntry objects into PremiumList
    final CategorizedPremiumList threeElementsPremiumList = initiallPremiumList.asBuilder()
        .addEntry(sld2, CATEGORY_NAME_FIVE)
        .addEntry(sld3, CATEGORY_NAME_FIVE)
        .build();

    // Should now have two entries in map
    assertThat(threeElementsPremiumList.getPremiumListEntries().size())
        .isEqualTo(3);

    // Delete the road bicycle
    CategorizedPremiumList twoElementsPremiumList =
        threeElementsPremiumList.asBuilder()
            .deleteEntry(sld2)
            .build();

    assertThat(twoElementsPremiumList.getPremiumListEntries().size())
        .isEqualTo(2);

    assertThat(twoElementsPremiumList.getPremiumListEntries().get(sld3))
        .isEquivalentAccordingToCompareTo(createEntry(sld3, CATEGORY_NAME_FIVE));

    assertThat(twoElementsPremiumList.getPremiumListEntries().containsKey(sld1))
        .isTrue();
  }

  @Test
  public void testVerifiesAddingOneTransitionForStartOfTime() throws Exception {

    // Test case verifies that for a entry that has only START_OF_TIME that it should add only
    // one transition for given effective date

    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final String sld3 = "sld3";
    final String tld = "tld";


    final CategorizedPremiumList initialPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, CATEGORY_NAME_ONE,
                START_OF_TIME, CATEGORY_NAME_FIVE,
                THREE_DAYS_AHEAD_UTC, CATEGORY_NAME_FOUR),
            tld,
            sld1);
    
    final CategorizedPremiumList threeElementsPremiumList = initialPremiumList.asBuilder()
        .addEntry(sld2, CATEGORY_NAME_ONE)
        .addEntry(sld3, CATEGORY_NAME_FOUR)
        .build();
    
    assertThat(threeElementsPremiumList.getPremiumListEntries().size())
        .isEqualTo(3);
    
    CategorizedPremiumList twoElementsPremiumList =
        threeElementsPremiumList.asBuilder()
            .updateEntry(
                sld2, // Has one transition date (START_OF_TIME)
                CATEGORY_NAME_TWO,
                FIVE_DAYS_AHEAD_UTC)
            .build();

    // Verify that we have the new transition date
    assertThat(twoElementsPremiumList.getPremiumListEntries().get(sld2).getNextTransitionDateTime())
        .isEqualTo(FIVE_DAYS_AHEAD_UTC);

    assertThat(twoElementsPremiumList.getPremiumListEntries()).containsKey(sld1);
  }

  @Test
  public void testVerifiesUpdateThrowsUnableToFindEntry() throws Exception {
    // Test case verifies that when we pass an invalid second level domain
    // that an IllegalStateException gets thrown

    thrown.expect(IllegalStateException.class, "Unable to find entry for [invalid]");

    premiumList.asBuilder()
            .updateEntry(
                "invalid",
                CATEGORY_NAME_THREE,
                FIVE_DAYS_AHEAD_UTC)
            .build();
  }

  @Test
  public void testVerifiesUpdateFutureCategoryAlreadyExists() throws Exception {
    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final String sld3 = "sld3";
    final String tld = "tld";

    final CategorizedPremiumList initialPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, CATEGORY_NAME_ONE,
                START_OF_TIME, CATEGORY_NAME_FIVE,
                EIGHT_MONTHS_PRIOR_UTC, CATEGORY_NAME_FIVE),
            tld,
            sld1);

    final CategorizedPremiumList threeElementsInPremiumList = initialPremiumList.asBuilder()
        .addEntry(sld2, CATEGORY_NAME_ONE)
        .addEntry(sld3, CATEGORY_NAME_FOUR)
        .build();

    assertThat(threeElementsInPremiumList.getPremiumListEntries()).hasSize(3);

    CategorizedPremiumList result =
        threeElementsInPremiumList.asBuilder()
            .updateEntry(
                sld1, // Action has two transitions
                CATEGORY_NAME_TWO,
                SIX_MONTHS_AHEAD_UTC)
            .build();

    // Verify that we have the new transition date
    assertThat(result.getPremiumListEntries().get(sld1).getNextTransitionDateTime())
        .isEqualTo(SIX_MONTHS_AHEAD_UTC);
  }

  @Test
  public void testVerifiesMultipleUpdatesIsNotAddingMultipleTransitions() throws Exception {
    // Test case verifies that by updating multiple times that the effective
    // future date is being overwritten - and is not adding a new transition

    final String sld = "sld";
    final String tld = "tld";

    final CategorizedPremiumList initialPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                START_OF_TIME, CATEGORY_NAME_FIVE),
            tld,
            sld);

    // Purposely set the Effective Date to five days ahead in time
    CategorizedPremiumList resultFiveDaysAhead =
        initialPremiumList.asBuilder()
            .updateEntry(
                sld,
                CATEGORY_NAME_TWO,
                FIVE_DAYS_AHEAD_UTC)
            .build();

    // Verify that we have the new transition date
    assertThat(resultFiveDaysAhead.getPremiumListEntries()
        .get(sld).getNextTransitionDateTime())
        .isEqualTo(FIVE_DAYS_AHEAD_UTC);


    // Purposely set the Effective Date to six months ahead in time
    CategorizedPremiumList resultSixMonthAhead =
        initialPremiumList.asBuilder()
            .updateEntry(
                sld,
                CATEGORY_NAME_FIVE,
                SIX_MONTHS_AHEAD_UTC)
            .build();

    // Verify that we have the new transition date
    assertThat(resultSixMonthAhead.getPremiumListEntries()
        .get(sld).getNextTransitionDateTime())
        .isEqualTo(SIX_MONTHS_AHEAD_UTC);
  }

  @Test
  public void testVerifiesDeletingEntryThatDoesNotExistThrowsException() throws Exception {
    // Test case verifies if a second level domain does not exist
    final String tld = "tld";
    final String sld1 = "sld1";
    final String sld2 = "sld2";
    final String sld3 = "sld3";
    final String sld4 = "sld4";

    final CategorizedPremiumList initialPremiumList =
        persistCategorizedPremiumList(
            ImmutableSortedMap.of(
                FIVE_DAYS_AHEAD_UTC, CATEGORY_NAME_ONE,
                START_OF_TIME, CATEGORY_NAME_FIVE,
                THREE_DAYS_AHEAD_UTC, CATEGORY_NAME_FOUR),
            tld,
            sld1);

    final CategorizedPremiumList newPremiumList = initialPremiumList.asBuilder()
        .addEntry(sld2, CATEGORY_NAME_FIVE)
        .addEntry(sld3, CATEGORY_NAME_FOUR)
        .build();

    thrown.expect(IllegalStateException.class, "Unable to find entry [sld4]");

    newPremiumList.asBuilder()
        .deleteEntry(sld4)
        .build();
  }

  @Test
  public void activatePricingCategoryByCallingSaveAndUpdateEntries() throws Exception {
    final String tld = "tld";
    final String sld = "sld";
    final String category_ZZ = "ZZ";

    DatastoreHelper.persistResource(new PricingCategory.Builder()
        .setName(category_ZZ)
        .setPrice(Money.parse(USD_PRICE))
        .build());

    new CategorizedPremiumList.Builder()
        .setName(tld)
        .setPremiumListMap(
            ImmutableMap.of(
                sld,
                new CategorizedListEntry.Builder()
                    .setLabel(sld)
                    .setPricingCategoryTransitions(
                        ImmutableSortedMap.of(START_OF_TIME, category_ZZ))
                    .build()))
        .build().saveAndUpdateEntries();

    // Retrieve the PremiumList to verify the activation date was set
    final List<PricingCategory> pricingCategories =
        CategorizedPremiumList.getPricingCategories(CategorizedPremiumList.get(tld).get());
    assertThat(pricingCategories.get(0).getActivationDate()).isNotNull();
  }

  @Test
  public void testVerifiesUniquePricingCategoriesAreReturned() {
    final List<PricingCategory> pricingCategories =
        CategorizedPremiumList.getPricingCategories(premiumList);
    assertThat(pricingCategories.size()).isEqualTo(3);
    assertThat(pricingCategories.get(1).getName()).isEqualTo(CATEGORY_NAME_TWO);
    assertThat(pricingCategories.get(1).getPrice()).isEqualTo(Money.parse(USD_PRICE));
  }

  @Test
  public void verifyEntriesAreBeingDeletedInDatastore() {
    final String sld1 = "brown1";
    final String sld2 = "brown2";
    final String sld3 = "brown3";
    final String tld = "cow";

    new CategorizedPremiumList.Builder()
        .setName(tld)
        .addEntry(sld1, CATEGORY_NAME_ONE)
        .build()
        .saveAndUpdateEntries();

    final CategorizedPremiumList premiumListAfterFirstSave = ofy()
        .load()
        .type(CategorizedPremiumList.class)
        .parent(getCrossTldKey())
        .id(tld)
        .now();

    assertThat(premiumListAfterFirstSave.getPremiumListEntries())
        .containsKey(sld1);

    premiumListAfterFirstSave
        .asBuilder()
        .deleteEntry(sld1)
        .addEntry(sld2, CATEGORY_NAME_TWO)
        .addEntry(sld3, CATEGORY_NAME_THREE)
        .build()
        .saveAndUpdateEntries();

    final CategorizedPremiumList premiumListAfterSecondSave = ofy()
        .load()
        .type(CategorizedPremiumList.class)
        .parent(getCrossTldKey())
        .id(tld)
        .now();

    assertThat(premiumListAfterSecondSave.getPremiumListEntries())
        .doesNotContainKey(sld1);

    final CategorizedListEntry cle = ofy()
        .load()
        .type(CategorizedListEntry.class)
        .parent(premiumListAfterFirstSave.getRevisionKey())
        .id(sld1)
        .now();

    assertThat(cle).isNull();
  }

  @Test
  public void verifySaveNewPremiumList() {
    final String sld1 = "brown1";
    final String tld = "cow";

    new CategorizedPremiumList.Builder()
        .setName(tld)
        .addEntry(sld1, CATEGORY_NAME_ONE)
        .build()
        .saveNewPremiumList();

    final CategorizedPremiumList result =
        ofy()
        .load()
        .type(CategorizedPremiumList.class)
        .parent(getCrossTldKey())
        .id(tld)
        .now();

    assertThat(result.getPremiumListEntries()).containsKey(sld1);
  }
}
