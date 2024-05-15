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
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.HttpSessionMetadata;
import google.registry.flows.SessionMetadata;
import google.registry.flows.custom.DomainPricingCustomLogic;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForPremiumNameException;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppinput.EppInput;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeHttpSession;
import google.registry.util.Clock;
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 20.4).getAmount(), CREATE, false))
                .build());
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 50).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 100).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 500).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 100).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 50).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 500).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 400).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 5).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 50).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 40).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 5).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 50).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 40).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 50).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 1).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 1).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 1).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 5).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 5).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 17).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 85).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
                .build());
  }

  @Test
  void testGetDomainTransferPrice_premiumDomain_default_noBilling_premiumRenewalPrice()
      throws EppException {
    assertThat(domainPricingLogic.getTransferPrice(tld, "premium.example", clock.nowUtc(), null))
        .isEqualTo(
            new FeesAndCredits.Builder()
                .setCurrency(USD)
                .addFeeOrCredit(Fee.create(Money.of(USD, 100).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 100).getAmount(), RENEW, true))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 10).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 1.23).getAmount(), RENEW, false))
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
                .addFeeOrCredit(Fee.create(Money.of(USD, 1.23).getAmount(), RENEW, false))
                .build());
  }
}
