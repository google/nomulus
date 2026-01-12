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
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.getPollMessages;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainRestoreRequestFlow.DomainNotEligibleForRestoreException;
import google.registry.flows.domain.DomainRestoreRequestFlow.RestoreCommandIncludesChangesException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import java.util.Map;
import java.util.Optional;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainRestoreRequestFlow}. */
class DomainRestoreRequestFlowTest extends ResourceFlowTestCase<DomainRestoreRequestFlow, Domain> {

  private static final ImmutableMap<String, String> FEE_STD_1_0_MAP =
      ImmutableMap.of("FEE_VERSION", "epp:fee-1.0", "FEE_NS", "fee1_00", "CURRENCY", "USD");

  @BeforeEach
  void initDomainTest() {
    createTld("tld");
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "123", EUR, "567"))
            .build());
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  Domain persistPendingDeleteDomain() throws Exception {
    // The domain is now past what had been its expiration date at the time of deletion.
    return persistPendingDeleteDomain(clock.nowUtc().minusDays(5));
  }

  Domain persistPendingDeleteDomain(DateTime expirationTime) throws Exception {
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

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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
    assertMutatingFlow(true);
    // Double check that we see a poll message in the future for when the delete happens.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntryDomainRestore =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE, DomainHistory.class);
    assertLastHistoryContainsResource(domain);
    assertThat(loadByKey(domain.getAutorenewBillingEvent()).getEventTime())
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
        .hasLastEppUpdateRegistrarId("TheRegistrar");
    assertThat(domain.getGracePeriods()).isEmpty();
    assertDomainDnsRequests("example.tld");
    // The poll message for the delete should now be gone. The only poll message should be the new
    // autorenew poll message.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntryDomainRestore)
            .build());
    // There should be a onetime for the restore and a new recurrence, but no renew onetime.
    assertBillingEvents(
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(expirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntryDomainRestore)
            .build(),
        new BillingEvent.Builder()
            .setReason(Reason.RESTORE)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(USD, 17))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setDomainHistory(historyEntryDomainRestore)
            .build());
  }

  @Test
  void testSuccess_expiryInPast_extendedByOneYear() throws Exception {
    setEppInput("domain_update_restore_request.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    DateTime expirationTime = clock.nowUtc().minusDays(20);
    DateTime newExpirationTime = expirationTime.plusYears(1);
    persistPendingDeleteDomain(expirationTime);
    assertMutatingFlow(true);
    // Double check that we see a poll message in the future for when the delete happens.
    assertThat(getPollMessages("TheRegistrar", clock.nowUtc().plusMonths(1))).hasSize(1);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntryDomainRestore =
        getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE, DomainHistory.class);
    assertLastHistoryContainsResource(domain);
    assertThat(loadByKey(domain.getAutorenewBillingEvent()).getEventTime())
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
        .hasLastEppUpdateRegistrarId("TheRegistrar");
    assertThat(domain.getGracePeriods()).isEmpty();
    assertDomainDnsRequests("example.tld");
    // The poll message for the delete should now be gone. The only poll message should be the new
    // autorenew poll message.
    assertPollMessages(
        "TheRegistrar",
        new PollMessage.Autorenew.Builder()
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntryDomainRestore)
            .build());
    // There should be a bill for the restore and an explicit renew, along with a new recurrence
    // autorenew event.
    assertBillingEvents(
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setEventTime(newExpirationTime)
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntryDomainRestore)
            .build(),
        new BillingEvent.Builder()
            .setReason(Reason.RESTORE)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(USD, 17))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setDomainHistory(historyEntryDomainRestore)
            .build(),
        new BillingEvent.Builder()
            .setReason(Reason.RENEW)
            .setTargetId("example.tld")
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(USD, 11))
            .setPeriodYears(1)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc())
            .setDomainHistory(historyEntryDomainRestore)
            .build());
  }

  @Test
  void testSuccess_autorenewEndTimeIsCleared() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setAutorenewEndTime(Optional.of(clock.nowUtc().plusYears(2)))
            .build());
    assertThat(reloadResourceByForeignKey().getAutorenewEndTime()).isPresent();
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee.xml", FEE_STD_1_0_MAP));
    assertThat(reloadResourceByForeignKey().getAutorenewEndTime()).isEmpty();
  }

  @Test
  void testSuccess_fee_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee.xml", FEE_STD_1_0_MAP));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee_defaults.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee.xml", FEE_STD_1_0_MAP));
  }

  @Test
  void testFailure_fee_unknownCurrency_std_v1() {
    ImmutableMap<String, String> substitutions =
        ImmutableMap.of("FEE_VERSION", "epp:fee-1.0", "FEE_NS", "fee1_00", "CURRENCY", "BAD");
    setEppInput("domain_update_restore_request_fee.xml", substitutions);
    EppException thrown =
        assertThrows(UnknownCurrencyEppException.class, this::persistPendingDeleteDomain);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee_refundable.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee_grace_period.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee_applied.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_premiumNotBlocked() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_premium.xml", FEE_STD_1_0_MAP));
  }

  @Test
  void testSuccess_premiumNotBlocked_andNoRenewal_std_v1() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium_no_renewal.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain(clock.nowUtc().plusYears(2));
    runFlowAssertResponse(
        loadFile("domain_update_restore_request_response_fee_no_renewal.xml", FEE_STD_1_0_MAP));
  }

  @Test
  void testSuccess_superuserOverridesReservedList() throws Exception {
    persistResource(
        Tld.get("tld")
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
    setEppInput("domain_update_restore_request_premium.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile("domain_update_restore_request_response_premium.xml", FEE_STD_1_0_MAP));
  }

  @Test
  void testSuccess_worksWithoutPollMessage() throws Exception {
    Domain domain = persistPendingDeleteDomain();
    VKey<PollMessage.OneTime> deletePollMessage = domain.getDeletePollMessage();
    persistResource(domain.asBuilder().setDeletePollMessage(null).build());
    DatabaseHelper.deleteByKey(deletePollMessage);
    runFlowAssertResponse(loadFile("generic_success_response.xml"));
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
        Registrar.loadByRegistrarId("TheRegistrar")
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
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(State.PENDING)
            .build());
    EppException thrown =
        assertThrows(RegistrarMustBeActiveForThisOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    persistResource(Tld.get("tld").asBuilder().setRestoreBillingCost(Money.of(USD, 100)).build());
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
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
  void testFailure_wrongCurrency_std_v1() throws Exception {
    runWrongCurrencyTest(FEE_STD_1_0_MAP);
  }

  @Test
  void testFailure_feeGivenInWrongScale_std_v1() throws Exception {
    setEppInput("domain_update_restore_request_fee_bad_scale.xml", FEE_STD_1_0_MAP);
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_notInRedemptionPeriod() throws Exception {
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
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
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistPendingDeleteDomain();
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
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
  void testFailure_missingBillingAccount() throws Exception {
    persistPendingDeleteDomain();
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
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistPendingDeleteDomain();
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("generic_success_response.xml"));
  }

  @Test
  void testFailure_premiumBlocked_std_v1() throws Exception {
    createTld("example");
    setEppInput("domain_update_restore_request_premium.xml", FEE_STD_1_0_MAP);
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
        Tld.get("tld")
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
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntryDomainRestore =
        (DomainHistory) getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RESTORE);
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
