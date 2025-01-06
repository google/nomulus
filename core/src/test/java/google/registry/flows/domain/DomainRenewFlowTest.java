// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.BULK_PRICING;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertPollMessages;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getOnlyHistoryEntryOfType;
import static google.registry.testing.DatabaseHelper.loadByKey;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistDeletedDomain;
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
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.FlowUtils.NotLoggedInException;
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
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainRenewFlow.IncorrectCurrentExpirationDateException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.MissingRemoveBulkPricingTokenOnBulkPricingDomainException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.RemoveBulkPricingTokenOnNonBulkPricingDomainException;
import google.registry.flows.exceptions.ResourceStatusProhibitsOperationException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import java.util.Map;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainRenewFlow}. */
class DomainRenewFlowTest extends ResourceFlowTestCase<DomainRenewFlow, Domain> {

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

  @BeforeEach
  void initDomainTest() {
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

  @Test
  private void assertAllocationTokenWasNotRedeemed(String token) {
    AllocationToken reloadedToken =
        tm().transact(() -> tm().loadByKey(VKey.create(AllocationToken.class, token)));
    assertThat(reloadedToken.isRedeemed()).isFalse();
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    persistDomain();
    dryRunFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z")));
  }

  @Test
  void testSuccess() throws Exception {
    clock.advanceOneMilli();
    persistDomain();
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"));
  }

  @Test
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

  @Test
  void testSuccess_internalRegistration_standardDomain() throws Exception {
    persistDomain(SPECIFIED, Money.of(USD, 2));
    setRegistrarIdForFlow("NewRegistrar");
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        "NewRegistrar",
        UserPrivileges.SUPERUSER,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"),
        Money.of(USD, 10),
        SPECIFIED,
        Money.of(USD, 2));
  }

  @Test
  void testSuccess_internalRegiration_premiumDomain() throws Exception {
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
  void testSuccess_anchorTenant_standardDomain() throws Exception {
    persistDomain(NONPREMIUM, null);
    setRegistrarIdForFlow("NewRegistrar");
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        "NewRegistrar",
        UserPrivileges.SUPERUSER,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"),
        Money.of(USD, 55),
        NONPREMIUM,
        null);
  }

