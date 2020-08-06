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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.assertPollMessages;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatastoreHelper.getPollMessages;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.newDomainBase;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainRestoreRequestFlow.DomainNotEligibleForRestoreException;
import google.registry.flows.domain.DomainRestoreRequestFlow.RestoreCommandIncludesChangesException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainRestoreRequestFlow}. */
class DomainRestoreRequestFlowTest
    extends ResourceFlowTestCase<DomainRestoreRequestFlow, DomainBase> {

  private static final ImmutableMap<String, String> FEE_06_MAP =
      ImmutableMap.of("FEE_VERSION", "0.6", "FEE_NS", "fee", "CURRENCY", "USD");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      ImmutableMap.of("FEE_VERSION", "0.11", "FEE_NS", "fee11", "CURRENCY", "USD");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      ImmutableMap.of("FEE_VERSION", "0.12", "FEE_NS", "fee12", "CURRENCY", "USD");

  @BeforeEach
  void initDomainTest() {
    createTld("tld");
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  void persistPendingDeleteDomain() throws Exception {
    // The domain is now past what had been its expiration date at the time of deletion.
    persistPendingDeleteDomain(clock.nowUtc().minusDays(5));
  }

  void persistPendingDeleteDomain(DateTime expirationTime) throws Exception {
    DomainBase domain = newDomainBase(getUniqueIdFromCommand());
    HistoryEntry historyEntry =
        persistResource(
            new HistoryEntry.Builder()
                .setType(HistoryEntry.Type.DOMAIN_DELETE)
                .setParent(domain)
                .build());
    persistResource(
        domain
            .asBuilder()
            .setRegistrationExpirationTime(expirationTime)
            .setDeletionTime(clock.nowUtc().plusDays(35))
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.REDEMPTION, clock.nowUtc().plusDays(1), "foo", null))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .setDeletePollMessage(
                persistResource(
                        new PollMessage.OneTime.Builder()
                            .setClientId("TheRegistrar")
                            .setEventTime(clock.nowUtc().plusDays(5))
                            .setParent(historyEntry)
                            .build())
                    .createVKey())
            .build());
    clock.advanceOneMilli();
  }

  @Test
  void testDryRun() throws Exception {
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistPendingDeleteDomain();
    dryRunFlowAssertResponse(loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess_expiryStillInFuture_notExtended() throws Exception {
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime expirationTime = clock.nowUtc().plusYears(5).plusDays(45);
    persistPendingDeleteDomain(expirationTime);
    assertTransactionalFlow(true);
    // Double check that we see a poll message in the future for when the delete happens.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    DomainBase domain = reloadResourceByForeignKey();
    HistoryEntry historyEntryDomainRestore =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE);
    assertThat(tm().load(domain.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(expirationTime);
    assertAboutDomains()
        .that(domain)
        // New expiration time should be the same as from before the deletion.
        .hasRegistrationExpirationTime(expirationTime)
        .and()
        .doesNotHaveStatusValue(StatusValue.PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME)
        .and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_DELETE, HistoryEntry.Type.DOMAIN_RESTORE)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("TheRegistrar");
    assertThat(domain.getGracePeriods()).isEmpty();
    assertDnsTasksEnqueued("example.tld");
    // The poll message for the delete should now be gone. The only poll message should be the new
    // autorenew poll message.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainRestore)
            .build());
    // There should be a onetime for the restore and a new recurring billing event, but no renew
    // onetime.
    assertBillingEvents(
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(expirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntryDomainRestore)
            .build(),
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.RESTORE)
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setCost(Money.of(USD, 17))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setParent(historyEntryDomainRestore)
            .build());
  }

  @Test
  void testSuccess_expiryInPast_extendedByOneYear() throws Exception {
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime expirationTime = clock.nowUtc().minusDays(20);
    DateTime newExpirationTime = expirationTime.plusYears(1);
    persistPendingDeleteDomain(expirationTime);
    assertTransactionalFlow(true);
    // Double check that we see a poll message in the future for when the delete happens.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    DomainBase domain = reloadResourceByForeignKey();
    HistoryEntry historyEntryDomainRestore =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE);
    assertThat(tm().load(domain.getAutorenewBillingEvent()).getEventTime())
        .isEqualTo(newExpirationTime);
    assertAboutDomains()
        .that(domain)
        // New expiration time should be exactly a year from now.
        .hasRegistrationExpirationTime(newExpirationTime)
        .and()
        .doesNotHaveStatusValue(StatusValue.PENDING_DELETE)
        .and()
        .hasDeletionTime(END_OF_TIME)
        .and()
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_DELETE, HistoryEntry.Type.DOMAIN_RESTORE)
        .and()
        .hasLastEppUpdateTime(clock.nowUtc())
        .and()
        .hasLastEppUpdateClientId("TheRegistrar");
    assertThat(domain.getGracePeriods()).isEmpty();
    assertDnsTasksEnqueued("example.tld");
    // The poll message for the delete should now be gone. The only poll message should be the new
    // autorenew poll message.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainRestore)
            .build());
    // There should be a bill for the restore and an explicit renew, along with a new recurring
    // autorenew event.
    assertBillingEvents(
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setEventTime(newExpirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntryDomainRestore)
            .build(),
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.RESTORE)
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setCost(Money.of(USD, 17))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setParent(historyEntryDomainRestore)
            .build(),
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.RENEW)
            .setTargetId("example.tld")
            .setClientId("TheRegistrar")
            .setCost(Money.of(USD, 11))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setParent(historyEntryDomainRestore)
            .build());
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
  void testFailure_fee_unknownCurrency() {
    ImmutableMap<String, String> substitutions =
        ImmutableMap.of("FEE_VERSION", "0.12", "FEE_NS", "fee12", "CURRENCY", "BAD");
    setEppInput("domain_update_restore_request_fee.xml", substitutions);
    EppException thrown =
        assertThrows(UnknownCurrencyEppException.class, this::persistPendingDeleteDomain);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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
  void testSuccess_premiumNotBlocked() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml");
    persistPendingDeleteDomain();
    runFlowAssertResponse(loadFile("domain_update_restore_request_response_premium.xml"));
  }

  @Test
  void testSuccess_premiumNotBlocked_andNoRenewal() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium_no_renewal.xml");
    persistPendingDeleteDomain(clock.nowUtc().plusYears(2));
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee_no_renewal.xml", FEE_12_MAP));
  }

  @Test
  void testSuccess_superuserOverridesReservedList() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(persistReservedList("tld-reserved", "example,FULLY_BLOCKED"))
            .build());
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testSuccess_superuserOverridesPremiumNameBlock() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml");
    persistPendingDeleteDomain();
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile("domain_update_restore_request_response_premium.xml"));
  }

  @Test
  void testFailure_doesNotExist() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_suspendedRegistrarCantRestoreDomain() {
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.SUSPENDED)
            .build());
    EppException thrown =
        assertThrows(RegistrarMustBeActiveForThisOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_pendingRegistrarCantRestoreDomain() {
    persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.PENDING)
            .build());
    EppException thrown =
        assertThrows(RegistrarMustBeActiveForThisOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v06() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_06_MAP);
    persistPendingDeleteDomain();
    persistResource(
        Registry.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v11() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_11_MAP);
    persistPendingDeleteDomain();
    persistResource(
        Registry.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v12() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_12_MAP);
    persistPendingDeleteDomain();
    persistResource(
        Registry.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void runWrongCurrencyTest(Map<String, String> substitutions) throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", substitutions);
    persistPendingDeleteDomain();
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
            .setRestoreBillingCost(Money.of(EUR, 11))
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
            .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
            .setServerStatusChangeBillingCost(Money.of(EUR, 19))
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
  void testFailure_notInRedemptionPeriod() throws Exception {
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(4))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    EppException thrown = assertThrows(DomainNotEligibleForRestoreException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_notDeleted() throws Exception {
    persistActiveDomain(getUniqueIdFromCommand());
    EppException thrown = assertThrows(DomainNotEligibleForRestoreException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_fullyDeleted() throws Exception {
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    EppException thrown = assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_withChange() throws Exception {
    persistPendingDeleteDomain();
    setEppInput("domain_update_restore_request_with_change.xml");
    EppException thrown = assertThrows(RestoreCommandIncludesChangesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_withAdd() throws Exception {
    persistPendingDeleteDomain();
    setEppInput("domain_update_restore_request_with_add.xml");
    EppException thrown = assertThrows(RestoreCommandIncludesChangesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_withRemove() throws Exception {
    persistPendingDeleteDomain();
    setEppInput("domain_update_restore_request_with_remove.xml");
    EppException thrown = assertThrows(RestoreCommandIncludesChangesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_withSecDnsExtension() throws Exception {
    persistPendingDeleteDomain();
    setEppInput("domain_update_restore_request_with_secdns.xml");
    EppException thrown = assertThrows(UnimplementedExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setClientId("NewRegistrar");
    persistPendingDeleteDomain();
    EppException thrown =
        assertThrows(
            ResourceNotOwnedException.class,
            () -> runFlowAssertResponse(loadFile("generic_success_response.xml")));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_notAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_premiumBlocked() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml");
    persistPendingDeleteDomain();
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown = assertThrows(PremiumNameBlockedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_reservedBlocked() throws Exception {
    createTld("tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(persistReservedList("tld-reserved", "example,FULLY_BLOCKED"))
            .build());
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(DomainReservedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_premiumNotAcked() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "rich.example"));
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistPendingDeleteDomain();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-rgp-restore-request");
    assertTldsFieldLogged("tld");
  }

  @Test
  void testIcannTransactionReportField_getsStored() throws Exception {
    persistPendingDeleteDomain();
    runFlow();
    DomainBase domain = reloadResourceByForeignKey();
    HistoryEntry historyEntryDomainRestore =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE);
    assertThat(historyEntryDomainRestore.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld",
                historyEntryDomainRestore.getModificationTime(),
                TransactionReportField.RESTORED_DOMAINS,
                1));
  }

  @Test
  void testFailure_restoreReportsAreNotSupported() {
    setEppInput("domain_update_restore_report.xml");
    // This exception is referred to by its fully qualified path (rather than being imported) so
    // that it is not included in the list of exceptions thrown by DomainRestoreRequestFlow, as this
    // test EPP won't trigger the request flow at all.
    EppException thrown = assertThrows(
        google.registry.flows.EppException.UnimplementedCommandException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("domain restore reports are not supported");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
