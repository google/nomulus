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
import static google.registry.flows.domain.DomainTransferFlowTestCase.persistWithPendingTransfer;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.newDomainBase;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DatabaseHelper.persistResources;
import static google.registry.testing.DomainBaseSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.HistoryEntrySubject.assertAboutHistoryEntries;
import static google.registry.testing.TestDataHelper.updateSubstitutions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.ExceedsMaxRegistrationYearsException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainRenewFlow.IncorrectCurrentExpirationDateException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainBase;
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
import google.registry.model.tld.Registry;
import google.registry.testing.DualDatabaseTest;
import google.registry.testing.ReplayExtension;
import google.registry.testing.SetClockExtension;
import google.registry.testing.TestOfyAndSql;
import java.util.Map;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link DomainRenewFlow}. */
@DualDatabaseTest
class DomainRenewFlowTest extends ResourceFlowTestCase<DomainRenewFlow, DomainBase> {

  private static final ImmutableMap<String, String> FEE_BASE_MAP =
      ImmutableMap.of(
          "NAME", "example.tld",
          "PERIOD", "5",
          "EX_DATE", "2005-04-03T22:00:00.0Z",
          "FEE", "55.00",
          "CURRENCY", "USD");

  private static final ImmutableMap<String, String> FEE_06_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "0.6", "FEE_NS", "fee");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "0.11", "FEE_NS", "fee11");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      updateSubstitutions(FEE_BASE_MAP, "FEE_VERSION", "0.12", "FEE_NS", "fee12");

  private final DateTime expirationTime = DateTime.parse("2000-04-03T22:00:00.0Z");

  @Order(value = Order.DEFAULT - 3)
  @RegisterExtension
  final SetClockExtension setClockExtension =
      new SetClockExtension(clock, expirationTime.minusMillis(20));

  @Order(value = Order.DEFAULT - 2)
  @RegisterExtension
  final ReplayExtension replayExtension = ReplayExtension.createWithDoubleReplay(clock);

  @BeforeEach
  void initDomainTest() {
    createTld("tld");
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "5"));
  }

  private void persistDomain(StatusValue... statusValues) throws Exception {
    DomainBase domain = newDomainBase(getUniqueIdFromCommand());
    tm().transact(
            () -> {
              try {
                DomainHistory historyEntryDomainCreate =
                    new DomainHistory.Builder()
                        .setDomain(domain)
                        .setType(HistoryEntry.Type.DOMAIN_CREATE)
                        .setModificationTime(clock.nowUtc())
                        .setRegistrarId(domain.getCreationRegistrarId())
                        .build();
                BillingEvent.Recurring autorenewEvent =
                    new BillingEvent.Recurring.Builder()
                        .setReason(Reason.RENEW)
                        .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                        .setTargetId(getUniqueIdFromCommand())
                        .setRegistrarId("TheRegistrar")
                        .setEventTime(expirationTime)
                        .setRecurrenceEndTime(END_OF_TIME)
                        .setParent(historyEntryDomainCreate)
                        .build();
                PollMessage.Autorenew autorenewPollMessage =
                    new PollMessage.Autorenew.Builder()
                        .setTargetId(getUniqueIdFromCommand())
                        .setRegistrarId("TheRegistrar")
                        .setEventTime(expirationTime)
                        .setAutorenewEndTime(END_OF_TIME)
                        .setMsg("Domain was auto-renewed.")
                        .setParent(historyEntryDomainCreate)
                        .build();
                DomainBase newDomain =
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
            });
    clock.advanceOneMilli();
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
        Money.of(USD, 11).multipliedBy(renewalYears));
  }

  private void doSuccessfulTest(
      String responseFilename,
      int renewalYears,
      String renewalClientId,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions,
      Money totalRenewCost)
      throws Exception {
    assertTransactionalFlow(true);
    DateTime currentExpiration = reloadResourceByForeignKey().getRegistrationExpirationTime();
    DateTime newExpiration = currentExpiration.plusYears(renewalYears);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, loadFile(responseFilename, substitutions));
    DomainBase domain = reloadResourceByForeignKey();
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
        .hasLastEppUpdateClientId(renewalClientId);
    assertAboutHistoryEntries().that(historyEntryDomainRenew).hasPeriodYears(renewalYears);
    BillingEvent.OneTime renewBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.RENEW)
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId(renewalClientId)
            .setCost(totalRenewCost)
            .setPeriodYears(renewalYears)
            .setEventTime(clock.nowUtc())
            .setBillingTime(clock.nowUtc().plus(Registry.get("tld").getRenewGracePeriodLength()))
            .setParent(historyEntryDomainRenew)
            .build();
    assertBillingEvents(
        renewBillingEvent,
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(expirationTime)
            .setRecurrenceEndTime(clock.nowUtc())
            .setParent(
                getOnlyHistoryEntryOfType(
                    domain, HistoryEntry.Type.DOMAIN_CREATE, DomainHistory.class))
            .build(),
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntryDomainRenew)
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
            .setParent(historyEntryDomainRenew)
            .build());
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.RENEW,
                domain.getRepoId(),
                clock.nowUtc().plus(Registry.get("tld").getRenewGracePeriodLength()),
                renewalClientId,
                null),
            renewBillingEvent));
  }

  @TestOfyAndSql
  void testDryRun() throws Exception {
    persistDomain();
    dryRunFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z")));
  }

  @TestOfyAndSql
  void testSuccess() throws Exception {
    clock.advanceOneMilli();
    persistDomain();
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"));
  }

  @TestOfyAndSql
  void testSuccess_recurringClientIdIsSame_whenSuperuserOverridesRenewal() throws Exception {
    persistDomain();
    setRegistrarIdForFlow("NewRegistrar");
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        "NewRegistrar",
        UserPrivileges.SUPERUSER,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"),
        Money.of(USD, 55));
  }

  @TestOfyAndSql
  void testSuccess_customLogicFee() throws Exception {
    // The "costly-renew" domain has an additional RENEW fee of 100 from custom logic on top of the
    // normal $11 standard renew price for this TLD.
    ImmutableMap<String, String> customFeeMap =
        updateSubstitutions(
            FEE_06_MAP,
            "NAME", "costly-renew.tld",
            "PERIOD", "1",
            "EX_DATE", "2001-04-03T22:00:00.0Z",
            "FEE", "111.00");
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

  @TestOfyAndSql
  void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_06_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_11_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_12_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_06_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_06_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_11_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_11_MAP);
  }

  @TestOfyAndSql
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_renew_fee_defaults.xml", FEE_12_MAP);
    persistDomain();
    doSuccessfulTest("domain_renew_response_fee.xml", 5, FEE_12_MAP);
  }

  @TestOfyAndSql
  void testFailure_fee_unknownCurrency() {
    setEppInput("domain_renew_fee.xml", updateSubstitutions(FEE_06_MAP, "CURRENCY", "BAD"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::persistDomain);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v06() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v11() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_refundableFee_v12() throws Exception {
    setEppInput("domain_renew_fee_refundable.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v06() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v11() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_gracePeriodFee_v12() throws Exception {
    setEppInput("domain_renew_fee_grace_period.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v06() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v11() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_appliedFee_v12() throws Exception {
    setEppInput("domain_renew_fee_applied.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_suspendedRegistrarCantRenewDomain() {
    doFailingTest_invalidRegistrarState(State.SUSPENDED);
  }

  @TestOfyAndSql
  void testFailure_pendingRegistrarCantRenewDomain() {
    doFailingTest_invalidRegistrarState(State.PENDING);
  }

  @TestOfyAndSql
  void testFailure_disabledRegistrarCantRenewDomain() {
    doFailingTest_invalidRegistrarState(State.DISABLED);
  }

  private void doFailingTest_invalidRegistrarState(State registrarState) {
    persistResource(
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setState(registrarState)
            .build());
    EppException thrown =
        assertThrows(RegistrarMustBeActiveForThisOperationException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_nonDefaultRenewGracePeriod() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewGracePeriodLength(Duration.standardMinutes(9))
            .build());
    persistDomain();
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"));
  }

  @TestOfyAndSql
  void testSuccess_missingPeriod() throws Exception {
    setEppInput("domain_renew_missing_period.xml");
    persistDomain();
    doSuccessfulTest("domain_renew_response_missing_period.xml", 1);
  }

  @TestOfyAndSql
  void testSuccess_autorenewPollMessageIsNotDeleted() throws Exception {
    persistDomain();
    // Modify the autorenew poll message so that it has an undelivered message in the past.
    persistResource(
        loadByKey(reloadResourceByForeignKey().getAutorenewPollMessage())
            .asBuilder()
            .setEventTime(expirationTime.minusYears(1))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z")));
    HistoryEntry historyEntryDomainRenew =
        getOnlyHistoryEntryOfType(reloadResourceByForeignKey(), HistoryEntry.Type.DOMAIN_RENEW);
    assertPollMessages(
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(expirationTime.minusYears(1))
            .setAutorenewEndTime(clock.nowUtc())
            .setMsg("Domain was auto-renewed.")
            .setParent(
                getOnlyHistoryEntryOfType(
                    reloadResourceByForeignKey(), HistoryEntry.Type.DOMAIN_CREATE))
            .build(),
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(reloadResourceByForeignKey().getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setParent(historyEntryDomainRenew)
            .build());
  }

  @TestOfyAndSql
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @TestOfyAndSql
  void testFailure_clientRenewProhibited() throws Exception {
    persistDomain(StatusValue.CLIENT_RENEW_PROHIBITED);
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("clientRenewProhibited");
  }

  @TestOfyAndSql
  void testFailure_serverRenewProhibited() throws Exception {
    persistDomain(StatusValue.SERVER_RENEW_PROHIBITED);
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverRenewProhibited");
  }

  @TestOfyAndSql
  void testFailure_pendingDelete() throws Exception {
    persistResource(
        newDomainBase(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrationExpirationTime(expirationTime)
            .setDeletionTime(clock.nowUtc().plusDays(1))
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongFeeAmount_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistDomain();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_wrongCurrency_v06() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_06_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
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

  @TestOfyAndSql
  void testFailure_wrongCurrency_v11() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_11_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
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

  @TestOfyAndSql
  void testFailure_wrongCurrency_v12() throws Exception {
    setEppInput("domain_renew_fee.xml", FEE_12_MAP);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCurrency(EUR)
            .setCreateBillingCost(Money.of(EUR, 13))
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

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v06() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_06_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v11() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_11_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_feeGivenInWrongScale_v12() throws Exception {
    setEppInput("domain_renew_fee_bad_scale.xml", FEE_12_MAP);
    persistDomain();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_pendingTransfer() throws Exception {
    persistDomain();
    persistWithPendingTransfer(
        reloadResourceByForeignKey()
            .asBuilder()
            .setRegistrationExpirationTime(DateTime.parse("2001-09-08T22:00:00.0Z"))
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingTransfer");
  }

  @TestOfyAndSql
  void testFailure_periodInMonths() throws Exception {
    setEppInput("domain_renew_months.xml");
    persistDomain();
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_max10Years() throws Exception {
    setEppInput("domain_renew_11_years.xml");
    persistDomain();
    EppException thrown = assertThrows(ExceedsMaxRegistrationYearsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_curExpDateMustMatch() throws Exception {
    persistDomain();
    // Note expiration time is off by one day.
    persistResource(
        reloadResourceByForeignKey()
            .asBuilder()
            .setRegistrationExpirationTime(DateTime.parse("2000-04-04T22:00:00.0Z"))
            .build());
    EppException thrown =
        assertThrows(IncorrectCurrentExpirationDateException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testFailure_unauthorizedClient() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    persistActiveDomain(getUniqueIdFromCommand());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserUnauthorizedClient() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z")));
  }

  @TestOfyAndSql
  void testFailure_notAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistDomain();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistDomain();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z")));
  }

  @TestOfyAndSql
  void testFailure_feeNotProvidedOnPremiumName() throws Exception {
    createTld("example");
    setEppInput("domain_renew_premium.xml");
    persistDomain();
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @TestOfyAndSql
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistDomain();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-renew");
    assertTldsFieldLogged("tld");
  }

  @TestOfyAndSql
  void testIcannTransactionRecord_getsStored() throws Exception {
    persistDomain();
    // Test with a nonstandard Renew period to ensure the reporting time is correct regardless
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setRenewGracePeriodLength(Duration.standardMinutes(9))
            .build());
    runFlow();
    DomainBase domain = reloadResourceByForeignKey();
    HistoryEntry historyEntry = getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RENEW);
    assertThat(historyEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld",
                historyEntry.getModificationTime().plusMinutes(9),
                TransactionReportField.netRenewsFieldFromYears(5),
                1));
  }
}