  @Test
  void testSuccess_anchorTenant_premiumDomain() throws Exception {
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
  void testFailure_fee_unknownCurrency() {
    setEppInput("domain_renew_fee.xml", updateSubstitutions(FEE_06_MAP, "CURRENCY", "BAD"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::persistDomain);
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
  void testSuccess_allocationToken() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("example.tld")
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    assertThat(DatabaseHelper.loadByEntity(allocationToken).getRedemptionHistoryId()).isPresent();
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(allocationToken.getToken());
    // Price is 50% off the first year only. Non-discounted price is $11.
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
  }

  @Test
  void testSuccess_allocationTokenMultiUse() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey()).isEqualTo("abc123");
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
    clock.advanceOneMilli();
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "other-example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "other-example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    billingEvent = Iterables.getLast(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("other-example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey()).isEqualTo("abc123");
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
  }

  @Test
  void testSuccess_allocationTokenMultiYearDiscount() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("example.tld")
                .setDiscountFraction(0.5)
                .setDiscountYears(10)
                .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    assertThat(DatabaseHelper.loadByEntity(allocationToken).getRedemptionHistoryId()).isPresent();
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(allocationToken.getToken());
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 11));
  }

  @Test
  void testFailure_invalidAllocationToken() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    EppException thrown = assertThrows(InvalidAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_allocationTokenIsForADifferentDomain() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("otherdomain.tld")
            .build());
    clock.advanceOneMilli();
    EppException thrown =
        assertThrows(AllocationTokenNotValidForDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
    assertAllocationTokenWasNotRedeemed("abc123");
  }

  @Test
  void testFailure_promotionNotActive() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
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
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotInPromotionException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testFailure_promoTokenNotValidForRegistrar() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedRegistrarIds(ImmutableSet.of("someClientId"))
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotValidForRegistrarException.class, this::runFlow))
        .marshalsToXml();
    assertAllocationTokenWasNotRedeemed("abc123");
  }

  @Test
  void testFailure_promoTokenNotValidForTld() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedTlds(ImmutableSet.of("example"))
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotValidForTldException.class, this::runFlow))
        .marshalsToXml();
    assertAllocationTokenWasNotRedeemed("abc123");
  }

  @Test
  void testFailure_alreadyRedemeedAllocationToken() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    Domain domain = persistActiveDomain("foo.tld");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 505L);
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryId(historyEntryId)
            .setDiscountFraction(0.5)
            .build());
    clock.advanceOneMilli();
    EppException thrown =
        assertThrows(AlreadyRedeemedAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_suspendedRegistrarCantRenewDomain() {
    doFailingTest_invalidRegistrarState(State.SUSPENDED);
  }

  @Test
  void testFailure_pendingRegistrarCantRenewDomain() {
    doFailingTest_invalidRegistrarState(State.PENDING);
  }

  @Test
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

  @Test
  void testSuccess_nonDefaultRenewGracePeriod() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setRenewGracePeriodLength(Duration.standardMinutes(9)).build());
    persistDomain();
    doSuccessfulTest(
        "domain_renew_response.xml",
        5,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2005-04-03T22:00:00.0Z"));
  }

  @Test
  void testSuccess_missingPeriod() throws Exception {
    setEppInput("domain_renew_missing_period.xml");
    persistDomain();
    doSuccessfulTest("domain_renew_response_missing_period.xml", 1);
  }

  @Test
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
            .setHistoryEntry(
                getOnlyHistoryEntryOfType(
                    reloadResourceByForeignKey(), HistoryEntry.Type.DOMAIN_CREATE))
            .build(),
        new PollMessage.Autorenew.Builder()
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(reloadResourceByForeignKey().getRegistrationExpirationTime())
            .setAutorenewEndTime(END_OF_TIME)
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntryDomainRenew)
            .build());
  }

  @Test
  void testSuccess_metaData_withReasonAndRequestedByRegistrar() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput(
        "domain_renew_metadata_with_reason_and_requestedByRegistrar.xml",
        ImmutableMap.of(
            "DOMAIN",
            "example.tld",
            "EXPDATE",
            "2000-04-03",
            "YEARS",
            "1",
            "REASON",
            "domain-renew-test",
            "REQUESTED",
            "false"));
    persistDomain();
    runFlow();
    Domain domain = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain)
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_RENEW);
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RENEW))
        .hasMetadataReason("domain-renew-test")
        .and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @Test
  void testSuccess_metaData_withRequestedByRegistrarOnly() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_renew_metadata_with_requestedByRegistrar_only.xml");

    persistDomain();
    runFlow();
    Domain domain1 = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain1)
        .hasOneHistoryEntryEachOfTypes(
            HistoryEntry.Type.DOMAIN_CREATE, HistoryEntry.Type.DOMAIN_RENEW);
    assertAboutHistoryEntries()
        .that(getOnlyHistoryEntryOfType(domain1, HistoryEntry.Type.DOMAIN_RENEW))
        .hasMetadataReason(null)
        .and()
        .hasMetadataRequestedByRegistrar(true);
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc().minusDays(1));
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_clientRenewProhibited() throws Exception {
    persistDomain(StatusValue.CLIENT_RENEW_PROHIBITED);
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("clientRenewProhibited");
  }

  @Test
  void testFailure_serverRenewProhibited() throws Exception {
    persistDomain(StatusValue.SERVER_RENEW_PROHIBITED);
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("serverRenewProhibited");
  }

  @Test
  void testFailure_pendingDelete() throws Exception {
    persistResource(
        DatabaseHelper.newDomain(getUniqueIdFromCommand())
            .asBuilder()
            .setRegistrationExpirationTime(expirationTime)
            .setDeletionTime(clock.nowUtc().plusDays(1))
            .addStatusValue(StatusValue.PENDING_DELETE)
            .build());
    ResourceStatusProhibitsOperationException thrown =
        assertThrows(ResourceStatusProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("pendingDelete");
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
  void testFailure_missingBillingAccountMap() throws Exception {
    persistDomain();
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

  @Test
  void testFailure_periodInMonths() throws Exception {
    setEppInput("domain_renew_months.xml");
    persistDomain();
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_max10Years() throws Exception {
    setEppInput("domain_renew_11_years.xml");
    persistDomain();
    EppException thrown = assertThrows(ExceedsMaxRegistrationYearsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
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

  @Test
  void testFailure_unauthorizedClient() throws Exception {
    setRegistrarIdForFlow("NewRegistrar");
    persistActiveDomain(getUniqueIdFromCommand());
    EppException thrown = assertThrows(ResourceNotOwnedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
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

  @Test
  void testFailure_notAuthorizedForTld() throws Exception {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    persistDomain();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
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

  @Test
  void testFailure_feeNotProvidedOnPremiumName() throws Exception {
    createTld("example");
    setEppInput("domain_renew_premium.xml");
    persistDomain();
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistDomain();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-renew");
    assertTldsFieldLogged("tld");
  }

  @Test
  void testIcannTransactionRecord_getsStored() throws Exception {
    persistDomain();
    // Test with a nonstandard Renew period to ensure the reporting time is correct regardless
    persistResource(
        Tld.get("tld").asBuilder().setRenewGracePeriodLength(Duration.standardMinutes(9)).build());
    runFlow();
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntry =
        (DomainHistory) getOnlyHistoryEntryOfType(domain, HistoryEntry.Type.DOMAIN_RENEW);
    assertThat(historyEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld",
                historyEntry.getModificationTime().plusMinutes(9),
                TransactionReportField.netRenewsFieldFromYears(5),
                1));
  }

  @Test
  void testFailsBulkPricingDomainInvalidAllocationToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .build());
    persistDomain();
    persistResource(
        reloadResourceByForeignKey().asBuilder().setCurrentBulkToken(token.createVKey()).build());

    persistResource(
        new AllocationToken.Builder()
            .setToken("token")
            .setTokenType(SINGLE_USE)
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .build());

    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "token"));

    EppException thrown =
        assertThrows(
            MissingRemoveBulkPricingTokenOnBulkPricingDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailsToRenewBulkPricingDomainNoRemoveBulkPricingToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .build());
    persistDomain();
    persistResource(
        reloadResourceByForeignKey().asBuilder().setCurrentBulkToken(token.createVKey()).build());

    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "5"));

    EppException thrown =
        assertThrows(
            MissingRemoveBulkPricingTokenOnBulkPricingDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailsToRenewNonBulkPricingDomainWithRemoveBulkPricingToken() throws Exception {
    persistDomain();

    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "__REMOVE_BULK_PRICING__"));

    EppException thrown =
        assertThrows(RemoveBulkPricingTokenOnNonBulkPricingDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccesfullyAppliesRemoveBulkPricingToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .build());
    persistDomain(SPECIFIED, Money.of(USD, 2));
    persistResource(
        reloadResourceByForeignKey().asBuilder().setCurrentBulkToken(token.createVKey()).build());
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "__REMOVE_BULK_PRICING__"));

    doSuccessfulTest(
        "domain_renew_response.xml",
        2,
        ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00Z"));

    // We still need to verify that the bulk token is removed as it's not being tested as a part of
    // doSuccessfulTest
    Domain domain = reloadResourceByForeignKey();
    assertThat(domain.getCurrentBulkToken()).isEmpty();
  }

  @Test
  void testDryRunRemoveBulkPricingToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .build());
    persistDomain(SPECIFIED, Money.of(USD, 2));
    persistResource(
        reloadResourceByForeignKey().asBuilder().setCurrentBulkToken(token.createVKey()).build());

    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "__REMOVE_BULK_PRICING__"));

    dryRunFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
  }

  @Test
  void testSuccess_usesDefaultToken() throws Exception {
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
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
    AllocationToken defaultToken2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.75)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.of(defaultToken1.createVKey(), defaultToken2.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(defaultToken1.getToken());
    // Price is 50% off the first year only. Non-discounted price is $11.
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
  }

  @Test
  void testSuccess_doesNotUseDefaultTokenWhenTokenPassedIn() throws Exception {
    setEppInput(
        "domain_renew_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2", "TOKEN", "abc123"));
    persistDomain();
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .build());
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.25)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken defaultToken2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.75)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.of(defaultToken1.createVKey(), defaultToken2.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(allocationToken.getToken());
    // Price is 50% off the first year only. Non-discounted price is $11.
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
  }

  @Test
  void testSuccess_noValidDefaultToken() throws Exception {
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedRegistrarIds(ImmutableSet.of("OtherRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken defaultToken2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.75)
                .setDiscountYears(1)
                .setAllowedRegistrarIds(ImmutableSet.of("OtherRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.of(defaultToken1.createVKey(), defaultToken2.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken()).isEmpty();
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 22));
  }

  @Test
  void testSuccess_doesNotApplyNonPremiumDefaultTokenToPremiumName() throws Exception {
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
                "0.6",
                "FEE_NS",
                "fee")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken()).isEmpty();
  }

  @Test
  void testSuccess_onlyUsesFirstValidToken() throws Exception {
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistDomain();
    AllocationToken defaultToken1 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("aaaaa")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.25)
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken defaultToken2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.5)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken defaultToken3 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("ccccc")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.75)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.of(
                    defaultToken1.createVKey(),
                    defaultToken2.createVKey(),
                    defaultToken3.createVKey()))
            .build());
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(defaultToken2.getToken());
    // Price is 50% off the first year only. Non-discounted price is $11.
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 16.5));
  }

  @Test
  void testSuccess_registryHasDeletedDefaultToken() throws Exception {
    setEppInput("domain_renew.xml", ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
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
    AllocationToken defaultToken2 =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setDiscountFraction(0.75)
                .setDiscountYears(1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.of(defaultToken1.createVKey(), defaultToken2.createVKey()))
            .build());
    DatabaseHelper.deleteResource(defaultToken1);
    runFlowAssertResponse(
        loadFile(
            "domain_renew_response.xml",
            ImmutableMap.of("DOMAIN", "example.tld", "EXDATE", "2002-04-03T22:00:00.0Z")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey())
        .isEqualTo(defaultToken2.getToken());
    // Price is 75% off the first year only. Non-discounted price is $11.
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 13.75));
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
                "0.6",
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
                "0.11",
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
                "0.12",
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
}
