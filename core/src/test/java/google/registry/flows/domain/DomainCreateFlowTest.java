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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.bsa.persistence.BsaTestingUtils.persistBsaLabel;
import static google.registry.flows.FlowTestCase.UserPrivileges.SUPERUSER;
import static google.registry.model.billing.BillingBase.Flag.ANCHOR_TENANT;
import static google.registry.model.billing.BillingBase.Flag.RESERVED;
import static google.registry.model.billing.BillingBase.Flag.SUNRISE;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.domain.token.AllocationToken.TokenType.BULK_PRICING;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.REGISTER_BSA;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.eppcommon.EppXmlTransformer.marshal;
import static google.registry.model.eppcommon.StatusValue.PENDING_DELETE;
import static google.registry.model.eppcommon.StatusValue.SERVER_HOLD;
import static google.registry.model.tld.Tld.TldState.GENERAL_AVAILABILITY;
import static google.registry.model.tld.Tld.TldState.PREDELEGATION;
import static google.registry.model.tld.Tld.TldState.QUIET_PERIOD;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertNoDnsRequests;
import static google.registry.testing.DatabaseHelper.assertPollMessagesForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.deleteTld;
import static google.registry.testing.DatabaseHelper.getHistoryEntries;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.newContact;
import static google.registry.testing.DatabaseHelper.newHost;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import google.registry.config.RegistryConfig;
import google.registry.flows.EppException;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.DomainCreateFlow.AnchorTenantCreatePeriodException;
import google.registry.flows.domain.DomainCreateFlow.BulkDomainRegisteredForTooManyYearsException;
import google.registry.flows.domain.DomainCreateFlow.MustHaveSignedMarksInCurrentPhaseException;
import google.registry.flows.domain.DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException;
import google.registry.flows.domain.DomainCreateFlow.NoTrademarkedRegistrationsBeforeSunriseException;
import google.registry.flows.domain.DomainCreateFlow.RenewalPriceInfo;
import google.registry.flows.domain.DomainCreateFlow.SignedMarksOnlyDuringSunriseException;
import google.registry.flows.domain.DomainFlowTmchUtils.FoundMarkExpiredException;
import google.registry.flows.domain.DomainFlowTmchUtils.FoundMarkNotYetValidException;
import google.registry.flows.domain.DomainFlowTmchUtils.NoMarksFoundMatchingDomainException;
import google.registry.flows.domain.DomainFlowTmchUtils.SignedMarkRevokedErrorException;
import google.registry.flows.domain.DomainFlowUtils.AcceptedTooLongAgoException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.ClaimsPeriodEndedException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelBlockedByBsaException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainNameExistsAsTldException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.ExceedsMaxRegistrationYearsException;
import google.registry.flows.domain.DomainFlowUtils.ExpiredClaimException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionMultipleMatchesException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionParseException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredDuringEarlyAccessProgramException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.InvalidDsRecordException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.InvalidTcnIdChecksumException;
import google.registry.flows.domain.DomainFlowUtils.InvalidTrademarkValidatorException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MalformedTcnIdException;
import google.registry.flows.domain.DomainFlowUtils.MaxSigLifeNotSupportedException;
import google.registry.flows.domain.DomainFlowUtils.MissingAdminContactException;
import google.registry.flows.domain.DomainFlowUtils.MissingBillingAccountMapException;
import google.registry.flows.domain.DomainFlowUtils.MissingClaimsNoticeException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.MissingTechnicalContactException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedForTldException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedForTldWithNameserverAllowListException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrarMustBeActiveForThisOperationException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.UnexpectedClaimsNoticeException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedMarkTypeException;
import google.registry.flows.domain.DomainPricingLogic.AllocationTokenInvalidForPremiumNameException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AlreadyRedeemedAllocationTokenException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException;
import google.registry.flows.exceptions.OnlyToolCanPassMetadataException;
import google.registry.flows.exceptions.ResourceAlreadyExistsForThisClientException;
import google.registry.flows.exceptions.ResourceCreateContentionException;
import google.registry.model.billing.BillingBase;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.BaseFee.FeeType;
import google.registry.model.domain.fee.Fee;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.poll.PendingActionNotificationResponse.DomainPendingActionNotificationResponse;
import google.registry.model.poll.PollMessage;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.RegistrarBase.State;
import google.registry.model.reporting.DomainTransactionRecord;
import google.registry.model.reporting.DomainTransactionRecord.TransactionReportField;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.model.tld.Tld.TldType;
import google.registry.monitoring.whitebox.EppMetric;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import google.registry.tmch.SmdrlCsvParser;
import google.registry.tmch.TmchData;
import google.registry.tmch.TmchTestData;
import google.registry.xml.ValidationMode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.cartesian.CartesianTest;
import org.junitpioneer.jupiter.cartesian.CartesianTest.Values;

/** Unit tests for {@link DomainCreateFlow}. */
class DomainCreateFlowTest extends ResourceFlowTestCase<DomainCreateFlow, Domain> {

  private static final String CLAIMS_KEY = "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001";
  // This is a time when SMD itself is not valid, but its signing certificates are. It will
  // trigger a different exception than when any signing cert is not valid yet.
  private static final DateTime SMD_NOT_YET_VALID_TIME = DateTime.parse("2022-11-20T12:34:56Z");
  private static final DateTime SMD_VALID_TIME = DateTime.parse("2023-01-02T12:34:56Z");
  // This is a time when the imtermediate certificate in the SMD signature chain has expired. The
  // SMD itself has not expired yet. It will trigger a different exception than when the SMD itself
  // has expired.
  private static final DateTime SMD_CERT_EXPIRED_TIME = DateTime.parse("2027-11-02T12:34:56Z");
  private static final String SMD_ID = "000000851669081693741-65535";
  private static final String SMD_FILE_PATH = "smd/active.smd";
  private static final String ENCODED_SMD =
      TmchData.readEncodedSignedMark(TmchTestData.loadFile(SMD_FILE_PATH)).getEncodedData();

  private AllocationToken allocationToken;
  private boolean cachedUseMinimumDataset;

  DomainCreateFlowTest() {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z").minus(Duration.millis(2)));
  }

