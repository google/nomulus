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

import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.testing.DatabaseHelper;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainRestoreRequestFlow} that use the old fee extensions (0.6, 0.11, 0.12). */
public class DomainRestoreRequestFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainRestoreRequestFlow> {

  private static final ImmutableMap<String, String> FEE_06_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.6", "FEE_NS", "fee", "CURRENCY", "USD");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.11", "FEE_NS", "fee11", "CURRENCY", "USD");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE_NS", "fee12", "CURRENCY", "USD");

  @BeforeEach
  void beforeEachDomainRestoreRequestFlowOldFeeExtensionsTest() {
    createTld("tld");
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "123", EUR, "567"))
            .build());
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testFailure_wrongFeeAmount_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    persistResource(Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    persistResource(Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    persistResource(Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee_bad_scale.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee_bad_scale.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee_bad_scale.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee_applied.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee_applied.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee_applied.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee_defaults.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee_defaults.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_11_MAP));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee_defaults.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_12_MAP));
  }

  @Test
  void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_fee_v06_noRenewal() throws Exception {
    setEppInput("domain_update_restore_request_fee_no_renewal.xml", FEE_06_MAP);
    persistPendingDeleteDomain(clock.nowUtc().plusMonths(6));
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee_no_renewal.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_11_MAP));
  }

  @Test
  void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_fee.xml", FEE_12_MAP));
  }

  @Test
  void testFailure_refundableFee_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee_refundable.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee_refundable.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee_refundable.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee_grace_period.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee_grace_period.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee_grace_period.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void runWrongCurrencyTest(Map<String, String> substitutions) throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", substitutions);
    persistPendingDeleteDomain();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 13)))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
            .setRegistryLockOrUnlockBillingCost(Money.of(EUR, 0))
            .build());
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v06() throws Exception {
    runWrongCurrencyTest(FEE_06_MAP);
  }

  @Test
  void testFailure_wrongCurrency_v11() throws Exception {
    runWrongCurrencyTest(FEE_11_MAP);
  }

  @Test
  void testFailure_wrongCurrency_v12() throws Exception {
    runWrongCurrencyTest(FEE_12_MAP);
  }

  @Test
  void testFailure_premiumBlocked_v12() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown = assertThrows(PremiumNameBlockedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_premiumNotBlocked_andNoRenewal_v12() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium_no_renewal.xml", FEE_12_MAP);
    persistPendingDeleteDomain(clock.nowUtc().plusYears(2));
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee_no_renewal.xml", FEE_12_MAP));
  }

  @Test
  void testFailure_fee_unknownCurrency_v12() {
    ImmutableMap<String, String> substitutions =
        ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE_NS", "fee12", "CURRENCY", "BAD");
    setEppInput("domain_update_restore_request_fee.xml", substitutions);
    EppException thrown =
        assertThrows(UnknownCurrencyEppException.class, this::persistPendingDeleteDomain);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private Domain persistPendingDeleteDomain() throws Exception {
    // The domain is now past what had been its expiration date at the time of deletion.
    return persistPendingDeleteDomain(clock.nowUtc().minusDays(5));
  }

  private Domain persistPendingDeleteDomain(DateTime expirationTime) throws Exception {
    Domain domain = persistResource(DatabaseHelper.newDomain(getUniqueIdFromCommand()));
    HistoryEntry historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setType(HistoryEntry.Type.DOMAIN_DELETE)
                .setModificationTime(clock.nowUtc())
                .setRegistrarId(domain.getCurrentSponsorRegistrarId())
                .setDomain(domain)
                .build());
    domain =
        persistResource(
            domain
                .asBuilder()
                .setRegistrationExpirationTime(expirationTime)
                .setDeletionTime(clock.nowUtc().plusDays(35))
                .addGracePeriod(
                    GracePeriod.create(
                        GracePeriodStatus.REDEMPTION,
                        domain.getRepoId(),
                        clock.nowUtc().plusDays(1),
                        "TheRegistrar",
                        null))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .setDeletePollMessage(
                    persistResource(
                            new PollMessage.OneTime.Builder()
                                .setRegistrarId("TheRegistrar")
                                .setEventTime(clock.nowUtc().plusDays(5))
                                .setHistoryEntry(historyEntry)
                                .build())
                        .createVKey())
                .build());
    clock.advanceOneMilli();
    return domain;
  }
}
