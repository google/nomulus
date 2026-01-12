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

package google.registry.flows.domain;

import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.REGISTER_BSA;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.eppoutput.CheckData.DomainCheck.create;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistBillingRecurrenceForDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceCheckFlowTestCase;
import google.registry.flows.domain.DomainCheckFlow.OnlyCheckedNamesCanBeFeeCheckedException;
import google.registry.flows.domain.DomainFlowUtils.BadCommandForRegistryPhaseException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainNameExistsAsTldException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.UnknownFeeCommandException;
import google.registry.flows.exceptions.TooManyResourceChecksException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.feestdv1.FeeCheckCommandExtensionStdV1.MultipleCurrenciesCannotBeCheckedException;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.label.ReservedList;
import google.registry.testing.DatabaseHelper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Unit tests for {@link DomainCheckFlow}. */
class DomainCheckFlowTest extends ResourceCheckFlowTestCase<DomainCheckFlow, Domain> {

  DomainCheckFlowTest() {
    setEppInput("domain_check_one_tld.xml");
    clock.setTo(DateTime.parse("2009-01-01T10:00:00Z"));
  }

  private static ReservedList createReservedList() {
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("anchor.tld")
            .setToken("2fooBAR")
            .setTokenType(SINGLE_USE)
            .build());
    return persistReservedList(
        "tld-reserved",
        "allowedinsunrise,ALLOWED_IN_SUNRISE",
        "anchor,RESERVED_FOR_ANCHOR_TENANT",
        "collision,NAME_COLLISION",
        "premiumcollision,NAME_COLLISION",
        "reserved,FULLY_BLOCKED",
        "specificuse,RESERVED_FOR_SPECIFIC_USE");
  }

  @BeforeEach
  void initCheckTest() {
    createTld("tld", TldState.QUIET_PERIOD);
    persistResource(Tld.get("tld").asBuilder().setReservedLists(createReservedList()).build());
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testNotLoggedIn_takesPrecedenceOverUndeclaredExtensions() {
    // Attempt to use the fee extension, but there is no login session and no supported extensions.
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    sessionMetadata.setRegistrarId(null);
    sessionMetadata.setServiceExtensionUris(ImmutableSet.of());
    // NotLoggedIn should be thrown, not UndeclaredServiceExtensionException.
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_nothingExists() throws Exception {
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_oneExists() throws Exception {
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_bsaBlocked_otherwiseAvailable_blocked() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("example1");
    doCheckTest(
        create(false, "example1.tld", "Blocked by a GlobalBlock service"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_bsaBlocked_alsoRegistered_registered() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("example1");
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_bsaBlocked_alsoReserved_reserved() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("reserved");
    persistBsaLabel("allowedinsunrise");
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Reserved"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_bsaBlocked_createAllowedWithToken() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("example1");
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(REGISTER_BSA)
            .setDomainName("example1.tld")
            .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Alloc token invalid for domain"),
        create(false, "specificuse.tld", "Alloc token invalid for domain"));
  }

  @Test
  void testSuccess_bsaBlocked_withIrrelevantTokenType() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("example1");
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("example1.tld")
            .build());
    doCheckTest(
        create(false, "example1.tld", "Blocked by a GlobalBlock service"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Alloc token invalid for domain"),
        create(false, "specificuse.tld", "Alloc token invalid for domain"));
  }

  @Test
  void testSuccess_bsaBlocked_onlyInEnrolledTlds() throws Exception {
    setEppInput("domain_check.xml");
    createTlds("com", "net", "org");
    persistResource(
        Tld.get("com").asBuilder().setBsaEnrollStartTime(Optional.of(START_OF_TIME)).build());
    persistBsaLabel("example");
    doCheckTest(
        create(false, "example.com", "Blocked by a GlobalBlock service"),
        create(true, "example.net", null),
        create(true, "example.org", null));
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_check_no_cltrid.xml");
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_oneExists_allocationTokenIsValid() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Reserved; alloc. token required"));
  }

  @Test
  void testSuccess_allocationToken_premiumAnchorTenant_noFee() throws Exception {
    createTld("example");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("example1", USD, "example1,USD 100"))
            .build());
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRegistrationBehavior(AllocationToken.RegistrationBehavior.ANCHOR_TENANT)
            .setDomainName("example1.tld")
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_anchor_response.xml"));
  }

  @Test
  void testSuccess_oneExists_allocationTokenForReservedDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("specificuse.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for domain"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Alloc token invalid for domain"),
        create(true, "specificuse.tld", null));
  }

  @Test
  void testSuccess_allocationTokenForReservedDomain_showsFee() throws Exception {
    setEppInput("domain_check_allocationtoken_fee_specificuse.xml");
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("specificuse.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    // Fees are shown for all non-reserved domains and the reserved domain matching this
    // allocation token.
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_specificuse_response.xml"));
  }

  @Test
  void testSuccess_notOutOfDateToken_forSpecificDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("specificuse.tld")
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for domain"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Alloc token invalid for domain"),
        create(true, "specificuse.tld", null));
  }

  @Test
  void testSuccess_allocationTokenPromotion_singleYear() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(0.5)
            .setDiscountYears(2)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_response.xml"));
  }

  @Test
  void testSuccess_allocationTokenPromotion_doesNotUseValidDefaultToken_singleYear()
      throws Exception {
    setUpDefaultToken();
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setDiscountYears(2)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_response.xml"));
  }

  @Test
  void testSuccess_allocationTokenPromotion_multiYearAndPremiums() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("rich.example")
            .setDiscountFraction(0.9)
            .setDiscountYears(3)
            .setDiscountPremiums(true)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput(
        "domain_check_allocationtoken_promotion.xml", ImmutableMap.of("DOMAIN", "rich.example"));
    runFlowAssertResponse(
        loadFile(
            "domain_check_allocationtoken_promotion_response.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "rich.example")
                .put("COST_1YR", "10.00")
                .put("COST_2YR", "20.00")
                .put("COST_5YR", "230.00")
                .put("FEE_CLASS", "<fee:class>premium</fee:class>")
                .build()));
  }

  @Test
  void testSuccess_allocationTokenInvalid_overridesOtherErrors() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "The allocation token is invalid"),
        create(false, "example2.tld", "The allocation token is invalid"),
        create(false, "reserved.tld", "The allocation token is invalid"),
        create(false, "specificuse.tld", "The allocation token is invalid"));
  }

  @Test
  void testSuccess_allocationTokenForWrongDomain_overridesOtherConcerns() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("someotherdomain.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for domain"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Alloc token invalid for domain"),
        create(false, "specificuse.tld", "Alloc token invalid for domain"));
  }

  @Test
  void testSuccess_outOfDateToken_overridesOtherIssues() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("specificuse.tld")
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(2), TokenStatus.VALID)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token not in promo period"),
        create(false, "example2.tld", "Alloc token not in promo period"),
        create(false, "reserved.tld", "Alloc token not in promo period"),
        create(false, "specificuse.tld", "Alloc token not in promo period"));
  }

  @Test
  void testSuccess_redeemedTokenOverridesOtherConcerns() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    Domain domain = persistActiveDomain("example1.tld");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 1L);
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryId(historyEntryId)
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token was already redeemed"),
        create(false, "example2.tld", "Alloc token was already redeemed"),
        create(false, "reserved.tld", "Alloc token was already redeemed"),
        create(false, "specificuse.tld", "Alloc token was already redeemed"));
  }

  @Test
  void testSuccess_allocationTokenPromotion_noPremium_stillPasses() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDiscountFraction(0.9)
            .setDiscountYears(3)
            .setDiscountPremiums(false)
            .build());
    setEppInput(
        "domain_check_allocationtoken_multiname_promotion.xml",
        ImmutableMap.of("DOMAIN", "rich.example"));
    doCheckTest(
        create(true, "example1.example", null),
        create(true, "rich.example", null),
        create(true, "example3.example", null));
  }

  @Test
  void testSuccess_allocationTokenPromotion_multiYear() throws Exception {
    createTld("tld");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("single.tld")
            .setDiscountFraction(0.444)
            .setDiscountYears(2)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput(
        "domain_check_allocationtoken_promotion.xml", ImmutableMap.of("DOMAIN", "single.tld"));
    // 1-yr: 13 * .556
    // 2-yr: (13 + 11) * .556
    // 5-yr: 2-yr-cost + 3 * 11
    runFlowAssertResponse(
        loadFile(
            "domain_check_allocationtoken_promotion_response.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "single.tld")
                .put("COST_1YR", "7.23")
                .put("COST_2YR", "13.34")
                .put("COST_5YR", "46.34")
                .put("FEE_CLASS", "")
                .build()));
  }

  @Test
  void testSuccess_promotionNotActive() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(60), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(false, "example1.tld", "Alloc token not in promo period"),
        create(false, "example2.example", "Alloc token not in promo period"),
        create(false, "reserved.tld", "Alloc token not in promo period"),
        create(false, "rich.example", "Alloc token not in promo period"));
  }

  @Test
  void testSuccess_promoTokenNotValidForTld() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setAllowedTlds(ImmutableSet.of("example"))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.example", null),
        create(false, "reserved.tld", "Reserved"),
        create(true, "rich.example", null));
  }

  @Test
  void testSuccess_promoTokenNotValidForRegistrar() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setAllowedRegistrarIds(ImmutableSet.of("someOtherClient"))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for client"),
        create(false, "example2.example", "Alloc token invalid for client"),
        create(false, "reserved.tld", "Alloc token invalid for client"),
        create(false, "rich.example", "Alloc token invalid for client"));
  }

  @Test
  void testSuccess_oneReservedInSunrise() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(Tld.get("tld").asBuilder().setReservedLists(createReservedList()).build());
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(true, "allowedinsunrise.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_twoReservedOutsideSunrise() throws Exception {
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Reserved"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_domainWithMultipleReservationType_useMostSevereMessage() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                createReservedList(),
                persistReservedList("tld-collision", "allowedinsunrise,NAME_COLLISION"))
            .build());
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Cannot be delegated"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_anchorTenantReserved() throws Exception {
    setEppInput("domain_check_anchor.xml");
    doCheckTest(create(false, "anchor.tld", "Reserved; alloc. token required"));
  }

  @Test
  void testSuccess_anchorTenantWithToken() throws Exception {
    setEppInput("domain_check_anchor_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("anchor.tld")
            .build());
    doCheckTest(create(true, "anchor.tld", null));
  }

  @Test
  void testSuccess_premiumAnchorTenantWithToken() throws Exception {
    setEppInput("domain_check_anchor_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("anchor.tld")
            .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "anchor,USD 70"))
            .build());
    doCheckTest(create(true, "anchor.tld", null));
  }

  @Test
  void testSuccess_multipartTld_oneReserved() throws Exception {
    createTld("tld.foo");
    persistResource(
        Tld.get("tld.foo")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "tld.foo", "reserved,FULLY_BLOCKED", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    setEppInput("domain_check_one_multipart_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld.foo", "Reserved"),
        create(false, "allowedinsunrise.tld.foo", "Reserved"),
        create(true, "example2.tld.foo", null),
        create(true, "example3.tld.foo", null));
  }

  @Test
  void testSuccess_oneExistsButWasDeleted() throws Exception {
    persistDeletedDomain("example1.tld", clock.nowUtc().minusDays(1));
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  void testSuccess_duplicatesAllowed() throws Exception {
    setEppInput("domain_check_duplicates.xml");
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example1.tld", null));
  }

  @Test
  void testSuccess_xmlMatches() throws Exception {
    persistActiveDomain("example2.tld");
    runFlowAssertResponse(loadFile("domain_check_one_tld_response.xml"));
  }

  @Test
  void testSuccess_50IdsAllowed() throws Exception {
    // Make sure we don't have a regression that reduces the number of allowed checks.
    setEppInput("domain_check_50.xml");
    runFlow();
  }

  @Test
  void testSuccess_50IdsAllowed_withAllocationToken() throws Exception {
    setEppInput("domain_check_50_allocationtoken.xml");
    runFlow();
  }

  @Test
  void testFailure_tooManyIds() {
    setEppInput("domain_check_51.xml");
    EppException thrown = assertThrows(TooManyResourceChecksException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongTld() {
    setEppInput("domain_check.xml");
    EppException thrown = assertThrows(TldDoesNotExistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_notAuthorizedForTld() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_missingBillingAccount() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCurrency(JPY)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(JPY, 800))
            .setServerStatusChangeBillingCost(Money.ofMajor(JPY, 800))
            .setRestoreBillingCost(Money.ofMajor(JPY, 800))
            .build());
    EppException thrown = assertThrows(MissingBillingAccountMapException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_multipleCurencies_v12() throws Exception {
    persistResource(
        createTld("example")
            .asBuilder()
            .setCurrency(JPY)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(JPY, 800))
            .setServerStatusChangeBillingCost(Money.ofMajor(JPY, 800))
            .setRestoreBillingCost(Money.ofMajor(JPY, 800))
            .build());
    persistResource(
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "foo", JPY, "bar"))
            .build());
    setEppInput("domain_check_fee_multiple_currencies_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_currencies_response_v12.xml"));
  }

  @Test
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistActiveDomain("example2.tld");
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("domain_check_one_tld_response.xml"));
  }

  private void doFailingBadLabelTest(
      String label, Class<? extends EppException> expectedException) {
    setEppInput("domain_check_template.xml", ImmutableMap.of("LABEL", label));
    EppException thrown = assertThrows(expectedException, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_uppercase() {
    doFailingBadLabelTest("FOO.tld", BadDomainNameCharacterException.class);
  }

  @Test
  void testFailure_badCharacter() {
    doFailingBadLabelTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  void testFailure_leadingDash() {
    doFailingBadLabelTest("-example.tld", LeadingDashException.class);
  }

  @Test
  void testFailure_trailingDash() {
    doFailingBadLabelTest("example-.tld", TrailingDashException.class);
  }

  @Test
  void testFailure_tooLong() {
    doFailingBadLabelTest("a".repeat(64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  void testFailure_leadingDot() {
    doFailingBadLabelTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  void testFailure_leadingDotTld() {
    doFailingBadLabelTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  void testFailure_tooManyParts() {
    doFailingBadLabelTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  void testFailure_tooFewParts() {
    doFailingBadLabelTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  void testFailure_domainNameExistsAsTld_lowercase() {
    createTlds("foo.tld", "tld");
    doFailingBadLabelTest("foo.tld", DomainNameExistsAsTldException.class);
  }

  @Test
  void testFailure_domainNameExistsAsTld_uppercase() {
    createTlds("foo.tld", "tld");
    doFailingBadLabelTest("FOO.TLD", BadDomainNameCharacterException.class);
  }

  @Test
  void testFailure_invalidPunycode() {
    doFailingBadLabelTest("xn--abcdefg.tld", InvalidPunycodeException.class);
  }

  @Test
  void testFailure_dashesInThirdAndFourthPosition() {
    doFailingBadLabelTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  void testFailure_tldDoesNotExist() {
    doFailingBadLabelTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  void testFailure_invalidIdnCodePoints() {
    // ❤☀☆☂☻♞☯.tld
    doFailingBadLabelTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  void testFailure_predelegation() {
    createTld("tld", PREDELEGATION);
    EppException thrown = assertThrows(BadCommandForRegistryPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testAvailExtension() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_avail.xml");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  /** Test that premium names are shown as available even if the fee extension is not used. */
  @Test
  void testAvailExtension_premiumDomainsAreAvailableWithoutExtension() throws Exception {
    createTld("example");
    setEppInput("domain_check_premium.xml");
    doCheckTest(create(true, "rich.example", null));
  }

  /** Test multiyear periods and explicitly correct currency and that the avail extension is ok. */
  @Test
  void testFeeExtension_v06() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v06() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleReservations() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList("example-sunrise", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  void testFeeExtension_v11() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v11.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v11() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v11.xml"));
  }

  @Test
  void testFeeExtension_v12() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  @Test
  void testFeeExtension_stdv1() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v12() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v12.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_stdv1() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_stdv1.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_stdv1.xml"));
  }

  @Test
  void testSuccess_thirtyDomains_restoreFees() throws Exception {
    // Note that 30 is more than 25, which is the maximum # of entity groups you can enlist in a
    // single database transaction (each Domain entity is in a separate entity group).
    // It's also pretty common for registrars to send large domain checks.
    setEppInput("domain_check_fee_thirty_domains.xml");
    // example-00.tld won't exist and thus will not have a renew fee like the others.
    for (int i = 1; i < 30; i++) {
      persistPendingDeleteDomain(String.format("example-%02d.tld", i));
    }
    runFlowAssertResponse(loadFile("domain_check_fee_response_thirty_domains.xml"));
  }

  /**
   * Test commands for create, renew, transfer, restore and update with implicit period and
   * currency.
   */
  @Test
  void testFeeExtension_multipleCommands_v06() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_tokenNotValidForSome_v06() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.TRANSFER))
            .setDiscountFraction(0.1)
            .build());
    setEppInput("domain_check_fee_multiple_commands_allocationtoken_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_allocationtoken_response_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_defaultTokenOnlyOnCreate_v06() throws Exception {
    setUpDefaultToken();
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_default_token_response_v06.xml"));
  }

  // Version 11 cannot have multiple commands.

  @Test
  void testFeeExtension_multipleCommands_v12() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v12.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_std_v1() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_tokenNotValidForSome_v12() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.TRANSFER))
            .setDiscountFraction(0.1)
            .build());
    setEppInput("domain_check_fee_multiple_commands_allocationtoken_v12.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_allocationtoken_response_v12.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_tokenNotValidForSome_std_v1() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.TRANSFER))
            .setDiscountFraction(0.1)
            .build());
    setEppInput("domain_check_fee_multiple_commands_allocationtoken_stdv1.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_allocationtoken_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_defaultTokenOnlyOnCreate_v12() throws Exception {
    setUpDefaultToken();
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_default_token_response_v12.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_defaultTokenOnlyOnCreate_std_v1() throws Exception {
    setUpDefaultToken();
    setEppInput("domain_check_fee_multiple_commands_stdv1.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_default_token_response_stdv1.xml"));
  }

  @Disabled("TODO(b/454680236): broken test")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v06() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_default_token_multiple_names_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_default_token_multiple_names_response_v06.xml"));
  }

  @Disabled("TODO(b/454680236): broken")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v11() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_default_token_multiple_names_v11.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_default_token_multiple_names_response_v11.xml"));
  }

  @Disabled("TODO(b/454680236): broken test")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v12(String name, FeeFileLoader loader)
      throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInputXml(loader.load(this, "domain_check_fee_default_token_multiple_names_v12.xml"));
    runFlowAssertResponse(
        loader.load(this, "domain_check_fee_default_token_multiple_names_response_v12.xml"));
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  void testFeeExtension_premiumLabels_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v06.xml"));
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v06() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v06.xml"));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior_renewPriceOnly()
      throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06_renew_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06_renew_only.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior_transferPriceOnly()
      throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06_transfer_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06_transfer_only.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withSpecifiedRenewalBehavior() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(
        persistActiveDomain("rich.example"), SPECIFIED, Money.of(USD, new BigDecimal("15.55")));
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06.xml",
            ImmutableMap.of("RENEWPRICE", "15.55")));
  }

  @Test
  void testFeeExtension_premium_eap_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(clock.nowUtc().minusDays(1), Money.of(USD, 100))
                    .put(clock.nowUtc().plusDays(1), Money.of(USD, 50))
                    .put(clock.nowUtc().plusDays(2), Money.of(USD, 0))
                    .build())
            .build());

    runFlowAssertResponse(loadFile("domain_check_fee_premium_eap_response_v06.xml"));
  }

  @Test
  void testFeeExtension_premium_eap_v06_withRenewalOnRestore() throws Exception {
    createTld("example");
    DateTime startTime = DateTime.parse("2010-01-01T10:00:00Z");
    clock.setTo(startTime);
    persistResource(
        persistActiveDomain("rich.example")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    persistPendingDeleteDomain("rich.example");
    setEppInput("domain_check_fee_premium_v06.xml");
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(startTime.minusDays(1), Money.of(USD, 100))
                    .put(startTime.plusDays(1), Money.of(USD, 50))
                    .put(startTime.plusDays(2), Money.of(USD, 0))
                    .build())
            .build());
    runFlowAssertResponse(loadFile("domain_check_fee_premium_eap_response_v06_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_create() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v11() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_renew() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_transfer() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_restore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_restore_withRenewal() throws Exception {
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    createTld("example");
    persistPendingDeleteDomain("rich.example");
    runFlowAssertResponse(
        loadFile("domain_check_fee_premium_response_v11_restore_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_update() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_update.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_update.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v12() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_std_v1() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v12_specifiedPriceRenewal_renewPriceOnly() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(
        persistActiveDomain("rich.example"), SPECIFIED, Money.of(USD, new BigDecimal("27.74")));
    setEppInput("domain_check_fee_premium_v12_renew_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_premium_response_v12_renew_only.xml",
            ImmutableMap.of("RENEWPRICE", "27.74")));
  }

  @Test
  void testFeeExtension_premiumLabels_std_v1_specifiedPriceRenewal_renewPriceOnly()
      throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(
        persistActiveDomain("rich.example"), SPECIFIED, Money.of(USD, new BigDecimal("27.74")));
    setEppInput("domain_check_fee_premium_stdv1_renew_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_premium_response_stdv1_renew_only.xml",
            ImmutableMap.of("RENEWPRICE", "27.74")));
  }

  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v12() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_std_v1() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v12_withRenewalOnRestore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    persistPendingDeleteDomain("rich.example");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_std_v1_withRenewalOnRestore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_stdv1.xml");
    persistPendingDeleteDomain("rich.example");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_stdv1_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_fractionalCost() throws Exception {
    // Note that the response xml expects to see "11.10" with two digits after the decimal point.
    // This works because Money.getAmount(), used in the flow, returns a BigDecimal that is set to
    // display the number of digits that is conventional for the given currency.
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 11.1)))
            .build());
    setEppInput("domain_check_fee_fractional.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_fractional_response.xml"));
  }

  /** Test that create fees are properly omitted/classed on names on reserved lists. */
  @Test
  void testFeeExtension_reservedName_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v06.xml"));
  }

  @Test
  void testFeeExtension_reservedName_restoreFeeWithDupes_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    // The domain needs to exist in order for it to be loaded to check for restore fee.
    persistBillingRecurrenceForDomain(persistActiveDomain("allowedinsunrise.tld"), DEFAULT, null);
    setEppInput("domain_check_fee_reserved_dupes_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_dupes_v06.xml"));
  }

  /** The tests must be split up for version 11, which allows only one command at a time. */
  @Test
  void testFeeExtension_reservedName_v11_create() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_renew() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_transfer() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_restore() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_restore_withRenewals() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    persistPendingDeleteDomain("reserved.tld");
    persistPendingDeleteDomain("allowedinsunrise.tld");
    persistPendingDeleteDomain("collision.tld");
    persistPendingDeleteDomain("premiumcollision.tld");
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_reserved_response_v11_restore_with_renewals.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v12() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v12.xml"));
  }

  @Test
  void testFeeExtension_reservedName_std_v1() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_reservedName_restoreFeeWithDupes_v12() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    // The domain needs to exist in order for it to be loaded to check for restore fee.
    setEppInput("domain_check_fee_reserved_dupes_v12.xml");
    persistBillingRecurrenceForDomain(persistActiveDomain("allowedinsunrise.tld"), DEFAULT, null);
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_dupes_response_v12.xml"));
  }

  @Test
  void testFeeExtension_reservedName_restoreFeeWithDupes_std_v1() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    // The domain needs to exist in order for it to be loaded to check for restore fee.
    setEppInput("domain_check_fee_reserved_dupes_stdv1.xml");
    persistBillingRecurrenceForDomain(persistActiveDomain("allowedinsunrise.tld"), DEFAULT, null);
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_dupes_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v06.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06_withRestoreRenewals()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    persistPendingDeleteDomain("reserved.tld");
    persistPendingDeleteDomain("allowedinsunrise.tld");
    persistPendingDeleteDomain("collision.tld");
    persistPendingDeleteDomain("premiumcollision.tld");
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_reserved_sunrise_response_v06_with_renewals.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_create() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_renew() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_transfer() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_restore() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v12() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v12.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_std_v1() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_stdv1.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_stdv1.xml"));
  }

  @Test
  void testFeeExtension_wrongCurrency_v06() {
    setEppInput("domain_check_fee_euro_v06.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_wrongCurrency_v11() {
    setEppInput("domain_check_fee_euro_v11.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_wrongCurrency_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_euro_v12.xml"));
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_badCurrencyType() {
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "BAD"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_periodNotInYears_v06() {
    setEppInput("domain_check_fee_bad_period_v06.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_periodNotInYears_v11() {
    setEppInput("domain_check_fee_bad_period_v11.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_periodNotInYears_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_bad_period_v12.xml"));
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandWithPhase_v06() {
    setEppInput("domain_check_fee_command_phase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandWithPhase_v11() {
    setEppInput("domain_check_fee_command_phase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_commandWithPhase_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_command_phase_v12.xml"));
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandSubphase_v06() {
    setEppInput("domain_check_fee_command_subphase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandSubphase_v11() {
    setEppInput("domain_check_fee_command_subphase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_commandSubphase_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_command_subphase_v12.xml"));
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // This test is only relevant for v06, since domain names are not specified in v11 or v12.
  @Test
  void testFeeExtension_feeCheckNotInAvailabilityCheck() {
    setEppInput("domain_check_fee_not_in_avail.xml");
    EppException thrown =
        assertThrows(OnlyCheckedNamesCanBeFeeCheckedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearRestore_v06() {
    setEppInput("domain_check_fee_multiyear_restore_v06.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearRestore_v11() {
    setEppInput("domain_check_fee_multiyear_restore_v11.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_multiyearRestore_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_multiyear_restore_v12.xml"));
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearTransfer_v06() {
    setEppInput("domain_check_fee_multiyear_transfer_v06.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearTransfer_v11() {
    setEppInput("domain_check_fee_multiyear_transfer_v11.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_multiyearTransfer_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_multiyear_transfer_v12.xml"));
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCommand_v06() {
    setEppInput("domain_check_fee_unknown_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCommand_v11() {
    setEppInput("domain_check_fee_unknown_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @ParameterizedTest
  @MethodSource("provideFeeTestParams")
  void testFeeExtension_unknownCommand_latest(String name, FeeFileLoader loader) {
    setEppInputXml(loader.load(this, "domain_check_fee_unknown_command_v12.xml"));
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v06() {
    setEppInput("domain_check_fee_invalid_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v11() {
    setEppInput("domain_check_fee_invalid_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v12() {
    setEppInput("domain_check_fee_invalid_command_v12.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_std_v1() {
    setEppInput("domain_check_fee_invalid_command_stdv1.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalid_multipleCurrencies_std_v1() {
    persistResource(
        createTld("example")
            .asBuilder()
            .setCurrency(JPY)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRenewBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.ofMajor(JPY, 800)))
            .setRegistryLockOrUnlockBillingCost(Money.ofMajor(JPY, 800))
            .setServerStatusChangeBillingCost(Money.ofMajor(JPY, 800))
            .setRestoreBillingCost(Money.ofMajor(JPY, 800))
            .build());
    persistResource(
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "foo", JPY, "bar"))
            .build());
    setEppInput("domain_check_fee_multiple_currencies_stdv1.xml");
    assertAboutEppExceptions()
        .that(assertThrows(MultipleCurrenciesCannotBeCheckedException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testSuccess_eapFeeCheck_v06() throws Exception {
    runEapFeeCheckTest(
        "domain_check_fee_v06.xml",
        "domain_check_eap_fee_response_v06.xml",
        new FeeFileLoader(/* isFeeStdV1= */ false));
  }

  @Test
  void testSuccess_eapFeeCheck_v11() throws Exception {
    runEapFeeCheckTest(
        "domain_check_fee_v11.xml",
        "domain_check_eap_fee_response_v11.xml",
        new FeeFileLoader(/* isFeeStdV1= */ false));
  }

  @Test
  void testSuccess_eapFeeCheck_v12() throws Exception {
    runEapFeeCheckTest(
        "domain_check_fee_v12.xml",
        "domain_check_eap_fee_response_v12.xml",
        new FeeFileLoader(/* isFeeStdV1= */ false));
  }

  @Test
  void testSuccess_eapFeeCheck_date_v12() throws Exception {
    runEapFeeCheckTest(
        "domain_check_fee_date_v12.xml",
        "domain_check_eap_fee_response_date_v12.xml",
        new FeeFileLoader(/* isFeeStdV1= */ false));
  }

  @Test
  void testSuccess_eapFeeCheck_std_v1() throws Exception {
    runEapFeeCheckTest(
        "domain_check_fee_stdv1.xml",
        "domain_check_eap_fee_response_stdv1.xml",
        new FeeFileLoader(/* isFeeStdV1= */ true));
  }

  @Test
  void testSuccess_feeCheck_multipleRanges() {
    // TODO: If at some point we have more than one type of fees that are time dependent, populate
    // this test to test if the notAfter date is the earliest of the end points of the ranges.
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    createTlds("com", "net", "org");
    setEppInput("domain_check.xml");
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-check");
    assertTldsFieldLogged("com", "net", "org");
  }

  @Test
  void testTieredPricingPromoResponse() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    setUpDefaultToken("NewRegistrar");
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_tiered_promotion_fee_response_v12.xml"));
  }

  @Test
  void testTieredPricingPromo_registrarNotIncluded_standardResponse() throws Exception {
    setUpDefaultToken("NewRegistrar");
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  @Test
  void testTieredPricingPromo_registrarIncluded_noTokenActive() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveDomain("example1.tld");

    persistResource(
        setUpDefaultToken("NewRegistrar")
            .asBuilder()
            .setTokenStatusTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    TokenStatus.NOT_STARTED,
                    clock.nowUtc().plusDays(1),
                    TokenStatus.VALID))
            .build());

    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  private Domain persistPendingDeleteDomain(String domainName) {
    Domain existingDomain =
        persistResource(
            DatabaseHelper.newDomain(domainName)
                .asBuilder()
                .setDeletionTime(clock.nowUtc().plusDays(25))
                .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .build());
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(existingDomain)
                .setType(HistoryEntry.Type.DOMAIN_DELETE)
                .setModificationTime(existingDomain.getCreationTime())
                .setRegistrarId(existingDomain.getCreationRegistrarId())
                .build());
    BillingRecurrence renewEvent =
        persistResource(
            new BillingRecurrence.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(existingDomain.getDomainName())
                .setRegistrarId("TheRegistrar")
                .setEventTime(existingDomain.getCreationTime())
                .setRecurrenceEndTime(clock.nowUtc())
                .setDomainHistory(historyEntry)
                .build());
    return persistResource(
        existingDomain.asBuilder().setAutorenewBillingEvent(renewEvent.createVKey()).build());
  }

  private void runEapFeeCheckTest(String inputFile, String outputFile, FeeFileLoader loader)
      throws Exception {
    runEapFeeCheckTestWithXmlInputOutput(
        loader.load(this, inputFile, ImmutableMap.of("CURRENCY", "USD")),
        loader.load(this, outputFile));
  }

  private void runEapFeeCheckTestWithXmlInputOutput(String inputXml, String outputXml)
      throws Exception {
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistActiveDomain("example1.tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(clock.nowUtc().minusDays(1), Money.of(USD, 100))
                    .put(clock.nowUtc().plusDays(1), Money.of(USD, 50))
                    .put(clock.nowUtc().plusDays(2), Money.of(USD, 0))
                    .build())
            .build());
    setEppInputXml(inputXml);
    runFlowAssertResponse(outputXml);
  }

  private AllocationToken setUpDefaultToken() {
    return setUpDefaultToken("TheRegistrar");
  }

  private AllocationToken setUpDefaultToken(String registrarId) {
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of(registrarId))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    return defaultToken;
  }

  private static class FeeFileLoader {
    private final boolean isFeeStdV1;

    FeeFileLoader(boolean isFeeStdV1) {
      this.isFeeStdV1 = isFeeStdV1;
    }

    String load(DomainCheckFlowTest test, String filename) {
      return isFeeStdV1 ? test.loadFeeV12FileAsStdV1(filename) : test.loadFile(filename);
    }

    String load(DomainCheckFlowTest test, String filename, Map<String, String> substitutions) {
      return isFeeStdV1
          ? test.loadFeeV12FileAsStdV1(filename, substitutions)
          : test.loadFile(filename, substitutions);
    }
  }

  /**
   * Fee versions 0.12 and 1.0 are the same in how they're requested (though not in the responses.
   * As a result, for tests that throw an exception, we can use the same XML content.
   */
  @SuppressWarnings("unused")
  private static Stream<Arguments> provideFeeTestParams() {
    return Stream.of(
        Arguments.of("fee_std_v1", new FeeFileLoader(true)),
        Arguments.of("fee_12", new FeeFileLoader(false)));
  }
}
