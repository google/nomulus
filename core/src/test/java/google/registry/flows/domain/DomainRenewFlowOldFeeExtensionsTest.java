// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.TestDataHelper.updateSubstitutions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.testing.DatabaseHelper;
import java.util.Map;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainRenewFlow} that use the old fee extensions (0.6, 0.11, 0.12). */
public class DomainRenewFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainRenewFlow> {

  private static final ImmutableMap<String, String> FEE_BASE_MAP =
      ImmutableMap.of(
          "NAME", "example.tld",
          "PERIOD", "5",
          "EX_DATE", "2005-04-03T22:00:00.0Z",
          "FEE", "55.00",
          "CURRENCY", "USD");

  private static final ImmutableMap<String, String> FEE_06_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "fee-0.6", "FEE_NS", "fee");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "fee-0.11", "FEE_NS", "fee11");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "fee-0.12", "FEE_NS", "fee12");

  private final DateTime expirationTime = DateTime.parse("2000-04-03T22:00:00.0Z");

  @BeforeEach
  void beforeEachDomainRenewFlowOldFeeExtensionsTest() {
    clock.setTo(expirationTime.minusMillis(20));
    createTld("tld");
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "123", EUR, "567"))
            .build());
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "5"));
  }

  private void persistDomain(StatusValue... statusValues) throws Exception {
    persistDomain(DEFAULT, null, statusValues);
  }

  private void persistDomain(
      RenewalPriceBehavior renewalPriceBehavior,
      @Nullable Money renewalPrice,
      StatusValue... statusValues)
      throws Exception {
    Domain domain = DatabaseHelper.newDomain(getUniqueIdFromCommand());
    try {
      DomainHistory historyEntryDomainCreate =
          new DomainHistory.Builder()
              .setDomain(domain)
              .setType(HistoryEntry.Type.DOMAIN_CREATE)
              .setModificationTime(clock.nowUtc())
              .setRegistrarId(domain.getCreationRegistrarId())
              .build();
      BillingRecurrence autorenewEvent =
          new BillingRecurrence.Builder()
              .setReason(Reason.RENEW)
              .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
              .setTargetId(getUniqueIdFromCommand())
              .setRegistrarId("TheRegistrar")
              .setEventTime(expirationTime)
              .setRecurrenceEndTime(END_OF_TIME)
              .setDomainHistory(historyEntryDomainCreate)
              .setRenewalPriceBehavior(renewalPriceBehavior)
              .setRenewalPrice(renewalPrice)
              .build();
      PollMessage.Autorenew autorenewPollMessage =
          new PollMessage.Autorenew.Builder()
              .setTargetId(getUniqueIdFromCommand())
              .setRegistrarId("TheRegistrar")
              .setEventTime(expirationTime)
              .setAutorenewEndTime(END_OF_TIME)
              .setMsg("Domain was auto-renewed.")
              .setHistoryEntry(historyEntryDomainCreate)
              .build();
      Domain newDomain =
          domain
              .asBuilder()
              .setRegistrationExpirationTime(expirationTime)
              .setStatusValues(ImmutableSet.copyOf(statusValues))
              .setAutorenewBillingEvent(autorenewEvent.createVKey())
              .setAutorenewPollMessage(autorenewPollMessage.createVKey())
              .build();
      persistResources(
          ImmutableSet.of(
              historyEntryDomainCreate, autorenewEvent,
              autorenewPollMessage, newDomain));
    } catch (Exception e) {
      throw new RuntimeException("Error persisting domain", e);
    }
    clock.advanceOneMilli();
  }

  @Test
  void testFailure_wrongFeeAmount_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 13)))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setRegistryLockOrUnlockBillingCost(Money.of(EUR, 20))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistDomain();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 13)))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setRegistryLockOrUnlockBillingCost(Money.of(EUR, 20))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistDomain();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 13)))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setRegistryLockOrUnlockBillingCost(Money.of(EUR, 20))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .build());
    persistDomain();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v06() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v11() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v12() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_doesNotApplyNonPremiumDefaultTokenToPremiumName_v06() throws Exception {
    ImmutableMap<String, String> customFeeMap = updateSubstitutions(FEE_06_MAP, "FEE", "500");
    setEppInput("domain_renew_fee.xml", customFeeMap);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 100"))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response_fee.xml",
            ImmutableMap.of(
                "NAME",
                "example.tld",
                "PERIOD",
                "5",
                "EX_DATE",
                "2005-04-03T22:00:00.0Z",
                "FEE",
                "500.00",
                "CURRENCY",
                "USD",
                "FEE_VERSION",
                "fee-0.6",
                "FEE_NS",
                "fee")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken()).isEmpty();
  }

  @Test
  void testSuccess_internalRegiration_premiumDomain_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 100"))
            .build());
    persistDomain(SPECIFIED, Money.of(USD, 2));
    setRegistrarIdForFlow("NewRegistrar");
    ImmutableMap<String, String> customFeeMap = updateSubstitutions(FEE_06_MAP, "FEE", "10.00");
    setEppInput("domain_renew_fee.xml", customFeeMap);
    doSuccessfulTest(
        "domain_renew_response_fee.xml",
        5,
        "NewRegistrar",
        UserPrivileges.SUPERUSER,
        customFeeMap,
        Money.of(USD, 10),
        SPECIFIED,
        Money.of(USD, 2));
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response_fee.xml",
            ImmutableMap.of(
                "NAME",
                "example.tld",
                "PERIOD",
                "5",
                "EX_DATE",
                "2005-04-03T22:00:00.0Z",
                "FEE",
                "49.50",
                "CURRENCY",
                "USD",
                "FEE_VERSION",
                "fee-0.6",
                "FEE_NS",
                "fee")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response_fee.xml",
            ImmutableMap.of(
                "NAME",
                "example.tld",
                "PERIOD",
                "5",
                "EX_DATE",
                "2005-04-03T22:00:00.0Z",
                "FEE",
                "49.50",
                "CURRENCY",
                "USD",
                "FEE_VERSION",
                "fee-0.11",
                "FEE_NS",
                "fee")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response_fee.xml",
            ImmutableMap.of(
                "NAME",
                "example.tld",
                "PERIOD",
                "5",
                "EX_DATE",
                "2005-04-03T22:00:00.0Z",
                "FEE",
                "49.50",
                "CURRENCY",
                "USD",
                "FEE_VERSION",
                "fee-0.12",
                "FEE_NS",
                "fee")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken1.createVKey()))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v06() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v11() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v12() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v06() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v11() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v12() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v06() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v11() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v12() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_06_MAP);
  }

  @Test
  void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_11_MAP);
  }

  @Test
  void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_12_MAP);
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_06_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_06_MAP);
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_11_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_11_MAP);
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_12_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_12_MAP);
  }

  @Test
  void testSuccess_anchorTenant_premiumDomain_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 100"))
            .build());
    persistDomain(NONPREMIUM, null);
    setRegistrarIdForFlow("NewRegistrar");
    ImmutableMap<String, String> customFeeMap = updateSubstitutions(FEE_06_MAP, "FEE", "55.00");
    setEppInput("domain_renew_fee.xml", customFeeMap);
    doSuccessfulTest(
        "domain_renew_response_fee.xml",
        5,
        "NewRegistrar",
        UserPrivileges.SUPERUSER,
        customFeeMap,
        Money.of(USD, 55),
        NONPREMIUM,
        null);
  }

  @Test
  void testSuccess_customLogicFee_v06() throws Exception {
    // The "costly-renew" domain has an additional RENEW fee of 100 from custom logic on top of the
    // normal $11 standard renew price for this TLD.
    ImmutableMap<String, String> customFeeMap =
        updateSubstitutions(
            FEE_06_MAP,
            "NAME",
            "costly-renew.tld",
            "PERIOD",
            "1",
            "EX_DATE",
            "2001-04-03T22:00:00.0Z",
            "FEE",
            "111.00");
    setEppInput("domain_renew_fee.xml", customFeeMap);
    persistDomain();
    doSuccessfulTest(
        "domain_renew_response_fee.xml",
        1,
        "TheRegistrar",
        UserPrivileges.NORMAL,
        customFeeMap,
        Money.of(USD, 111));
  }

  private void doSuccessfulTest(String responseFilename, int renewalYears) throws Exception {
    doSuccessfulTest(responseFilename, renewalYears, ImmutableMap.of());
  }

  private void doSuccessfulTest(
      String responseFilename, int renewalYears, Map<String, String> substitutions)
      throws Exception {
    doSuccessfulTest(
        responseFilename,
        renewalYears,
        "TheRegistrar",
        UserPrivileges.NORMAL,
        substitutions,
        Money.of(USD, 11).multipliedBy(renewalYears),
        DEFAULT,
        null);
  }

  private void doSuccessfulTest(
      String responseFilename,
      int renewalYears,
      String renewalClientId,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions,
      Money totalRenewCost)
      throws Exception {
    doSuccessfulTest(
        responseFilename,
        renewalYears,
        renewalClientId,
        userPrivileges,
        substitutions,
        totalRenewCost,
        DEFAULT,
        null);
  }

  private void doSuccessfulTest(
      String responseFilename,
      int renewalYears,
      String renewalClientId,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions,
      Money totalRenewCost,
      RenewalPriceBehavior renewalPriceBehavior,
      @Nullable Money renewalPrice)
      throws Exception {
    assertMutatingFlow(true);
    DateTime currentExpiration = reloadResourceByForeignKey().getRegistrationExpirationTime();
    DateTime newExpiration = currentExpiration.plusYears(renewalYears);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, loadFile(responseFilename, substitutions));
    Domain domain = reloadResourceByForeignKey();
    assertLastHistoryContainsResource(domain);
    DomainHistory historyEntryDomainRenew =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RENEW, DomainHistory.class);
    assertThat(loadByKey(domain.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(newExpiration);
    assertAboutDomains()
        .that(domain)
        .isActiveAt(clock.nowUtc())
        .and()
        .hasRegistrationExpirationTime(newExpiration)
        .and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_RENEW)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateRegistrarId(renewalClientId);
    assertAboutHistoryEntries().that(historyEntryDomainRenew).hasPeriodYears(renewalYears);
    BillingEvent renewBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.RENEW)
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId(renewalClientId)
            .setCost(totalRenewCost)
            .setPeriodYears(renewalYears)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc().plus(Tld.get("tld").getRenewGracePeriodLength()))
            .setDomainHistory(historyEntryDomainRenew)
            .build();
    assertBillingEvents(
        renewBillingEvent,
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setRenewalPriceBehavior(renewalPriceBehavior)
            .setRenewalPrice(renewalPrice)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(expirationTime)
            .setRecurrenceEndTime(clock.nowUtc())
            .setDomainHistory(
                getOnlyHistoryEntryOfType(
                    domain, HistoryEntry.Type.DOMAIN_CREATE, DomainHistory.class))
            .build(),
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setRenewalPriceBehavior(renewalPriceBehavior)
            .setRenewalPrice(renewalPrice)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntryDomainRenew)
            .build());
    // There should only be the new autorenew poll message, as the old one will have been deleted
    // since it had no messages left to deliver.
    assertPollMessages(
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntryDomainRenew)
            .build());
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                clock.nowUtc().plus(Tld.get("tld").getRenewGracePeriodLength()),
                renewalClientId,
                null),
            renewBillingEvent));
  }
}