  @BeforeEach
  void initCreateTest() throws Exception {
    createTld("tld");
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abcDEF23456")
                .setTokenType(SINGLE_USE)
                .setDomainName("anchor.tld")
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "tld-reserved",
                    "reserved,FULLY_BLOCKED",
                    "resdom,RESERVED_FOR_SPECIFIC_USE",
                    "anchor,RESERVED_FOR_ANCHOR_TENANT",
                    "test-and-validate,NAME_COLLISION",
                    "badcrash,NAME_COLLISION"),
                persistReservedList("global-list", "resdom,FULLY_BLOCKED"))
            .build());
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY, "test-validate", CLAIMS_KEY));
    cachedUseMinimumDataset = RegistryConfig.useMinimumDataset();
  }

  @AfterEach
  void afterEach() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = cachedUseMinimumDataset;
  }

  private void enrollTldInBsa() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(clock.nowUtc().minusSeconds(1)))
            .build());
  }

  /**
   * Create host and contact entries for testing.
   *
   * @param hostTld the TLD of the host (which might be an external TLD)
   */
  private void persistContactsAndHosts(String hostTld) {
    for (int i = 1; i <= 14; ++i) {
      persistActiveHost(String.format("ns%d.example.%s", i, hostTld));
    }
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    clock.advanceOneMilli();
  }

  private void persistContactsAndHosts() {
    persistContactsAndHosts("net"); // domain_create.xml uses hosts on "net".
  }

  private void assertSuccessfulCreate(
      String domainTld, ImmutableSet<BillingBase.Flag> expectedBillingFlags) throws Exception {
    assertSuccessfulCreate(domainTld, expectedBillingFlags, null);
  }

  private void assertSuccessfulCreate(
      String domainTld,
      ImmutableSet<BillingBase.Flag> expectedBillingFlags,
      @Nullable AllocationToken allocationToken)
      throws Exception {
    Domain domain = reloadResourceByForeignKey();

    boolean isAnchorTenant = expectedBillingFlags.contains(ANCHOR_TENANT);
    // Set up the creation cost.
    BigDecimal createCost =
        isDomainPremium(getUniqueIdFromCommand(), clock.nowUtc())
            ? BigDecimal.valueOf(200)
            : BigDecimal.valueOf(24);
    if (isAnchorTenant) {
      createCost = BigDecimal.ZERO;
    }
    if (expectedBillingFlags.contains(SUNRISE)) {
      createCost =
          createCost.multiply(
              BigDecimal.valueOf(1 - RegistryConfig.getSunriseDomainCreateDiscount()));
    }
    FeesAndCredits feesAndCredits =
        new FeesAndCredits.Builder()
            .setCurrency(USD)
            .addFeeOrCredit(
                Fee.create(
                    createCost,
                    FeeType.CREATE,
                    isDomainPremium(getUniqueIdFromCommand(), clock.nowUtc())))
            .build();
    Money eapFee =
        Money.of(
            Tld.get(domainTld).getCurrency(),
            Tld.get(domainTld).getEapFeeFor(clock.nowUtc()).getCost());
    DateTime billingTime =
        isAnchorTenant
            ? clock.nowUtc().plus(Tld.get(domainTld).getAnchorTenantAddGracePeriodLength())
            : clock.nowUtc().plus(Tld.get(domainTld).getAddGracePeriodLength());
    assertLastHistoryContainsResource(domain);
    DomainHistory historyEntry = getHistoryEntries(domain, DomainHistory.class).get(0);
    assertAboutDomains()
        .that(domain)
        .hasRegistrationExpirationTime(
            tm().transact(() -> tm().loadByKey(domain.getAutorenewBillingEvent()).getEventTime()))
        .and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE)
        .and()
        .hasPeriodYears(2);
    RenewalPriceInfo renewalPriceInfo =
        DomainCreateFlow.getRenewalPriceInfo(isAnchorTenant, Optional.ofNullable(allocationToken));
    // There should be one bill for the create and one for the recurrence autorenew event.
    BillingEvent createBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.CREATE)
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setCost(feesAndCredits.getCreateCost())
            .setPeriodYears(2)
            .setEventTime(clock.nowUtc())
            .setBillingTime(billingTime)
            .setFlags(expectedBillingFlags)
            .setDomainHistory(historyEntry)
            .setAllocationToken(allocationToken == null ? null : allocationToken.createVKey())
            .build();

    BillingRecurrence renewBillingEvent =
        new BillingRecurrence.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setDomainHistory(historyEntry)
            .setRenewalPriceBehavior(renewalPriceInfo.renewalPriceBehavior())
            .setRenewalPrice(renewalPriceInfo.renewalPrice())
            .build();

    ImmutableSet.Builder<BillingBase> expectedBillingEvents =
        new ImmutableSet.Builder<BillingBase>().add(createBillingEvent).add(renewBillingEvent);

    // If EAP is applied, a billing event for EAP should be present.
    // EAP fees are bypassed for anchor tenant domains.
    if (!isAnchorTenant && !eapFee.isZero()) {
      BillingEvent eapBillingEvent =
          new BillingEvent.Builder()
              .setReason(Reason.FEE_EARLY_ACCESS)
              .setTargetId(getUniqueIdFromCommand())
              .setRegistrarId("TheRegistrar")
              .setPeriodYears(1)
              .setCost(eapFee)
              .setEventTime(clock.nowUtc())
              .setBillingTime(billingTime)
              .setFlags(expectedBillingFlags)
              .setDomainHistory(historyEntry)
              .build();
      expectedBillingEvents.add(eapBillingEvent);
    }
    assertBillingEvents(expectedBillingEvents.build());
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getDomainName())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntry)
            .build());

    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.ADD, domain.getRepoId(), billingTime, "TheRegistrar", null),
            createBillingEvent));
    assertDomainDnsRequests(getUniqueIdFromCommand());
  }

  private void assertNoLordn() throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId(null)
        .and()
        .hasLaunchNotice(null)
        .and()
        .hasLordnPhase(LordnPhase.NONE);
  }

  private void assertSunriseLordn() throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId(SMD_ID)
        .and()
        .hasLaunchNotice(null)
        .and()
        .hasLordnPhase(LordnPhase.SUNRISE);
  }

  private void assertClaimsLordn() throws Exception {
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasSmdId(null)
        .and()
        .hasLaunchNotice(
            LaunchNotice.create(
                "370d0b7c9223372036854775807",
                "tmch",
                DateTime.parse("2010-08-16T09:00:00.0Z"),
                DateTime.parse("2009-08-16T09:00:00.0Z")))
        .and()
        .hasLordnPhase(LordnPhase.CLAIMS);
  }

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions)
      throws Exception {
    assertMutatingFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, loadFile(responseXmlFile, substitutions));
    assertSuccessfulCreate(domainTld, ImmutableSet.of());
    assertNoLordn();
  }

  private void doSuccessfulTest(
      String domainTld, String responseXmlFile, Map<String, String> substitutions)
      throws Exception {
    doSuccessfulTest(domainTld, responseXmlFile, UserPrivileges.NORMAL, substitutions);
  }

  private void doSuccessfulTest(String domainTld) throws Exception {
    doSuccessfulTest(
        domainTld, "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("tld");
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testDryRun() throws Exception {
    persistContactsAndHosts();
    dryRunFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  void testSuccess_neverExisted() throws Exception {
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_cachingDisabled() throws Exception {
    boolean origIsCachingEnabled = RegistryConfig.isEppResourceCachingEnabled();
    try {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(false);
      persistContactsAndHosts();
      doSuccessfulTest();
    } finally {
      RegistryConfig.overrideIsEppResourceCachingEnabledForTesting(origIsCachingEnabled);
    }
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_create_no_cltrid.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_no_cltrid.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testFailure_invalidAllocationToken() {
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_reservedDomainCreate_allocationTokenIsForADifferentDomain() {
    // Try to register a reserved domain name with an allocation token valid for a different domain
    // name.
    setEppInput(
        "domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld", "YEARS", "2"));
    persistContactsAndHosts();
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
  void testFailure_nonreservedDomainCreate_allocationTokenIsForADifferentDomain() {
    // Try to register a non-reserved domain name with an allocation token valid for a different
    // domain name.
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
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
  void testFailure_alreadyRedemeedAllocationToken() {
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
    Domain domain = persistActiveDomain("foo.tld");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 505L);
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryId(historyEntryId)
            .build());
    clock.advanceOneMilli();
    EppException thrown =
        assertThrows(AlreadyRedeemedAllocationTokenException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_validAllocationToken_isRedeemed() throws Exception {
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
    clock.advanceOneMilli();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), token);
    HistoryEntry historyEntry = getHistoryEntries(reloadResourceByForeignKey()).get(0);
    assertThat(tm().transact(() -> tm().loadByEntity(token)).getRedemptionHistoryId())
        .hasValue(historyEntry.getHistoryEntryId());
  }

  // DomainTransactionRecord is not propagated.
  @Test
  void testSuccess_validAllocationToken_multiUse() throws Exception {
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setTokenType(UNLIMITED_USE)
                .setToken("abc123")
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                        .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                        .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                        .build())
                .build());
    clock.advanceOneMilli();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), allocationToken);
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "otherexample.tld", "YEARS", "2"));
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "otherexample.tld")));
  }

  @Test
  void testSuccess_multipartTld() throws Exception {
    createTld("foo.tld");
    setEppInput("domain_create_with_tld.xml", ImmutableMap.of("TLD", "foo.tld"));
    persistContactsAndHosts("foo.tld");
    assertMutatingFlow(true);
    String expectedResponseXml =
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.foo.tld"));
    runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, expectedResponseXml);
    assertSuccessfulCreate("foo.tld", ImmutableSet.of());
    assertNoLordn();
  }

  @Test
  void testFailure_domainNameExistsAsTld_lowercase() {
    createTlds("foo.tld", "tld");
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "foo.tld"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainNameExistsAsTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_domainNameExistsAsTld_uppercase() {
    createTlds("foo.tld", "tld");
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "FOO.TLD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(BadDomainNameCharacterException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_generalAvailability_withEncodedSignedMark() {
    createTld("tld", GENERAL_AVAILABILITY);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "open", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    EppException thrown = assertThrows(SignedMarksOnlyDuringSunriseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_generalAvailability_superuserMismatchedEncodedSignedMark() {
    createTld("tld", GENERAL_AVAILABILITY);
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "wrong.tld", "PHASE", "open", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoMarksFoundMatchingDomainException.class, this::runFlowAsSuperuser);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "USD"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.6", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "USD"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.11", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.6", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.11", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "24.00"));
  }

  @Test
  void testFailure_refundableFee_v06() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v11() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v12() {
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v06() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v11() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v12() {
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v06() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v11() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v12() {
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE)
        .and()
        .hasMetadataReason("domain-create-test")
        .and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @Test
  void testFailure_metadataNotFromTool() {
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(OnlyToolCanPassMetadataException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_premiumAndEap() throws Exception {
    createTld("example");
    setEppInput("domain_create_premium_eap.xml");
    persistContactsAndHosts("net");
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest(
        "example",
        "domain_create_response_premium_eap.xml",
        ImmutableMap.of("DOMAIN", "rich.example"));
  }

  /**
   * Test fix for a bug where we were looking at the length of the unicode string but indexing into
   * the punycode string. In rare cases (3 and 4 letter labels) this would cause us to think there
   * was a trailing dash in the domain name and fail to create it.
   */
  @Test
  void testSuccess_unicodeLengthBug() throws Exception {
    createTld("xn--q9jyb4c");
    persistContactsAndHosts("net");
    eppLoader.replaceAll("example.tld", "osx.xn--q9jyb4c");
    runFlow();
  }

  @Test
  void testSuccess_nonDefaultAddGracePeriod() throws Exception {
    persistResource(
        Tld.get("tld").asBuilder().setAddGracePeriodLength(Duration.standardMinutes(6)).build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_existedButWasDeleted() throws Exception {
    persistContactsAndHosts();
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_maxNumberOfNameservers() throws Exception {
    setEppInput("domain_create_13_nameservers.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_secDns() throws Exception {
    setEppInput("domain_create_dsdata_no_maxsiglife.xml");
    persistContactsAndHosts("tld"); // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    Domain domain = reloadResourceByForeignKey();
    assertAboutDomains()
        .that(domain)
        .hasExactlyDsData(
            DomainDsData.create(
                    12345, 3, 1, base16().decode("A94A8FE5CCB19BA61C4C0873D391E987982FBBD3"))
                .cloneWithDomainRepoId(domain.getRepoId()));
  }

  @Test
  void testSuccess_secDnsMaxRecords() throws Exception {
    setEppInput("domain_create_dsdata_8_records.xml");
    persistContactsAndHosts("tld"); // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    assertAboutDomains().that(reloadResourceByForeignKey()).hasNumDsData(8);
  }

  @Test
  void testSuccess_idn() throws Exception {
    createTld("xn--q9jyb4c");
    setEppInput("domain_create_idn_minna.xml");
    persistContactsAndHosts("net");
    runFlowAssertResponse(loadFile("domain_create_response_idn_minna.xml"));
    assertSuccessfulCreate("xn--q9jyb4c", ImmutableSet.of());
    assertDomainDnsRequests("xn--abc-873b2e7eb1k8a4lpjvv.xn--q9jyb4c");
  }

  @Test
  void testSuccess_noNameserversOrDsData() throws Exception {
    setEppInput("domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertNoDnsRequests();
  }

  @Test
  void testSuccess_periodNotSpecified() throws Exception {
    setEppInput("domain_create_missing_period.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")),
        "epp.response.resData.creData.exDate"); // Ignore expiration date; we verify it below
    assertAboutDomains()
        .that(reloadResourceByForeignKey())
        .hasRegistrationExpirationTime(clock.nowUtc().plusYears(1));
    assertDomainDnsRequests("example.tld");
  }

  @Test
  void testFailure_periodInMonths() {
    setEppInput("domain_create_months.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_claimsNotice() throws Exception {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of());
    assertDomainDnsRequests("example-one.tld");
    assertClaimsLordn();
  }

  @Test
  void testSuccess_claimsNoticeInQuietPeriod() throws Exception {
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setDomainName("example-one.tld")
                .setToken("abcDEF23456")
                .setTokenType(SINGLE_USE)
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    PREDELEGATION,
                    DateTime.parse("1999-01-01T00:00:00Z"),
                    QUIET_PERIOD))
            .setReservedLists(persistReservedList("res1", "example-one,RESERVED_FOR_SPECIFIC_USE"))
            .build());
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_allocationtoken_claims.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED), allocationToken);
    assertDomainDnsRequests("example-one.tld");
    assertClaimsLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_noClaimsNotice_forClaimsListName_afterClaimsPeriodEnd() throws Exception {
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Tld.get("tld").asBuilder().setClaimsPeriodEnd(clock.nowUtc()).build());
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of());
    assertDomainDnsRequests("example.tld");
  }

  @Test
  void testFailure_missingClaimsNotice() {
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingClaimsNoticeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_claimsNoticeProvided_nameNotOnClaimsList() {
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.of());
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnexpectedClaimsNoticeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_claimsNoticeProvided_claimsPeriodEnded() {
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Tld.get("tld").asBuilder().setClaimsPeriodEnd(clock.nowUtc()).build());
    EppException thrown = assertThrows(ClaimsPeriodEndedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_tooManyNameservers() {
    setEppInput("domain_create_14_nameservers.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(TooManyNameserversException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsMaxSigLife() {
    setEppInput("domain_create_dsdata.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MaxSigLifeNotSupportedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsTooManyDsRecords() {
    setEppInput("domain_create_dsdata_9_records.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(TooManyDsRecordsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsInvalidDigestType() throws Exception {
    setEppInput("domain_create_dsdata_bad_digest_types.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_secDnsInvalidAlgorithm() throws Exception {
    setEppInput("domain_create_dsdata_bad_algorithms.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidDsRecordException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongExtension() {
    setEppInput("domain_create_wrong_extension.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnimplementedExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v06() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "USD"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v06() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 8)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "USD"));
    persistContactsAndHosts();
    // $15 is 50% off the first year registration ($8) and 0% 0ff the 2nd year (renewal at $11)
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.6", "FEE", "15.00")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v06() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 100)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v11() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "USD"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v11() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 8)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "USD"));
    persistContactsAndHosts();
    // $12 is equal to 50% off the first year registration and 0% 0ff the 2nd year
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.11", "FEE", "15.00")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v11() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 100)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongFeeAmount_v12() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 20)))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_wrongFeeAmountTooHigh_defaultToken_v12() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 8)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();
    // $12 is equal to 50% off the first year registration and 0% 0ff the 2nd year
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "15.00")));
  }

  @Test
  void testFailure_wrongFeeAmountTooLow_defaultToken_v12() throws Exception {
    setupDefaultTokenWithDiscount();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 100)))
            .build());
    // Expects fee of $24
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v06() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v11() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v12() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unknownCurrency() {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "BAD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_alreadyExists() throws Exception {
    persistContactsAndHosts();
    persistActiveDomain(getUniqueIdFromCommand());
    ResourceAlreadyExistsForThisClientException thrown =
        assertThrows(ResourceAlreadyExistsForThisClientException.class, this::runFlow);
    assertAboutEppExceptions()
        .that(thrown)
        .marshalsToXml()
        .and()
        .hasMessage(
            String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_resourceContention() throws Exception {
    persistContactsAndHosts();
    String targetId = getUniqueIdFromCommand();
    persistResource(
        DatabaseHelper.newDomain(targetId)
            .asBuilder()
            .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
            .build());
    ResourceCreateContentionException thrown =
        assertThrows(ResourceCreateContentionException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .contains(String.format("Object with given ID (%s) already exists", targetId));
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_reserved() {
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainReservedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_anchorTenant() throws Exception {
    setEppInput("domain_create_anchor_allocationtoken.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_internalRegistrationWithSpecifiedRenewalPrice() throws Exception {
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("resdom.tld")
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .build());
    // Despite the domain being FULLY_BLOCKED, the non-superuser create succeeds the domain is also
    // RESERVED_FOR_SPECIFIC_USE and the correct allocation token is passed.
    setEppInput(
        "domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld", "YEARS", "2"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "resdom.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abc123");
  }

  @Test
  void testFailure_anchorTenant_notTwoYearPeriod() {
    setEppInput("domain_create_anchor_tenant_invalid_years.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(AnchorTenantCreatePeriodException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_anchorTenant_bypassesEapFees() throws Exception {
    setEapForTld("tld");
    // This XML file does not contain EAP fees.
    setEppInput("domain_create_anchor_allocationtoken.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_anchorTenant_withClaims() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("example-one.tld")
            .setToken("abcDEF23456")
            .setTokenType(SINGLE_USE)
            .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList("anchor-with-claims", "example-one,RESERVED_FOR_ANCHOR_TENANT"))
            .build());
    setEppInput("domain_create_allocationtoken_claims.xml");
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
    assertDomainDnsRequests("example-one.tld");
    assertClaimsLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_anchorTenant_withMetadataExtension() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_anchor_tenant_metadata_extension.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT));
    assertNoLordn();
  }

  @Test
  void testSuccess_anchorTenantInSunrise_withMetadataExtension() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_anchor_tenant_sunrise_metadata_extension.xml");
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    // Even for anchor tenants, require signed marks in sunrise
    EppException exception =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(exception).marshalsToXml();
  }

  @Test
  void testSuccess_anchorTenantInSunrise_withMetadataExtension_andSignedMark() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput(
        "domain_create_anchor_tenant_sunrise_metadata_extension_signed_mark.xml",
        ImmutableMap.of("SMD", ENCODED_SMD));
    eppRequestSource = EppRequestSource.TOOL; // Only tools can pass in metadata.
    persistContactsAndHosts();
    clock.setTo(SMD_VALID_TIME);
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of(
                "DOMAIN",
                "test-validate.tld",
                "CREATE_TIME",
                SMD_VALID_TIME.toString(),
                "EXPIRATION_TIME",
                SMD_VALID_TIME.plusYears(2).toString())));
    assertSuccessfulCreate("tld", ImmutableSet.of(SUNRISE, ANCHOR_TENANT));
  }

  @Test
  void testSuccess_anchorTenantInSunrise_withSignedMark() throws Exception {
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setDomainName("test-validate.tld")
                .setToken("abcDEF23456")
                .setTokenType(SINGLE_USE)
                .build());
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList("anchor_tenants", "test-validate,RESERVED_FOR_ANCHOR_TENANT"))
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, START_DATE_SUNRISE))
            .build());
    setEppInput("domain_create_anchor_tenant_signed_mark.xml", ImmutableMap.of("SMD", ENCODED_SMD));
    clock.setTo(SMD_VALID_TIME);
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of(
                "DOMAIN",
                "test-validate.tld",
                "CREATE_TIME",
                SMD_VALID_TIME.toString(),
                "EXPIRATION_TIME",
                SMD_VALID_TIME.plusYears(2).toString())));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT, SUNRISE), allocationToken);
    assertDomainDnsRequests("test-validate.tld");
    assertSunriseLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_anchorTenant_duringQuietPeriodBeforeSunrise() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                new ImmutableSortedMap.Builder<DateTime, TldState>(Ordering.natural())
                    .put(START_OF_TIME, PREDELEGATION)
                    .put(DateTime.parse("1999-01-01T00:00:00Z"), QUIET_PERIOD)
                    .put(DateTime.parse("1999-07-01T00:00:00Z"), START_DATE_SUNRISE)
                    .put(DateTime.parse("2000-01-01T00:00:00Z"), GENERAL_AVAILABILITY)
                    .build())
            .build());
    // The anchor tenant is created during the quiet period, on 1999-04-03.
    setEppInput("domain_create_anchor_allocationtoken.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testSuccess_reservedDomain_viaAllocationTokenExtension() throws Exception {
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("resdom.tld")
                .build());
    // Despite the domain being FULLY_BLOCKED, the non-superuser create succeeds the domain is also
    // RESERVED_FOR_SPECIFIC_USE and the correct allocation token is passed.
    setEppInput(
        "domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld", "YEARS", "2"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "resdom.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abc123");
  }

  @Test
  void testSuccess_reservedDomain_viaAllocationTokenExtension_inQuietPeriod() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("resdom.tld")
                .build());
    setEppInput(
        "domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld", "YEARS", "2"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "resdom.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abc123");
  }

  private void assertAllocationTokenWasRedeemed(String token) throws Exception {
    AllocationToken reloadedToken =
        tm().transact(() -> tm().loadByKey(VKey.create(AllocationToken.class, token)));
    assertThat(reloadedToken.isRedeemed()).isTrue();
    assertThat(reloadedToken.getRedemptionHistoryId())
        .hasValue(getHistoryEntries(reloadResourceByForeignKey()).get(0).getHistoryEntryId());
  }

  private static void assertAllocationTokenWasNotRedeemed(String token) {
    AllocationToken reloadedToken =
        tm().transact(() -> tm().loadByKey(VKey.create(AllocationToken.class, token)));
    assertThat(reloadedToken.isRedeemed()).isFalse();
  }

  @Test
  void testSuccess_allocationTokenPromotion() throws Exception {
    // A discount of 0.5 means that the first-year cost (13) is cut in half, so a discount of 6.5
    // Note: we're asking to register it for two years so the total cost should be 13 + (13/2)
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusMillis(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusSeconds(1), TokenStatus.ENDED)
                    .build())
            .build());
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, BigDecimal.valueOf(17.5)));
  }

  @Test
  void testSuccess_allocationToken_multiYearDiscount_maxesAtTokenDiscountYears() throws Exception {
    // ($13 + $11 + $11) *  (1 - 0.73) + 2 * $11 =
    runTest_allocationToken_multiYearDiscount(false, 0.73, 3, Money.of(USD, 31.45));
  }

  @Test
  void testSuccess_allocationToken_multiYearDiscount_maxesAtNumRegistrationYears()
      throws Exception {
    // ($13 + 4 * $11) * (1 - 0.276) = $41.27
    runTest_allocationToken_multiYearDiscount(false, 0.276, 10, Money.of(USD, 41.27));
  }

  void runTest_allocationToken_multiYearDiscount(
      boolean discountPremiums, double discountFraction, int discountYears, Money expectedPrice)
      throws Exception {
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("example.tld")
            .setDiscountFraction(discountFraction)
            .setDiscountYears(discountYears)
            .setDiscountPremiums(discountPremiums)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusMillis(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusSeconds(1), TokenStatus.ENDED)
                    .build())
            .build());
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "5"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_wildcard.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "example.tld")
                .put("CRDATE", "1999-04-03T22:00:00.0Z")
                .put("EXDATE", "2004-04-03T22:00:00.0Z")
                .build()));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getCost()).isEqualTo(expectedPrice);
  }

  @Test
  void testSuccess_allocationToken_multiYearDiscount_worksForPremiums() throws Exception {
    createTld("example");
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("rich.example")
            .setDiscountFraction(0.98)
            .setDiscountYears(2)
            .setDiscountPremiums(true)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusMillis(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusSeconds(1), TokenStatus.ENDED)
                    .build())
            .build());
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("YEARS", "3", "FEE", "104.00"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of("EXDATE", "2002-04-03T22:00:00.0Z", "FEE", "104.00")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("rich.example");
    // 1yr @ $100 + 2yrs @ $100 * (1 - 0.98) = $104
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 104.00));
  }

  @Test
  void testSuccess_allocationToken_singleYearDiscount_worksForPremiums() throws Exception {
    createTld("example");
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("rich.example")
            .setDiscountFraction(0.95555)
            .setDiscountPremiums(true)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusMillis(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusSeconds(1), TokenStatus.ENDED)
                    .build())
            .build());
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("YEARS", "3", "FEE", "204.44"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of("EXDATE", "2002-04-03T22:00:00.0Z", "FEE", "204.44")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("rich.example");
    // 2yrs @ $100 + 1yr @ $100 * (1 - 0.95555) = $204.44
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 204.44));
  }

  @Test
  void testSuccess_promotionDoesNotApplyToPremiumPrice() {
    // Discounts only apply to premium domains if the token is explicitly configured to allow it.
    createTld("example");
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().plusMillis(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusSeconds(1), TokenStatus.ENDED)
                    .build())
            .build());
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("YEARS", "2", "FEE", "193.50"));
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenInvalidForPremiumNameException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testFailure_promotionNotActive() {
    persistContactsAndHosts();
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
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotInPromotionException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testSuccess_promoTokenNotValidForTld() {
    persistContactsAndHosts();
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
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotValidForTldException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testSuccess_promoTokenNotValidForRegistrar() {
    persistContactsAndHosts();
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
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    assertAboutEppExceptions()
        .that(assertThrows(AllocationTokenNotValidForRegistrarException.class, this::runFlow))
        .marshalsToXml();
  }

  @Test
  void testSuccess_usesDefaultToken() throws Exception {
    persistContactsAndHosts();
    setupDefaultToken("aaaaa", 0, "NewRegistrar");
    setupDefaultTokenWithDiscount();
    runTest_defaultToken("bbbbb");
  }

  @Test
  void testSuccess_doesNotUseDefaultTokenWhenTokenPassedIn() throws Exception {
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .build());
    setupDefaultToken("aaaaa", 0, "NewRegistrar");
    setupDefaultTokenWithDiscount();
    clock.advanceOneMilli();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, BigDecimal.valueOf(17.5)));
    assertThat(billingEvent.getAllocationToken().get().getKey()).isEqualTo("abc123");
  }

  @Test
  void testSuccess_noValidDefaultToken() throws Exception {
    persistContactsAndHosts();
    setupDefaultToken("aaaaa", 0, "NewRegistrar");
    setupDefaultToken("bbbbb", 0, "OtherRegistrar");
    doSuccessfulTest();
  }

  @Test
  void testSuccess_onlyUseFirstValidDefaultToken() throws Exception {
    persistContactsAndHosts();
    setupDefaultToken("aaaaa", 0, "TheRegistrar");
    setupDefaultTokenWithDiscount();
    runTest_defaultToken("aaaaa");
  }

  @Test
  void testSuccess_registryHasDeletedDefaultToken() throws Exception {
    persistContactsAndHosts();
    AllocationToken defaultToken1 = setupDefaultToken("aaaaa", 0, "NewRegistrar");
    setupDefaultTokenWithDiscount();
    DatabaseHelper.deleteResource(defaultToken1);
    assertThat(runTest_defaultToken("bbbbb").getCost()).isEqualTo(Money.of(USD, 17.50));
  }

  @Test
  void testSuccess_defaultTokenAppliesCorrectPrice() throws Exception {
    persistContactsAndHosts();
    setupDefaultToken("aaaaa", 0, "NewRegistrar");
    setupDefaultTokenWithDiscount();
    assertThat(runTest_defaultToken("bbbbb").getCost())
        .isEqualTo(Money.of(USD, BigDecimal.valueOf(17.5)));
  }

  @Test
  void testSuccess_skipsOverExpiredDefaultToken() throws Exception {
    persistContactsAndHosts();
    persistResource(
        setupDefaultTokenWithDiscount()
            .asBuilder()
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(2), TokenStatus.VALID)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    doSuccessfulTest();
  }

  @Test
  void testSuccess_doesNotApplyNonPremiumDefaultTokenToPremiumName() throws Exception {
    persistContactsAndHosts();
    createTld("example");
    persistResource(
        setupDefaultTokenWithDiscount()
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("example"))
            .build());
    setEppInput("domain_create_premium.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of("EXDATE", "2001-04-03T22:00:00.0Z", "FEE", "200.00")));
    assertSuccessfulCreate("example", ImmutableSet.of());
  }

  private BillingEvent runTest_defaultToken(String token) throws Exception {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_wildcard.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "example.tld")
                .put("CRDATE", "1999-04-03T22:00:00.0Z")
                .put("EXDATE", "2001-04-03T22:00:00.0Z")
                .build()));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("example.tld");
    assertThat(billingEvent.getAllocationToken().get().getKey()).isEqualTo(token);
    return billingEvent;
  }

  @Test
  void testSuccess_superuserReserved() throws Exception {
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE, SUPERUSER, loadFile("domain_create_reserved_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED));
  }

  @Test
  void testSuccess_reservedNameCollisionDomain_inSunrise_setsServerHoldAndPollMessage()
      throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, START_DATE_SUNRISE))
            .build());
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-and-validate.tld", "PHASE", "sunrise", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of(
                "DOMAIN",
                "test-and-validate.tld",
                "CREATE_TIME",
                SMD_VALID_TIME.toString(),
                "EXPIRATION_TIME",
                SMD_VALID_TIME.plusYears(2).toString())));

    assertSunriseLordn();

    // Check for SERVER_HOLD status, no DNS tasks enqueued, and collision poll message.
    assertNoDnsRequests();
    Domain domain = reloadResourceByForeignKey();
    assertThat(domain.getStatusValues()).contains(SERVER_HOLD);
    assertPollMessagesWithCollisionOneTime(domain);
  }

  @Test
  void testSuccess_reservedNameCollisionDomain_withSuperuser_setsServerHoldAndPollMessage()
      throws Exception {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "badcrash.tld"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        SUPERUSER,
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "badcrash.tld")));

    // Check for SERVER_HOLD status, no DNS tasks enqueued, and collision poll message.
    assertNoDnsRequests();
    Domain domain = reloadResourceByForeignKey();
    assertThat(domain.getStatusValues()).contains(SERVER_HOLD);
    assertPollMessagesWithCollisionOneTime(domain);
  }

  private void assertPollMessagesWithCollisionOneTime(Domain domain) {
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getDomainName())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntry)
            .build(),
        new PollMessage.OneTime.Builder()
            .setHistoryEntry(historyEntry)
            .setEventTime(domain.getCreationTime())
            .setRegistrarId("TheRegistrar")
            .setMsg(DomainFlowUtils.COLLISION_MESSAGE)
            .setResponseData(
                ImmutableList.of(
                    DomainPendingActionNotificationResponse.create(
                        domain.getDomainName(), true, historyEntry.getTrid(), clock.nowUtc())))
            .setId(1L)
            .build());
  }

  @Test
  void testFailure_missingHost() {
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(ns2.example.net)");
  }

  @Test
  void testFailure_pendingDeleteHost() {
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    persistResource(newHost("ns2.example.net").asBuilder().addStatusValue(PENDING_DELETE).build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns2.example.net");
  }

  @Test
  void testFailure_missingContact() {
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    LinkedResourcesDoNotExistException thrown =
        assertThrows(LinkedResourcesDoNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("(sh8013)");
  }

  @Test
  void testFailure_pendingDeleteContact() {
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("sh8013");
    persistResource(newContact("jd1234").asBuilder().addStatusValue(PENDING_DELETE).build());
    clock.advanceOneMilli();
    LinkedResourceInPendingDeleteProhibitsOperationException thrown =
        assertThrows(LinkedResourceInPendingDeleteProhibitsOperationException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("jd1234");
  }

  @Test
  void testFailure_wrongTld() {
    persistContactsAndHosts("net");
    deleteTld("tld");
    EppException thrown = assertThrows(TldDoesNotExistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_predelegation() {
    createTld("tld", PREDELEGATION);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_startDateSunrise_missingLaunchExtension() {
    createTld("tld", START_DATE_SUNRISE);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_quietPeriod() {
    createTld("tld", QUIET_PERIOD);
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserPredelegation() throws Exception {
    createTld("tld", PREDELEGATION);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testSuccess_superuserStartDateSunrise_isSuperuser() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testSuccess_superuserQuietPeriod() throws Exception {
    createTld("tld", QUIET_PERIOD);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testSuccess_superuserOverridesPremiumNameBlock() throws Exception {
    createTld("example");
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        SUPERUSER,
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of("EXDATE", "2001-04-03T22:00:00.0Z", "FEE", "200.00")));
    assertSuccessfulCreate("example", ImmutableSet.of());
  }

  @Test
  void testSuccess_customLogicIsCalled_andSavesExtraEntity() throws Exception {
    // @see TestDomainCreateFlowCustomLogic for what the label "custom-logic-test" triggers.
    ImmutableMap<String, String> substitutions = ImmutableMap.of("DOMAIN", "custom-logic-test.tld");
    setEppInput("domain_create.xml", substitutions);
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.NORMAL,
        loadFile("domain_create_response.xml", substitutions));
    Domain domain = reloadResourceByForeignKey();
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertPollMessagesForResource(
        domain,
        new PollMessage.Autorenew.Builder()
            .setTargetId(domain.getDomainName())
            .setRegistrarId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setMsg("Domain was auto-renewed.")
            .setHistoryEntry(historyEntry)
            .build(),
        new PollMessage.OneTime.Builder()
            .setHistoryEntry(historyEntry)
            .setEventTime(domain.getCreationTime())
            .setRegistrarId("TheRegistrar")
            .setMsg("Custom logic was triggered")
            .setId(1L)
            .build());
  }

  @Test
  void testFailure_duplicateContact() {
    setEppInput("domain_create_duplicate_contact.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DuplicateContactForRoleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_missingContactType() {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    setEppInput("domain_create_missing_contact_type.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingContactTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_thickRegistry_missingRegistrant() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_create_missing_registrant.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingRegistrantException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_missingRegistrant() throws Exception {
    setEppInput("domain_create_missing_registrant.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  void testFailure_thickRegistry_missingAdmin() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_create_missing_admin.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_missingAdmin() throws Exception {
    setEppInput("domain_create_missing_admin.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  void testFailure_thickRegistry_missingTech() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_create_missing_tech.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingTechnicalContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_missingTech() throws Exception {
    setEppInput("domain_create_missing_tech.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  void testFailure_thickRegistry_missingNonRegistrantContacts() {
    RegistryConfig.CONFIG_SETTINGS.get().registryPolicy.useMinimumDataset = false;
    setEppInput("domain_create_missing_non_registrant_contacts.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MissingAdminContactException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_thinRegistry_missingNonRegistrantContacts() throws Exception {
    setEppInput("domain_create_missing_non_registrant_contacts.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld")));
  }

  @Test
  void testFailure_badIdn() {
    createTld("xn--q9jyb4c");
    setEppInput("domain_create_bad_idn_minna.xml");
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(InvalidIdnDomainLabelException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_badValidatorId() {
    setEppInput("domain_create_bad_validator_id.xml");
    persistClaimsList(ImmutableMap.of("exampleone", CLAIMS_KEY));
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidTrademarkValidatorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_codeMark() {
    setEppInput("domain_create_code_with_mark.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedMarkTypeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_expiredClaim() {
    clock.setTo(DateTime.parse("2010-08-17T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(ExpiredClaimException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_expiredAcceptance() {
    clock.setTo(DateTime.parse("2009-09-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(AcceptedTooLongAgoException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_malformedTcnIdWrongLength() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice1.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MalformedTcnIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_malformedTcnIdBadChar() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice2.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(MalformedTcnIdException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_badTcnIdChecksum() {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_bad_checksum_claim_notice.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(InvalidTcnIdChecksumException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_premiumBlocked() {
    createTld("example");
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown = assertThrows(PremiumNameBlockedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_premiumNotAcked() {
    createTld("example");
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "rich.example"));
    persistContactsAndHosts("net");
    EppException thrown = assertThrows(FeesRequiredForPremiumNameException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_omitFeeExtensionOnLogin_v06() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_omitFeeExtensionOnLogin_v11() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_omitFeeExtensionOnLogin_v12() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v06() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v11() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v12() {
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_suspendedRegistrarCantCreateDomain() {
    doFailingTest_invalidRegistrarState(State.SUSPENDED);
  }

  @Test
  void testFailure_pendingRegistrarCantCreateDomain() {
    doFailingTest_invalidRegistrarState(State.PENDING);
  }

  @Test
  void testFailure_disabledRegistrarCantCreateDomain() {
    doFailingTest_invalidRegistrarState(State.DISABLED);
  }

  private void doFailingTest_invalidRegistrarState(State registrarState) {
    persistContactsAndHosts();
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

  private void doFailingDomainNameTest(String domainName, Class<? extends EppException> exception) {
    setEppInput("domain_create_uppercase.xml");
    eppLoader.replaceAll("Example.tld", domainName);
    persistContactsAndHosts();
    EppException thrown = assertThrows(exception, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_bsaLabelMatch_notEnrolled() throws Exception {
    persistResource(Tld.get("tld").asBuilder().setBsaEnrollStartTime(Optional.empty()).build());
    persistBsaLabel("example");
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_bsaLabelMatch_notEnrolledYet() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setBsaEnrollStartTime(Optional.of(clock.nowUtc().plusSeconds(1)))
            .build());
    persistBsaLabel("example");
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testSuccess_blockedByBsa_hasRegisterBsaToken() throws Exception {
    enrollTldInBsa();
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(REGISTER_BSA)
                .setDomainName("example.tld")
                .build());
    persistBsaLabel("example");
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), allocationToken);
  }

  @Test
  void testSuccess_blockedByBsa_reservedDomain_viaAllocationTokenExtension() throws Exception {
    enrollTldInBsa();
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(REGISTER_BSA)
                .setDomainName("resdom.tld")
                .build());
    persistBsaLabel("resdom");
    setEppInput(
        "domain_create_allocationtoken.xml", ImmutableMap.of("DOMAIN", "resdom.tld", "YEARS", "2"));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile("domain_create_response.xml", ImmutableMap.of("DOMAIN", "resdom.tld")));
    assertSuccessfulCreate("tld", ImmutableSet.of(RESERVED), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abc123");
  }

  @Test
  void testSuccess_blockedByBsa_quietPeriod_skipTldStateCheckWithToken() throws Exception {
    enrollTldInBsa();
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(REGISTER_BSA)
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .setDomainName("example.tld")
                .build());
    persistContactsAndHosts();
    persistBsaLabel("example");
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), token);
  }

  @Test
  void testSuccess_blockedByBsa_anchorTenant() throws Exception {
    enrollTldInBsa();
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abcDEF23456")
                .setTokenType(REGISTER_BSA)
                .setDomainName("anchor.tld")
                .build());
    setEppInput("domain_create_anchor_allocationtoken.xml");
    persistContactsAndHosts();
    persistBsaLabel("anchor");
    runFlowAssertResponse(loadFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
    assertNoLordn();
    assertAllocationTokenWasRedeemed("abcDEF23456");
  }

  @Test
  void testFailure_blockedByBsa() throws Exception {
    enrollTldInBsa();
    persistBsaLabel("example");
    persistContactsAndHosts();
    EppException thrown = assertThrows(DomainLabelBlockedByBsaException.class, this::runFlow);
    assertAboutEppExceptions()
        .that(thrown)
        .marshalsToXml()
        .and()
        .hasMessage("Domain label is blocked by the Brand Safety Alliance");
    byte[] responseXmlBytes =
        marshal(
            EppOutput.create(
                new EppResponse.Builder()
                    .setTrid(Trid.create(null, "server-trid"))
                    .setResult(thrown.getResult())
                    .build()),
            ValidationMode.STRICT);
    assertThat(new String(responseXmlBytes, StandardCharsets.UTF_8))
        .isEqualTo(loadFile("domain_create_blocked_by_bsa.xml"));
  }

  @Test
  void testFailure_blockedByBsa_hasWrongToken() throws Exception {
    enrollTldInBsa();
    allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .setDomainName("example.tld")
                .build());
    persistBsaLabel("example");
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    EppException thrown = assertThrows(DomainLabelBlockedByBsaException.class, this::runFlow);
    assertAboutEppExceptions()
        .that(thrown)
        .marshalsToXml()
        .and()
        .hasMessage("Domain label is blocked by the Brand Safety Alliance");
    byte[] responseXmlBytes =
        marshal(
            EppOutput.create(
                new EppResponse.Builder()
                    .setTrid(Trid.create(null, "server-trid"))
                    .setResult(thrown.getResult())
                    .build()),
            ValidationMode.STRICT);
    assertThat(new String(responseXmlBytes, StandardCharsets.UTF_8))
        .isEqualTo(loadFile("domain_create_blocked_by_bsa.xml"));
  }

  @Test
  void testFailure_uppercase() {
    doFailingDomainNameTest("Example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  void testFailure_badCharacter() {
    doFailingDomainNameTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  void testFailure_leadingDash() {
    doFailingDomainNameTest("-example.tld", LeadingDashException.class);
  }

  @Test
  void testFailure_trailingDash() {
    doFailingDomainNameTest("example-.tld", TrailingDashException.class);
  }

  @Test
  void testFailure_tooLong() {
    doFailingDomainNameTest("a".repeat(64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  void testFailure_leadingDot() {
    doFailingDomainNameTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  void testFailure_leadingDotTld() {
    doFailingDomainNameTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  void testFailure_tooManyParts() {
    doFailingDomainNameTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  void testFailure_tooFewParts() {
    doFailingDomainNameTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  void testFailure_invalidPunycode() {
    // You don't want to know what this string (might?) mean.
    doFailingDomainNameTest("xn--uxa129t5ap4f1h1bc3p.tld", InvalidPunycodeException.class);
  }

  @Test
  void testFailure_dashesInThirdAndFourthPosition() {
    doFailingDomainNameTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  void testFailure_tldDoesNotExist() {
    doFailingDomainNameTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  void testFailure_invalidIdnCodePoints() {
    // ❤☀☆☂☻♞☯.tld
    doFailingDomainNameTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  void testFailure_startDateSunriseRegistration_missingSignedMark() {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_superuserStartDateSunriseRegistration_isSuperuser() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response.xml", SUPERUSER, ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testSuccess_startDateSunriseRegistration_withEncodedSignedMark() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of(
                "DOMAIN",
                "test-validate.tld",
                "CREATE_TIME",
                SMD_VALID_TIME.toString(),
                "EXPIRATION_TIME",
                SMD_VALID_TIME.plusYears(2).toString())));
    assertSuccessfulCreate("tld", ImmutableSet.of(SUNRISE));
    assertSunriseLordn();
  }

  /** Test that missing type= argument on launch create works in start-date sunrise. */
  @Test
  void testSuccess_startDateSunriseRegistration_withEncodedSignedMark_noType() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_sunrise_encoded_signed_mark_no_type.xml",
        ImmutableMap.of("SMD", ENCODED_SMD));
    persistContactsAndHosts();
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_encoded_signed_mark_name.xml",
            ImmutableMap.of(
                "DOMAIN",
                "test-validate.tld",
                "CREATE_TIME",
                SMD_VALID_TIME.toString(),
                "EXPIRATION_TIME",
                SMD_VALID_TIME.plusYears(2).toString())));
    assertSuccessfulCreate("tld", ImmutableSet.of(SUNRISE));
    assertSunriseLordn();
  }

  @Test
  void testFail_startDateSunriseRegistration_wrongEncodedSignedMark() {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "wrong.tld", "PHASE", "sunrise", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    EppException thrown = assertThrows(NoMarksFoundMatchingDomainException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFail_startDateSunriseRegistration_revokedSignedMark() throws Exception {
    SmdrlCsvParser.parse(TmchTestData.loadFile("smd/smdrl.csv").lines().collect(toImmutableList()))
        .save();
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    String revokedSmd =
        TmchData.readEncodedSignedMark(TmchTestData.loadFile("smd/revoked.smd")).getEncodedData();
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise", "SMD", revokedSmd));
    persistContactsAndHosts();
    EppException thrown = assertThrows(SignedMarkRevokedErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @CartesianTest
  void testFail_startDateSunriseRegistration_IdnRevokedSignedMark(
      @Values(strings = {"Agent", "Holder"}) String contact,
      @Values(strings = {"Court", "Trademark", "TreatyStatute"}) String type,
      @Values(strings = {"Arab", "Chinese", "English", "French"}) String language)
      throws Exception {
    String filepath =
        String.format("idn/%s-%s/%s-%s-%s-Revoked.smd", contact, language, type, contact, language);
    ImmutableList<String> labels = TmchTestData.extractLabels(filepath);
    if (labels.isEmpty()) {
      return;
    }
    SmdrlCsvParser.parse(
            TmchTestData.loadFile("idn/idn_smdrl.csv").lines().collect(toImmutableList()))
        .save();
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    String revokedSmd =
        TmchData.readEncodedSignedMark(TmchTestData.loadFile(filepath)).getEncodedData();
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", labels.get(0) + ".tld", "PHASE", "sunrise", "SMD", revokedSmd));
    persistContactsAndHosts();
    EppException thrown = assertThrows(SignedMarkRevokedErrorException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFail_startDateSunriseRegistration_markNotYetValid() {
    createTld("tld", START_DATE_SUNRISE);
    // If we move now back in time a bit, the mark will not have gone into effect yet.
    clock.setTo(SMD_NOT_YET_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FoundMarkNotYetValidException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFail_startDateSunriseRegistration_markExpired() {
    createTld("tld", START_DATE_SUNRISE);
    // Move time forward to the mark expiration time.
    clock.setTo(SMD_CERT_EXPIRED_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "PHASE", "sunrise", "SMD", ENCODED_SMD));
    persistContactsAndHosts();
    EppException thrown = assertThrows(FoundMarkExpiredException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_startDateSunriseRegistration_withClaimsNotice() {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_start_date_sunrise_claims_notice.xml");
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_notAuthorizedForTld() {
    createTld("irrelevant", "IRR");
    persistResource(
        loadRegistrar("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("irrelevant"))
            .build());
    persistContactsAndHosts();
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_missingBillingAccountMap() {
    persistContactsAndHosts();
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
  void testFailure_registrantNotAllowListed() {
    persistActiveContact("someone");
    persistContactsAndHosts();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("someone"))
            .build());
    RegistrantNotAllowedException thrown =
        assertThrows(RegistrantNotAllowedException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("jd1234");
  }

  @Test
  void testFailure_nameserverNotAllowListed() {
    persistContactsAndHosts();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.net"))
            .build());
    NameserversNotAllowedForTldException thrown =
        assertThrows(NameserversNotAllowedForTldException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("ns1.example.net");
  }

  @Test
  void testFailure_emptyNameserverFailsAllowList() {
    setEppInput("domain_create_no_hosts_or_dsdata.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("somethingelse.example.net"))
            .build());
    persistContactsAndHosts();
    EppException thrown =
        assertThrows(
            NameserversNotSpecifiedForTldWithNameserverAllowListException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_nameserverAndRegistrantAllowListed() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setAllowedRegistrantContactIds(ImmutableSet.of("jd1234"))
            .setAllowedFullyQualifiedHostNames(
                ImmutableSet.of("ns1.example.net", "ns2.example.net"))
            .build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  void testFailure_eapFee_combined() {
    setEppInput("domain_create_eap_combined_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeeDescriptionParseException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("No fee description");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_eapFee_description_swapped() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "Early Access Period",
            "DESCRIPTION_2",
            "create"));
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("CREATE");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_eapFee_totalAmountNotMatched() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "24")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "100")
            .put("DESCRIPTION_3", "renew")
            .put("FEE_3", "55")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected total of USD 124.00");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_eapFee_multipleEAPfees_doNotAddToExpectedValue() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "24")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "55")
            .put("DESCRIPTION_3", "Early Access Period")
            .put("FEE_3", "55")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("expected fee of USD 100.00");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_eapFee_multipleEAPfees_addToExpectedValue() throws Exception {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "24")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "55")
            .put("DESCRIPTION_3", "Early Access Period")
            .put("FEE_3", "45")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  void testSuccess_eapFee_fullDescription_includingArbitraryExpiryTime() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period, fee expires: 2022-03-01T00:00:00.000Z"));
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  void testFailure_eapFee_description_multipleMatch() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION", "0.6", "DESCRIPTION_1", "create", "DESCRIPTION_2", "renew transfer"));
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeeDescriptionMultipleMatchesException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("RENEW, TRANSFER");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_eapFeeApplied_v06() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  void testSuccess_eapFeeApplied_v11() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.11",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  void testSuccess_eapFeeApplied_v12() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "0.12",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period"));
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  void testFailure_domainInEap_failsWithoutFeeExtension() {
    persistContactsAndHosts();
    setEapForTld("tld");
    Exception e = assertThrows(FeesRequiredDuringEarlyAccessProgramException.class, this::runFlow);
    assertThat(e)
        .hasMessageThat()
        .isEqualTo(
            "Fees must be explicitly acknowledged when creating domains "
                + "during the Early Access Program. The EAP fee is: USD 100.00");
  }

  private void setEapForTld(String tld) {
    persistResource(
        Tld.get(tld)
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 100),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 0)))
            .build());
  }

  @Test
  void testSuccess_eapFee_beforeEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().plusDays(1),
                    Money.of(USD, 10),
                    clock.nowUtc().plusDays(2),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest("tld", "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testSuccess_eapFee_afterEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    Money.of(USD, 0),
                    clock.nowUtc().minusDays(2),
                    Money.of(USD, 100),
                    clock.nowUtc().minusDays(1),
                    Money.of(USD, 0)))
            .build());
    doSuccessfulTest("tld", "domain_create_response.xml", ImmutableMap.of("DOMAIN", "example.tld"));
  }

  @Test
  void testFailure_max10Years() {
    setEppInput("domain_create_11_years.xml");
    persistContactsAndHosts();
    EppException thrown = assertThrows(ExceedsMaxRegistrationYearsException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistContactsAndHosts();
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-create");
    assertTldsFieldLogged("tld");
    // Ensure we log the client ID for srs-dom-create, so we can also use it for attempted-adds.
    assertClientIdFieldLogged("TheRegistrar");
  }

  @Test
  void testIcannTransactionRecord_getsStored() throws Exception {
    persistContactsAndHosts();
    persistResource(
        Tld.get("tld").asBuilder().setAddGracePeriodLength(Duration.standardMinutes(9)).build());
    runFlow();
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntry = (DomainHistory) getHistoryEntries(domain).get(0);
    assertThat(historyEntry.getDomainTransactionRecords())
        .containsExactly(
            DomainTransactionRecord.create(
                "tld",
                historyEntry.getModificationTime().plusMinutes(9),
                TransactionReportField.netAddsFieldFromYears(2),
                1));
  }

  @Test
  void testIcannTransactionRecord_testTld_notStored() throws Exception {
    persistContactsAndHosts();
    persistResource(Tld.get("tld").asBuilder().setTldType(TldType.TEST).build());
    runFlow();
    Domain domain = reloadResourceByForeignKey();
    DomainHistory historyEntry = (DomainHistory) getHistoryEntries(domain).get(0);
    // No transaction records should be stored for test TLDs
    assertThat(historyEntry.getDomainTransactionRecords()).isEmpty();
  }

  @Test
  void testEppMetric_isSuccessfullyCreated() throws Exception {
    persistContactsAndHosts();
    runFlow();
    EppMetric eppMetric = getEppMetric();
    assertThat(eppMetric.getCommandName()).hasValue("DomainCreate");
  }

  @Test
  void testGetRenewalPriceInfo_isAnchorTenantWithoutToken_returnsNonPremiumAndNullPrice() {
    assertThat(DomainCreateFlow.getRenewalPriceInfo(true, Optional.empty()))
        .isEqualTo(RenewalPriceInfo.create(NONPREMIUM, null));
  }

  @Test
  void testGetRenewalPriceInfo_isAnchorTenantWithDefaultToken_returnsNonPremiumAndNullPrice() {
    assertThat(DomainCreateFlow.getRenewalPriceInfo(true, Optional.of(allocationToken)))
        .isEqualTo(RenewalPriceInfo.create(NONPREMIUM, null));
  }

  @Test
  void testGetRenewalPriceInfo_isNotAnchorTenantWithDefaultToken_returnsDefaultAndNullPrice() {
    assertThat(DomainCreateFlow.getRenewalPriceInfo(false, Optional.of(allocationToken)))
        .isEqualTo(RenewalPriceInfo.create(DEFAULT, null));
  }

  @Test
  void testGetRenewalPriceInfo_isNotAnchorTenantWithoutToken_returnsDefaultAndNullPrice() {
    assertThat(DomainCreateFlow.getRenewalPriceInfo(false, Optional.empty()))
        .isEqualTo(RenewalPriceInfo.create(DEFAULT, null));
  }

  @Test
  void
      testGetRenewalPriceInfo_isNotAnchorTenantWithSpecifiedInToken_returnsSpecifiedAndCreatePrice() {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 5))
                .build());
    assertThat(DomainCreateFlow.getRenewalPriceInfo(false, Optional.of(token)))
        .isEqualTo(RenewalPriceInfo.create(SPECIFIED, Money.of(USD, 5)));
  }

  @Test
  void testGetRenewalPriceInfo_isAnchorTenantWithSpecifiedStateInToken_throwsError() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DomainCreateFlow.getRenewalPriceInfo(
                    true,
                    Optional.of(
                        persistResource(
                            new AllocationToken.Builder()
                                .setToken("abc123")
                                .setTokenType(SINGLE_USE)
                                .setRenewalPriceBehavior(SPECIFIED)
                                .setRenewalPrice(Money.of(USD, 0))
                                .build()))));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Renewal price behavior cannot be SPECIFIED for anchor tenant");
  }

  @Test
  void testGetRenewalPriceInfo_withInvalidRenewalPriceBehavior_throwsError() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DomainCreateFlow.getRenewalPriceInfo(
                    true,
                    Optional.of(
                        persistResource(
                            new AllocationToken.Builder()
                                .setToken("abc123")
                                .setTokenType(SINGLE_USE)
                                .setRenewalPriceBehavior(RenewalPriceBehavior.valueOf("INVALID"))
                                .build()))));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "No enum constant"
                + " google.registry.model.billing.BillingBase.RenewalPriceBehavior.INVALID");
  }

  @Test
  void testSuccess_quietPeriod_skipTldStateCheckWithToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), token);
  }

  @Test
  void testSuccess_sunrise_skipTldCheckWithToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), token);
  }

  @Test
  void testSuccess_nonpremiumCreateToken() throws Exception {
    createTld("example");
    persistContactsAndHosts();
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRegistrationBehavior(RegistrationBehavior.NONPREMIUM_CREATE)
            .setDomainName("rich.example")
            .build());
    setEppInput(
        "domain_create_premium_allocationtoken.xml", ImmutableMap.of("YEARS", "1", "FEE", "13.00"));
    runFlowAssertResponse(loadFile("domain_create_nonpremium_token_response.xml"));
  }

  @Test
  void testFailure_quietPeriod_defaultTokenPresent() throws Exception {
    persistResource(
        new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    assertThrows(NoGeneralRegistrationsInCurrentPhaseException.class, this::runFlow);
  }

  @Test
  void testFailure_quietPeriodBeforeSunrise_trademarkedDomain() throws Exception {
    allocationToken =
        persistResource(
            allocationToken
                .asBuilder()
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .setDomainName(null)
                .build());
    // Trademarked domains using a bypass-tld-state token should fail if we're in a quiet period
    // before the sunrise period
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME, QUIET_PERIOD, clock.nowUtc().plusYears(1), START_DATE_SUNRISE))
            .build());
    persistContactsAndHosts();
    setEppInput("domain_create_allocationtoken_claims.xml");
    assertThrows(NoTrademarkedRegistrationsBeforeSunriseException.class, this::runFlow);
  }

  @Test
  void testSuccess_quietPeriodAfterSunrise_trademarkedDomain() throws Exception {
    allocationToken =
        persistResource(
            allocationToken
                .asBuilder()
                .setRegistrationBehavior(RegistrationBehavior.BYPASS_TLD_STATE)
                .setDomainName(null)
                .build());
    // Trademarked domains using a bypass-tld-state token should succeed if we're in a quiet period
    // after the sunrise period
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    QUIET_PERIOD,
                    clock.nowUtc().minusYears(1),
                    START_DATE_SUNRISE,
                    clock.nowUtc().minusMonths(1),
                    QUIET_PERIOD))
            .build());
    persistContactsAndHosts();
    setEppInput("domain_create_allocationtoken_claims.xml");
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(), allocationToken);
  }

  // ________________________________
  // Anchor tenant in quiet period before sunrise:
  // Only non-trademarked domains are allowed.
  // ________________________________
  @Test
  void testSuccess_anchorTenant_quietPeriodBeforeSunrise_nonTrademarked_viaToken()
      throws Exception {
    createTld("tld", QUIET_PERIOD);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("example.tld")
                .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
                .build());
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    persistContactsAndHosts();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), token);
  }

  @Test
  void testFailure_anchorTenant_quietPeriodBeforeSunrise_trademarked_withoutClaims_viaToken() {
    createTld("tld", QUIET_PERIOD);
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("test-validate.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "YEARS", "2"));
    persistContactsAndHosts();
    assertThrows(NoTrademarkedRegistrationsBeforeSunriseException.class, this::runFlow);
  }

  @Test
  void testFailure_anchorTenant_quietPeriodBeforeSunrise_trademarked_withClaims_viaToken() {
    createTld("tld", QUIET_PERIOD);
    persistResource(
        allocationToken
            .asBuilder()
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    setEppInput("domain_create_allocationtoken_claims.xml");
    persistContactsAndHosts();
    assertThrows(NoTrademarkedRegistrationsBeforeSunriseException.class, this::runFlow);
  }

  // ________________________________
  // Anchor tenant in sunrise:
  // Non-trademarked domains and trademarked domains with signed marks are allowed
  // ________________________________
  @Test
  void testSuccess_anchorTenant_inSunrise_nonTrademarked_viaToken() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("example.tld")
                .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), token);
  }

  @Test
  void testSuccess_anchorTenant_inSunrise_trademarked_withSignedMark_viaToken() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    clock.setTo(SMD_VALID_TIME);
    setEppInput(
        "domain_create_registration_encoded_signed_mark_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "test-validate.tld", "SMD", ENCODED_SMD));
    persistResource(
        allocationToken
            .asBuilder()
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .setDomainName("test-validate.tld")
            .build());
    persistContactsAndHosts();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(SUNRISE, ANCHOR_TENANT), allocationToken);
  }

  @Test
  void testFailure_anchorTenant_inSunrise_trademarked_withoutSignedMark_viaToken() {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        new AllocationToken.Builder()
            .setTokenType(SINGLE_USE)
            .setToken("abc123")
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    persistContactsAndHosts();
    assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
  }

  @Test
  void testFailure_anchorTenant_inSunrise_trademarked_withoutSignedMark_withClaims_viaToken() {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        allocationToken
            .asBuilder()
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    setEppInput("domain_create_allocationtoken_claims.xml");
    persistContactsAndHosts();
    assertThrows(MustHaveSignedMarksInCurrentPhaseException.class, this::runFlow);
  }

  // ________________________________
  // Anchor tenant in a post-sunrise quiet period:
  // Non-trademarked domains and trademarked domains with claims are allowed.
  // ________________________________
  @Test
  void testSuccess_anchorTenant_quietPeriodAfterSunrise_nonTrademarked_viaToken() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    QUIET_PERIOD,
                    clock.nowUtc().minusYears(1),
                    START_DATE_SUNRISE,
                    clock.nowUtc().minusMonths(1),
                    QUIET_PERIOD))
            .build());
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
                .setDomainName("example.tld")
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), token);
  }

  @Test
  void testSuccess_anchorTenant_quietPeriodAfterSunrise_trademarked_withClaims_viaToken()
      throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    QUIET_PERIOD,
                    clock.nowUtc().minusYears(1),
                    START_DATE_SUNRISE,
                    clock.nowUtc().minusMonths(1),
                    QUIET_PERIOD))
            .build());
    persistResource(
        allocationToken
            .asBuilder()
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    setEppInput("domain_create_allocationtoken_claims.xml");
    persistContactsAndHosts();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
  }

  @Test
  void testFailure_anchorTenant_quietPeriodAfterSunrise_trademarked_withoutClaims_viaToken() {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    QUIET_PERIOD,
                    clock.nowUtc().minusYears(1),
                    START_DATE_SUNRISE,
                    clock.nowUtc().minusMonths(1),
                    QUIET_PERIOD))
            .build());
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example-one.tld", "YEARS", "2"));
    assertThrows(MissingClaimsNoticeException.class, this::runFlow);
  }

  // ________________________________
  // Anchor tenant in GA:
  // Non-trademarked domains and trademarked domains with claims are allowed.
  // ________________________________
  @Test
  void testSuccess_anchorTenant_ga_nonTrademarked_viaToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
                .setDomainName("example.tld")
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), token);
  }

  @Test
  void testSuccess_anchorTenant_ga_trademarked_withClaims_viaToken() throws Exception {
    persistResource(
        allocationToken
            .asBuilder()
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    setEppInput("domain_create_allocationtoken_claims.xml");
    persistContactsAndHosts();
    runFlow();
    assertSuccessfulCreate("tld", ImmutableSet.of(ANCHOR_TENANT), allocationToken);
  }

  @Test
  void testFailure_anchorTenant_ga_trademarked_withoutClaims_viaToken() {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("example-one.tld")
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example-one.tld", "YEARS", "2"));
    assertThrows(MissingClaimsNoticeException.class, this::runFlow);
  }

  @Test
  void testFailure_anchorTenant_mismatchedName_viaToken() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRegistrationBehavior(RegistrationBehavior.ANCHOR_TENANT)
            .setDomainName("example.tld")
            .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example-one.tld", "YEARS", "2"));
    assertThrows(AllocationTokenNotValidForDomainException.class, this::runFlow);
  }

  @Test
  void testSuccess_bulkToken_addsTokenToDomain() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(BULK_PRICING)
                .setDiscountFraction(1.0)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "1"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_wildcard.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "example.tld")
                .put("CRDATE", "1999-04-03T22:00:00.0Z")
                .put("EXDATE", "2000-04-03T22:00:00.0Z")
                .build()));
    Domain domain = reloadResourceByForeignKey();
    assertThat(domain.getCurrentBulkToken()).isPresent();
    assertThat(domain.getCurrentBulkToken()).hasValue(token.createVKey());
  }

  @Test
  void testFailure_bulkToken_registrationTooLong() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(BULK_PRICING)
            .setDiscountFraction(1.0)
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setAllowedTlds(ImmutableSet.of("tld"))
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(USD, 0))
            .build());
    persistContactsAndHosts();
    setEppInput(
        "domain_create_allocationtoken.xml",
        ImmutableMap.of("DOMAIN", "example.tld", "YEARS", "2"));
    EppException thrown =
        assertThrows(BulkDomainRegisteredForTooManyYearsException.class, this::runFlow);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "The bulk token abc123 cannot be used to register names for longer than 1 year.");
  }

  @Test
  void testTieredPricingPromoResponse() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    setupDefaultTokenWithDiscount("NewRegistrar");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();

    // Fee in the result should be 24 (create cost of 13 plus renew cost of 11) even though the
    // actual cost is lower (due to the tiered pricing promo)
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "24.00")));
    // Expected cost is half off the create cost (13/2 == 6.50) plus one full-cost renew (11)
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 17.50));
  }

  @Test
  void testTieredPricingPromo_registrarNotIncluded_standardResponse() throws Exception {
    setupDefaultTokenWithDiscount("NewRegistrar");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();

    // For a registrar not included in the tiered pricing promo, costs should be 24
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "24.00")));
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 24));
  }

  @Test
  void testTieredPricingPromo_registrarIncluded_noTokenActive() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveDomain("example1.tld");

    persistResource(
        setupDefaultTokenWithDiscount("NewRegistrar")
            .asBuilder()
            .setTokenStatusTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    TokenStatus.NOT_STARTED,
                    clock.nowUtc().plusDays(1),
                    TokenStatus.VALID))
            .build());

    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12", "CURRENCY", "USD"));
    persistContactsAndHosts();

    // The token hasn't started yet, so the cost should be create (13) plus renew (11)
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "0.12", "FEE", "24.00")));
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 24));
  }

  private AllocationToken setupDefaultTokenWithDiscount() {
    return setupDefaultTokenWithDiscount("TheRegistrar");
  }

  private AllocationToken setupDefaultTokenWithDiscount(String registrarId) {
    return setupDefaultToken("bbbbb", 0.5, registrarId);
  }

  private AllocationToken setupDefaultToken(
      String token, double discountFraction, String registrarId) {
    AllocationToken allocationToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken(token)
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of(registrarId))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setDiscountFraction(discountFraction)
                .build());
    Tld tld = Tld.get("tld");
    persistResource(
        tld.asBuilder()
            .setDefaultPromoTokens(
                ImmutableList.<VKey<AllocationToken>>builder()
                    .addAll(tld.getDefaultPromoTokens())
                    .add(allocationToken.createVKey())
                    .build())
            .build());
    return allocationToken;
  }
}
