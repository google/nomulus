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
import static google.registry.util.DateTimeUtils.END_INSTANT;
import static google.registry.util.DateTimeUtils.START_INSTANT;
import static google.registry.util.DateTimeUtils.minusHours;
import static google.registry.util.DateTimeUtils.plusHours;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Range;
import google.registry.flows.EppException;
import google.registry.flows.HttpSessionMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.custom.DomainPricingCustomLogic;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
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

  Clock clock = new FakeClock(Instant.parse("2023-05-13T00:00:00.000Z"));
  @Mock EppInput eppInput;
  SessionMetadata sessionMetadata;
  Tld tld;
  Domain domain;

  @BeforeEach
  void beforeEach() throws Exception {
    createTld("example");
    sessionMetadata = new HttpSessionMetadata(new FakeHttpSession());
    domainPricingLogic =
        new DomainPricingLogic(
            new DomainPricingCustomLogic(eppInput, sessionMetadata, null),
            Duration.ofDays(10),
            Duration.ofHours(1),
            ImmutableMap.of(USD, new BigDecimal("100000.00"), JPY, new BigDecimal("10000000")),
            ImmutableMap.of(USD, new BigDecimal("10.00"), JPY, new BigDecimal("1000")));
    tld =
        persistResource(
            Tld.get("example")
                .asBuilder()
                .setRenewBillingCostTransitions(
                    ImmutableSortedMap.of(
                        START_INSTANT, Money.of(USD, 1), clock.now(), Money.of(USD, 10)))
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
                .setCreationTimeForTest(Instant.parse("1999-01-05T00:00:00Z"))
                .build());
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setRegistrarId(domain.getCreationRegistrarId())
                .setType(DOMAIN_CREATE)
                .setModificationTime(Instant.parse("1999-01-05T00:00:00Z"))
                .setDomain(domain)
                .build());
    BillingRecurrence billingRecurrence =
        persistResource(
            new BillingRecurrence.Builder()
                .setDomainHistory(historyEntry)
                .setRegistrarId(domain.getCreationRegistrarId())
                .setEventTime(Instant.parse("1999-01-05T00:00:00Z"))
                .setFlags(ImmutableSet.of(AUTO_RENEW))
                .setId(2L)
                .setReason(Reason.RENEW)
                .setRenewalPriceBehavior(renewalPriceBehavior)
                .setRenewalPrice(renewalPrice.orElse(null))
                .setRecurrenceEndTime(END_INSTANT)
                .setTargetId(domain.getDomainName())
                .build());
    persistResource(
        domain.asBuilder().setAutorenewBillingEvent(billingRecurrence.createVKey()).build());
    return billingRecurrence;
  }

  @Test
  void testGetDomainCreatePrice_sunrise_appliesDiscount() throws EppException {
    ImmutableSortedMap<Instant, TldState> transitions =
        ImmutableSortedMap.<Instant, TldState>naturalOrder()
            .put(START_INSTANT, TldState.PREDELEGATION)
            .put(minusHours(clock.now(), 1), TldState.START_DATE_SUNRISE)
            .put(plusHours(clock.now(), 1), TldState.GENERAL_AVAILABILITY)
            .build();
    createTld("sunrise");
    Tld sunriseTld =
        persistResource(Tld.get("sunrise").asBuilder().setTldStateTransitions(transitions).build());
    assertThat(
            domainPricingLogic.getCreatePrice(
                sunriseTld,
                "domain.sunrise",
                clock.now(),
                Optional.empty(),
                2,
                false,
                true,
                Optional.empty()))
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
                Optional.empty(),
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
  void testGetDomainCreatePrice_discountPriceAllocationToken_oneYearCreate_moreDiscountYears()
      throws EppException {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123_more_discount")
                .setTokenType(SINGLE_USE)
                .setDomainName("default.example")
                .setDiscountPrice(Money.of(USD, 5))
                .setDiscountYears(2)
                .setRegistrationBehavior(RegistrationBehavior.DEFAULT)
                .build());
    assertThat(
            domainPricingLogic.getCreatePrice(
                tld,
                "default.example",
                clock.now(),
                Optional.empty(),
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
  void testGetDomainRenewPrice_oneYear_standardDomain_noBilling_isStandardPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld, "standard.example", clock.now(), 1, null, Optional.empty()))
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
                tld, "standard.example", clock.now(), 5, null, Optional.empty()))
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
                tld, "premium.example", clock.now(), 1, null, Optional.empty()))
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
                tld, "premium.example", clock.now(), 5, null, Optional.empty()))
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
                clock.now(),
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
                clock.now(),
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
  void testGetDomainRenewPrice_multiYear_premiumDomain_default_isPremiumCost() throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "premium.example",
                clock.now(),
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
                clock.now(),
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
  void testGetDomainRenewPrice_oneYear_standardDomain_default_isNonPremiumPrice()
      throws EppException {
    assertThat(
            domainPricingLogic.getRenewPrice(
                tld,
                "standard.example",
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                    tld, "standard.example", clock.now(), -1, null, Optional.empty()));
    assertThat(thrown).hasMessageThat().isEqualTo("Number of years must be positive");
  }

  @Test
  void testGetDomainTransferPrice_standardDomain_default_noBilling_defaultRenewalPrice()
      throws EppException {
    assertThat(domainPricingLogic.getTransferPrice(tld, "standard.example", clock.now(), null))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(new BigDecimal("10.00"), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_default_noBilling_premiumRenewalPrice()
      throws EppException {
    assertThat(domainPricingLogic.getTransferPrice(tld, "premium.example", clock.now(), null))
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
                Optional.empty(),
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
                clock.now(),
                Optional.empty(),
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

  @Test
  void testDomainRenewPrice_specifiedToken() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("premium.example")
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 5))
                .build());
    assertThat(
            domainPricingLogic
                .getRenewPrice(
                    tld, "premium.example", clock.now(), 1, null, Optional.of(allocationToken))
                .getRenewCost())
        .isEqualTo(Money.of(USD, 5));
  }

  @Test
  void testDomainRenewPrice_specifiedToken_multiYear() throws Exception {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123_multi")
                .setTokenType(SINGLE_USE)
                .setDomainName("premium.example")
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 5))
                .build());
    assertThat(
            domainPricingLogic
                .getRenewPrice(
                    tld, "premium.example", clock.now(), 5, null, Optional.of(allocationToken))
                .getRenewCost())
        .isEqualTo(Money.of(USD, 25));
  }

  @Test
  void testGetXapFeeFor_tier0_startOfXap() {
    Instant deletionTime = clock.now();
    Instant checkTime = deletionTime.plus(Duration.ofMinutes(30));
    Optional<Fee> xapFee = domainPricingLogic.getXapFeeFor(checkTime, deletionTime, USD);
    assertThat(xapFee)
        .hasValue(
            Fee.create(
                new BigDecimal("100000.00"),
                Fee.FeeType.XAP,
                false,
                Range.closedOpen(deletionTime, deletionTime.plus(Duration.ofHours(1))),
                deletionTime.plus(Duration.ofHours(1))));
  }

  @Test
  void testGetXapFeeFor_tier1() {
    Instant deletionTime = clock.now();
    Instant checkTime = deletionTime.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(15));
    Optional<Fee> xapFee = domainPricingLogic.getXapFeeFor(checkTime, deletionTime, USD);
    assertThat(xapFee)
        .hasValue(
            Fee.create(
                new BigDecimal("96219.61"),
                Fee.FeeType.XAP,
                false,
                Range.closedOpen(
                    deletionTime.plus(Duration.ofHours(1)), deletionTime.plus(Duration.ofHours(2))),
                deletionTime.plus(Duration.ofHours(2))));
  }

  @Test
  void testGetXapFeeFor_tier239_finalTier() {
    Instant deletionTime = clock.now();
    Instant checkTime = deletionTime.plus(Duration.ofHours(239)).plus(Duration.ofMinutes(30));
    Optional<Fee> xapFee = domainPricingLogic.getXapFeeFor(checkTime, deletionTime, USD);
    assertThat(xapFee)
        .hasValue(
            Fee.create(
                new BigDecimal("10.00"),
                Fee.FeeType.XAP,
                false,
                Range.closedOpen(
                    deletionTime.plus(Duration.ofHours(239)),
                    deletionTime.plus(Duration.ofHours(240))),
                deletionTime.plus(Duration.ofHours(240))));
  }

  @Test
  void testGetXapFeeFor_unconfiguredCurrency() {
    Instant deletionTime = clock.now();
    Instant checkTime = deletionTime.plus(Duration.ofMinutes(30));
    Optional<Fee> xapFee =
        domainPricingLogic.getXapFeeFor(checkTime, deletionTime, CurrencyUnit.EUR);
    assertThat(xapFee).isEmpty();
  }

  @Test
  void testGetCreatePrice_xapEnabled_includesXapFeeAndRequiresExtension() throws Exception {
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("deleted.example")
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        domainPricingLogic.getCreatePrice(
            xapTld,
            "deleted.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.isFeeExtensionRequired()).isTrue();
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.of(USD, new BigDecimal("96219.61")));
    assertThat(feesAndCredits.getTotalCost()).isEqualTo(Money.of(USD, new BigDecimal("96232.61")));
  }

  @Test
  void testGetXapFeeFor_afterXapEnds_returnsEmpty() {
    Instant deletionTime = clock.now();
    Instant checkTime = deletionTime.plus(Duration.ofDays(10)).plus(Duration.ofMinutes(1));
    Optional<Fee> xapFee = domainPricingLogic.getXapFeeFor(checkTime, deletionTime, USD);
    assertThat(xapFee).isEmpty();
  }

  @Test
  void testGetCreatePrice_agpDeletedRegularDomain_duringXap_noXapFee() throws Exception {
    // A regular domain deleted during AGP is exempt from XAP fee evaluation upon subsequent
    // creation during active XAP.
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(
                        START_INSTANT,
                        Tld.ExpiryAccessPeriodMode.DISABLED,
                        clock.now().minus(Duration.ofHours(1)),
                        Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant creationTime = clock.now().minus(Duration.ofDays(2));
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("deleted.example")
            .setCreationTime(creationTime)
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        domainPricingLogic.getCreatePrice(
            xapTld,
            "deleted.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.isFeeExtensionRequired()).isFalse();
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.zero(USD));
    assertThat(feesAndCredits.getTotalCost()).isEqualTo(Money.of(USD, new BigDecimal("13.00")));
  }

  @Test
  void testGetCreatePrice_agpDeletedXapDomain_duringXap_noXapFee() throws Exception {
    // A domain originally registered with an XAP fee deleted during AGP is exempt from XAP fee
    // evaluation upon subsequent creation during active XAP.
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant creationTime = clock.now().minus(Duration.ofHours(2));
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("deleted.example")
            .setCreationTime(creationTime)
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        domainPricingLogic.getCreatePrice(
            xapTld,
            "deleted.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.isFeeExtensionRequired()).isFalse();
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.zero(USD));
    assertThat(feesAndCredits.getTotalCost()).isEqualTo(Money.of(USD, new BigDecimal("13.00")));
  }

  @Test
  void testGetCreatePrice_anchorTenantDeletedAfterStandardAgp_duringXap_chargesXapFee()
      throws Exception {
    // An anchor tenant domain deleted after standard AGP (5 days), e.g. on day 10 of its 30-day
    // anchor tenant AGP, is subject to XAP fees upon re-registration (not exempt as an AGP delete).
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setAddGracePeriodLength(Duration.ofDays(5))
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant creationTime = clock.now().minus(Duration.ofDays(10));
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("deleted.example")
            .setCreationTime(creationTime)
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        domainPricingLogic.getCreatePrice(
            xapTld,
            "deleted.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.isFeeExtensionRequired()).isTrue();
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.of(USD, new BigDecimal("96219.61")));
  }

  @Test
  void testGetCreatePrice_premiumDomainInXap_chargesPremiumAndXapFees() throws Exception {
    // Calculating create price for a recently deleted premium domain during active XAP should sum
    // both the premium create fee and the one-time XAP tier fee.
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("premium.example")
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        domainPricingLogic.getCreatePrice(
            xapTld,
            "premium.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.hasAnyPremiumFees()).isTrue();
    assertThat(feesAndCredits.isFeeExtensionRequired()).isTrue();
    assertThat(feesAndCredits.getCreateCost()).isEqualTo(Money.of(USD, new BigDecimal("100.00")));
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.of(USD, new BigDecimal("96219.61")));
    assertThat(feesAndCredits.getTotalCost()).isEqualTo(Money.of(USD, new BigDecimal("96319.61")));
  }

  @Test
  void testGetCreatePrice_zeroXapFee_doesNotRequireExtension() throws Exception {
    // When an XAP tier fee is $0.00, it is included in the fee items but does not require a fee
    // extension acknowledgment from the registrar.
    Tld xapTld =
        persistResource(
            tld.asBuilder()
                .setExpiryAccessPeriodTransitions(
                    ImmutableSortedMap.of(START_INSTANT, Tld.ExpiryAccessPeriodMode.ENABLED))
                .build());
    DomainPricingLogic zeroFeePricingLogic =
        new DomainPricingLogic(
            new DomainPricingCustomLogic(eppInput, sessionMetadata, null),
            Duration.ofDays(10),
            Duration.ofHours(1),
            ImmutableMap.of(USD, BigDecimal.ZERO),
            ImmutableMap.of(USD, BigDecimal.ZERO));
    Instant deletionTime = clock.now().minus(Duration.ofHours(1));
    Domain deletedDomain =
        new Domain.Builder()
            .setDomainName("deleted.example")
            .setDeletionTime(deletionTime)
            .setCreationRegistrarId("TheRegistrar")
            .setPersistedCurrentSponsorRegistrarId("TheRegistrar")
            .setRepoId("2-EXAMPLE")
            .build();
    FeesAndCredits feesAndCredits =
        zeroFeePricingLogic.getCreatePrice(
            xapTld,
            "deleted.example",
            clock.now(),
            Optional.of(deletedDomain),
            1,
            false,
            false,
            Optional.empty());
    assertThat(feesAndCredits.isFeeExtensionRequired()).isFalse();
    assertThat(feesAndCredits.getXapCost()).isEqualTo(Money.zero(USD));
    assertThat(feesAndCredits.getTotalCost()).isEqualTo(Money.of(USD, new BigDecimal("13.00")));
  }
}
