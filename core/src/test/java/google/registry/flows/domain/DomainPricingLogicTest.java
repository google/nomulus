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

package google.registry.flows.domain;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingBase.Flag.AUTO_RENEW;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.fee.BaseFee.FeeType.CREATE;
import static google.registry.model.domain.fee.BaseFee.FeeType.RENEW;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.reporting.HistoryEntry.Type.DOMAIN_CREATE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.HttpSessionMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForCurrencyException;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForPremiumNameException;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.eppinput.EppInput;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.util.Clock;
import java.math.BigDecimal;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;

/** Unit tests for {@link DomainPricingLogic}. */
public class DomainPricingLogicTest {
  DomainPricingLogic domainPricingLogic;

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  Clock clock = new FakeClock(DateTime.parse("2023-05-13T00:00:00.000Z"));
  @Mock EppInput eppInput;
  SessionMetadata sessionMetadata;
  Tld tld;
  Domain domain;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("example");
    sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    domainPricingLogic =
        new DomainPricingLogic(new DomainPricingCustomLogic(eppInput, sessionMetadata, null));
    tld =
        persistResource(
            Tld.get("example")
                .asBuilder()
                .setRenewBillingCostTransitions(
                    ImmutableSortedMap.of(
                        START_OF_TIME, Money.of(USD, 1), clock.nowUtc(), Money.of(USD, 10)))
                .setPremiumList(persistPremiumList("tld2", USD, "premium,USD 100"))
                .build());
  }

  /** helps to set up the domain info and returns a recurrence billing event for testing */
  private BillingRecurrence persistDomainAndSetRecurrence(
      String domainName, RenewalPriceBehavior renewalPriceBehavior, Optional<Money> renewalPrice) {
    domain =
        persistResource(
            DatabaseHelper.newDomain(domainName)
                .asBuilder()
                .setCreationTimeForTest(DateTime.parse("1999-01-05T00:00:00Z"))
                .build());
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(DOMAIN_CREATE)
                .setModificationTime(DateTime.parse("1999-01-05T00:00:00Z"))
                .setDomain(domain)
                .build());
    BillingRecurrence billingRecurrence =
        persistResource(
            new BillingRecurrence.Builder()
                .setDomainHistory(historyEntry)
                .setRegistrarId(domain.getCreationRegistrarId())
                .setEventTime(DateTime.parse("1999-01-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRenewalPriceBehavior(renewalPriceBehavior)
                .setRenewalPrice(renewalPrice.orElse(null))
                .setRecurrenceEndTime(END_OF_TIME)
                .setTargetId(domain.getDomainName())
                .build());
    persistResource(
        domain.asBuilder().setAutorenewBillingEvent(billingRecurrence.createVKey()).build());
    return billingRecurrence;
  }

  @Test
  void testGetDomainCreatePrice_sunrise_appliesDiscount() throws EppException {
    ImmutableSortedMap<DateTime, TldState> transitions =
        ImmutableSortedMap.<DateTime, TldState>naturalOrder()
            .put(START_OF_TIME, TldState.PREDELEGATION)
            .put(clock.nowUtc().minusHours(1), TldState.START_DATE_SUNRISE)
            .put(clock.nowUtc().plusHours(1), TldState.GENERAL_AVAILABILITY)
            .build();
    Tld sunriseTld = createTld("sunrise", transitions);
    assertThat(
            domainPricingLogic.getCreatePrice(
                sunriseTld, "domain.sunrise", clock.nowUtc(), 2, false, true, Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                // (13 + 11) * 0.85 == 20.40
                .addFeeOrCredit(Fee.create(new BigDecimal("20.40"), CREATE, false))
                .build());
  }

  @Test
  void testGetDomainCreatePrice_discountPriceAllocationToken_oneYearCreate_appliesDiscount()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("default.example")
                .setDiscountPrice(Money.of(USD, 5))
                .setDiscountYears(1)
                .setRegistrationBehavior(RegistrationBehavior.DEFAULT)
                .build());
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "default.example",
                clock.nowUtc(),
                1,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), CREATE, false))
                .build());
  }

  @Test
  void testGetDomainCreatePrice_discountPriceAllocationToken_multiYearCreate_appliesDiscount()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("default.example")
                .setDiscountPrice(Money.of(USD, 5))
                .setDiscountYears(1)
                .setRegistrationBehavior(RegistrationBehavior.DEFAULT)
                .build());

    // 3 year create should be 5 (discount price) + 10*2 (regular price) = 25.
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "default.example",
                clock.nowUtc(),
                3,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("25.00"), CREATE, false))
                .build());
  }

  @Test
  void
      testGetDomainCreatePrice_withDiscountPriceToken_domainCurrencyDoesNotMatchTokensCurrency_throwsException() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(JPY, new BigDecimal("250")))
                .setDiscountPremiums(false)
                .build());

    // Domain's currency is not JPY (is USD).
    assertThrows(
        AllocationTokenInvalidForCurrencyException.class,
        () ->
            domainPricingLogic.getCreatePrice(
                tld,
                "default.example",
                clock.nowUtc(),
                3,
                false,
                false,
                Optional.of(allocationToken)));
  }

  @Test
  void testGetDomainRenewPrice_oneYear_standardDomain_noBilling_isStandardPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld, "standard.example", clock.nowUtc(), 1, null, Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_standardDomain_noBilling_isStandardPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld, "standard.example", clock.nowUtc(), 5, null, Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("50.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_premiumDomain_noBilling_isPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld, "premium.example", clock.nowUtc(), 1, null, Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("100.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_premiumDomain_noBilling_isPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld, "premium.example", clock.nowUtc(), 5, null, Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("500.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_premiumDomain_default_isPremiumPrice() throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("100.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_premiumDomain_default_withToken_isPremiumPrice()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(true)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("50.00"), RENEW, true))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_premiumDomain_default_withTokenNotValidForPremiums_throwsException() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .build());
    assertThrows(
        AllocationTokenInvalidForPremiumNameException.class,
        () ->
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)));
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_premiumDomain_default_withDiscountPriceToken_throwsException() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(USD, 5))
                .setDiscountPremiums(false)
                .build());
    assertThrows(
        AllocationTokenInvalidForPremiumNameException.class,
        () ->
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)));
  }

  @Test
  void
      testGetDomainRenewPrice_withDiscountPriceToken_domainCurrencyDoesNotMatchTokensCurrency_throwsException() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(JPY, new BigDecimal("250")))
                .setDiscountPremiums(false)
                .build());

    // Domain's currency is not JPY (is USD).
    assertThrows(
        AllocationTokenInvalidForCurrencyException.class,
        () ->
            domainPricingLogic.getRenewPrice(
                tld,
                "default.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("default.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)));
  }

  @Test
  void testGetDomainRenewPrice_multiYear_premiumDomain_default_isPremiumCost() throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("500.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_premiumDomain_default_withToken_isPremiumPrice()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(true)
                .setDiscountYears(2)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("400.00"), RENEW, true))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_multiYear_premiumDomain_default_withTokenNotValidForPremiums_throwsException() {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .setDiscountYears(2)
                .build());
    assertThrows(
        AllocationTokenInvalidForPremiumNameException.class,
        () ->
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)));
  }

  @Test
  void testGetDomainRenewPrice_oneYear_standardDomain_default_isNonPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_standardDomain_default_withToken_isDiscountedPrice()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_standardDomain_default_withDiscountPriceToken_isDiscountedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(USD, 1.5))
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.50"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_standardDomain_default_isNonPremiumCost()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("50.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_standardDomain_default_withToken_isDiscountedPrice()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .setDiscountYears(2)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("40.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_multiYear_standardDomain_default_withDiscountPriceToken_isDiscountedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("discountPrice12345")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(USD, 2.5))
                .setDiscountPremiums(false)
                .setDiscountYears(2)
                .build());

    // 5 year create should be 2*2.5 (discount price) + 10*3 (regular price) = 35.
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("35.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_premiumDomain_anchorTenant_isNonPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", NONPREMIUM, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_premiumDomain_anchorTenant_withToken_isDiscountedNonPremiumPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", NONPREMIUM, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_premiumDomain_anchorTenant_isNonPremiumCost()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("premium.example", NONPREMIUM, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("50.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_multiYear_premiumDomain_anchorTenant_withToken_isDiscountedNonPremiumPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .setDiscountYears(2)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("premium.example", NONPREMIUM, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("40.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_standardDomain_anchorTenant_isNonPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("standard.example", NONPREMIUM, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_standardDomain_anchorTenant_isNonPremiumCost()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence("standard.example", NONPREMIUM, Optional.empty()),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("50.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_standardDomain_internalRegistration_isSpecifiedPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_standardDomain_internalRegistration_withToken_isSpecifiedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.of(allocationToken)))

        // The allocation token should not discount the speicifed price
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_standardDomain_internalRegistration_withDiscountPriceToken_isSpecifiedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(USD, 0.5))
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.of(allocationToken)))

        // The allocation token should not discount the speicifed price
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_oneYear_standardDomain_internalRegistration_withToken_doesNotChangePriceBehavior()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setRenewalPriceBehavior(DEFAULT)
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.of(allocationToken)))

        // The allocation token should not discount the speicifed price
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.00"), RENEW, false))
                .build());
    assertThat(
            Iterables.getLast(DatabaseHelper.loadAllOf(BillingRecurrence.class))
                .getRenewalPriceBehavior())
        .isEqualTo(SPECIFIED);
  }

  @Test
  void testGetDomainRenewPrice_multiYear_standardDomain_internalRegistration_isSpecifiedPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_multiYear_standardDomain_internalRegistration_withToken_isSpecifiedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), RENEW, false))
                .build());
  }

  @Test
  void
      testGetDomainRenewPrice_multiYear_standardDomain_internalRegistration_withDiscountPriceToken_isSpecifiedPrice()
          throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountPrice(Money.of(USD, 0.5))
                .setDiscountPremiums(false)
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1))),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("5.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_oneYear_premiumDomain_internalRegistration_isSpecifiedPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence(
                    "premium.example", SPECIFIED, Optional.of(Money.of(USD, 17))),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("17.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_multiYear_premiumDomain_internalRegistration_isSpecifiedPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                5,
                persistDomainAndSetRecurrence(
                    "premium.example", SPECIFIED, Optional.of(Money.of(USD, 17))),
                Optional.empty()))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("85.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainRenewPrice_negativeYear_throwsException() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                domainPricingLogic.getRenewPrice(
                    tld, "standard.example", clock.nowUtc(), -1, null, Optional.empty()));
    assertThat(thrown).hasMessageThat().isEqualTo("Number of years must be positive");
  }

  @Test
  void testGetDomainTransferPrice_standardDomain_default_noBilling_defaultRenewalPrice()
      throws EppException {
    assertThat(domainPricingLogic.getTransferPrice(tld, "standard.example", clock.nowUtc(), null))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_default_noBilling_premiumRenewalPrice()
      throws EppException {
    assertThat(domainPricingLogic.getTransferPrice(tld, "premium.example", clock.nowUtc(), null))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("100.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_standardDomain_default_defaultRenewalPrice() throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence("standard.example", DEFAULT, Optional.empty())))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_default_premiumRenewalPrice() throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty())))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("100.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_standardDomain_nonPremium_nonPremiumRenewalPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence("standard.example", NONPREMIUM, Optional.empty())))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_nonPremium_nonPremiumRenewalPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence("premium.example", NONPREMIUM, Optional.empty())))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_standardDomain_specified_specifiedRenewalPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "standard.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence(
                    "standard.example", SPECIFIED, Optional.of(Money.of(USD, 1.23)))))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.23"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_specified_specifiedRenewalPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getTransferPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                persistDomainAndSetRecurrence(
                    "premium.example", SPECIFIED, Optional.of(Money.of(USD, 1.23)))))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("1.23"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainCreatePrice_nonPremiumCreate_unaffectedRenewal() throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("premium.example")
                .setRegistrationBehavior(AllocationToken.RegistrationBehavior.NONPREMIUM_CREATE)
                .build());
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("13.00"), CREATE, false))
                .build());
    // Two-year create should be 13 (standard price) + 100 (premium price), and it's premium
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                2,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("113.00"), CREATE, true))
                .build());
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                1,
                persistDomainAndSetRecurrence("premium.example", DEFAULT, Optional.empty()),
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("100.00"), RENEW, true))
                .build());
  }

  @Test
  void testGetDomainCreatePrice_premium_multiYear_nonpremiumCreateAndRenewal() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("premium.example")
                .setRegistrationBehavior(AllocationToken.RegistrationBehavior.NONPREMIUM_CREATE)
                .setRenewalPriceBehavior(NONPREMIUM)
                .build());
    // Two-year create should be standard create (13) + renewal (10) because both create and renewal
    // are standard
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                2,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("23.00"), CREATE, false))
                .build());
    // Similarly, 3 years should be 13 + 10 + 10
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                3,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("33.00"), CREATE, false))
                .build());
  }

  @Test
  void testGetDomainCreatePrice_premium_multiYear_onlyNonpremiumRenewal() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("premium.example")
                .setRenewalPriceBehavior(NONPREMIUM)
                .build());
    // Two-year create should be 100 (premium 1st year) plus 10 (nonpremium 2nd year)
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                2,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("110.00"), CREATE, true))
                .build());
    // Similarly, 3 years should be 100 + 10 + 10
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "premium.example",
                clock.nowUtc(),
                3,
                false,
                false,
                Optional.of(allocationToken)))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("120.00"), CREATE, true))
                .build());
  }
}
