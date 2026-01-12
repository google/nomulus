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
import static google.registry.flows.FlowTestCase.UserPrivileges.SUPERUSER;
import static google.registry.model.billing.BillingBase.Flag.ANCHOR_TENANT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.pricing.PricingEngineProxy.isDomainPremium;
import static google.registry.testing.DatabaseHelper.assertBillingEvents;
import static google.registry.testing.DatabaseHelper.assertDomainDnsRequests;
import static google.registry.testing.DatabaseHelper.assertPollMessagesForResource;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.getHistoryEntries;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.loadRegistrar;
import static google.registry.testing.DatabaseHelper.persistActiveContact;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.DomainSubject.assertAboutDomains;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager.UndeclaredServiceExtensionException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionMultipleMatchesException;
import google.registry.flows.domain.DomainFlowUtils.FeeDescriptionParseException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.model.billing.BillingBase;
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
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.poll.PollMessage;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.testing.DatabaseHelper;
import google.registry.tmch.LordnTaskUtils.LordnPhase;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainCreateFlow} that use the old fee extensions (0.6, 0.11, 0.12). */
public class DomainCreateFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainCreateFlow> {

  private static final ImmutableMap<String, String> FEE_06_MAP =
      ImmutableMap.of("FEE_VERSION", "fee-0.6", "FEE_NS", "fee", "CURRENCY", "USD", "FEE", "15.00");
  private static final ImmutableMap<String, String> FEE_11_MAP =
      ImmutableMap.of(
          "FEE_VERSION", "fee-0.11", "FEE_NS", "fee", "CURRENCY", "USD", "FEE", "15.00");
  private static final ImmutableMap<String, String> FEE_12_MAP =
      ImmutableMap.of(
          "FEE_VERSION", "fee-0.12", "FEE_NS", "fee", "CURRENCY", "USD", "FEE", "15.00");

  private static final String CLAIMS_KEY = "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001";

  private AllocationToken allocationToken;

  @BeforeEach
  void beforeEachDomainCreateFlowOldFeeExtensionsTest() {
    setEppInput("domain_create.xml", ImmutableMap.of("DOMAIN", "example.tld"));
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z").minus(Duration.millis(2)));
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
  }

  private void persistContactsAndHosts() {
    persistContactsAndHosts("net"); // domain_create.xml uses hosts on "net".
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

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions)
      throws Exception {
    assertMutatingFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, loadFile(responseXmlFile, substitutions));
    assertSuccessfulCreate(domainTld, ImmutableSet.of(), 24);
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

  private void assertSuccessfulCreate(String domainTld, ImmutableSet<Flag> expectedBillingFlags)
      throws Exception {
    assertSuccessfulCreate(domainTld, expectedBillingFlags, null, 24, null);
  }

  private void assertSuccessfulCreate(
      String domainTld, ImmutableSet<Flag> expectedBillingFlags, double createCost)
      throws Exception {
    assertSuccessfulCreate(domainTld, expectedBillingFlags, null, createCost, null);
  }

  private void assertSuccessfulCreate(
      String domainTld, ImmutableSet<Flag> expectedBillingFlags, AllocationToken token)
      throws Exception {
    assertSuccessfulCreate(domainTld, expectedBillingFlags, token, 24, null);
  }

  private void assertSuccessfulCreate(
      String domainTld,
      ImmutableSet<Flag> expectedBillingFlags,
      AllocationToken token,
      double createCost)
      throws Exception {
    assertSuccessfulCreate(domainTld, expectedBillingFlags, token, createCost, null);
  }

  private void assertSuccessfulCreate(
      String domainTld,
      ImmutableSet<Flag> expectedBillingFlags,
      @Nullable AllocationToken token,
      double createCost,
      @Nullable Integer specifiedRenewCost)
      throws Exception {
    Domain domain = reloadResourceByForeignKey();

    boolean isAnchorTenant = expectedBillingFlags.contains(ANCHOR_TENANT);
    // Set up the creation cost.
    boolean isDomainPremium = isDomainPremium(getUniqueIdFromCommand(), clock.nowUtc());

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
    RenewalPriceBehavior expectedRenewalPriceBehavior =
        isAnchorTenant
            ? RenewalPriceBehavior.NONPREMIUM
            : Optional.ofNullable(token)
                .map(AllocationToken::getRenewalPriceBehavior)
                .orElse(RenewalPriceBehavior.DEFAULT);
    // There should be one bill for the create and one for the recurrence autorenew event.
    BillingEvent createBillingEvent =
        new BillingEvent.Builder()
            .setReason(Reason.CREATE)
            .setTargetId(getUniqueIdFromCommand())
            .setRegistrarId("TheRegistrar")
            .setCost(Money.of(USD, BigDecimal.valueOf(createCost)))
            .setPeriodYears(2)
            .setEventTime(clock.nowUtc())
            .setBillingTime(billingTime)
            .setFlags(expectedBillingFlags)
            .setDomainHistory(historyEntry)
            .setAllocationToken(Optional.ofNullable(token).map(t -> t.createVKey()).orElse(null))
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
            .setRenewalPriceBehavior(expectedRenewalPriceBehavior)
            .setRenewalPrice(
                Optional.ofNullable(specifiedRenewCost)
                    .map(r -> Money.of(USD, BigDecimal.valueOf(r)))
                    .orElse(null))
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

  @Test
  void testFailure_wrongFeeAmount_v06() {
    setEppInput("domain_create_fee.xml", FEE_06_MAP);
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
  void testFailure_wrongFeeAmount_v11() {
    setEppInput("domain_create_fee.xml", FEE_11_MAP);
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
  void testFailure_wrongFeeAmount_v12() {
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
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
    setEppInput("domain_create_fee.xml", FEE_06_MAP);
    persistContactsAndHosts();
    // $15 is 50% off the first year registration ($8) and 0% 0ff the 2nd year (renewal at $11)
    runFlowAssertResponse(loadFile("domain_create_response_fee.xml", FEE_06_MAP));
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
    setEppInput("domain_create_fee.xml", FEE_11_MAP);
    persistContactsAndHosts();
    // $12 is equal to 50% off the first year registration and 0% 0ff the 2nd year
    runFlowAssertResponse(loadFile("domain_create_response_fee.xml", FEE_11_MAP));
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
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();
    // $12 is equal to 50% off the first year registration and 0% 0ff the 2nd year
    runFlowAssertResponse(loadFile("domain_create_response_fee.xml", FEE_12_MAP));
  }

  @Test
  void testFailure_omitFeeExtensionOnLogin_v06() {
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", FEE_06_MAP);
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
    setEppInput("domain_create_fee.xml", FEE_11_MAP);
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
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UndeclaredServiceExtensionException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_eapFeeApplied_v06() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        new ImmutableMap.Builder<String, String>()
            .putAll(FEE_06_MAP)
            .put("DESCRIPTION_1", "create")
            .put("DESCRIPTION_2", "Early Access Period")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest("tld", "domain_create_response_eap_fee.xml", FEE_06_MAP);
  }

  @Test
  void testSuccess_eapFeeApplied_v11() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        new ImmutableMap.Builder<String, String>()
            .putAll(FEE_11_MAP)
            .put("DESCRIPTION_1", "create")
            .put("DESCRIPTION_2", "Early Access Period")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest("tld", "domain_create_response_eap_fee.xml", FEE_11_MAP);
  }

  @Test
  void testSuccess_eapFeeApplied_v12() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        new ImmutableMap.Builder<String, String>()
            .putAll(FEE_12_MAP)
            .put("DESCRIPTION_1", "create")
            .put("DESCRIPTION_2", "Early Access Period")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest("tld", "domain_create_response_eap_fee.xml", FEE_12_MAP);
  }

  @Test
  void testFailure_feeGivenInWrongScale_v06() {
    setEppInput("domain_create_fee_bad_scale.xml", FEE_06_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v11() {
    setEppInput("domain_create_fee_bad_scale.xml", FEE_11_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_feeGivenInWrongScale_v12() {
    setEppInput("domain_create_fee_bad_scale.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyValueScaleException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v06() {
    setEppInput("domain_create_fee_applied.xml", FEE_06_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v11() {
    setEppInput("domain_create_fee_applied.xml", FEE_11_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_appliedFee_v12() {
    setEppInput("domain_create_fee_applied.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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
    setEppInput("domain_create_fee.xml", FEE_06_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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
    setEppInput("domain_create_fee.xml", FEE_11_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(FeesMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v06() {
    setEppInput(
        "domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "fee-0.6", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v11() {
    setEppInput(
        "domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "fee-0.11", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongCurrency_v12() {
    setEppInput(
        "domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "fee-0.12", "CURRENCY", "EUR"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v06() {
    setEppInput("domain_create_fee_grace_period.xml", FEE_06_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v11() {
    setEppInput("domain_create_fee_grace_period.xml", FEE_11_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_gracePeriodFee_v12() {
    setEppInput("domain_create_fee_grace_period.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", FEE_06_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", FEE_11_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.11", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", FEE_12_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE", "24.00"));
  }

  @Test
  void testFailure_refundableFee_v06() {
    setEppInput("domain_create_fee_refundable.xml", FEE_06_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v11() {
    setEppInput("domain_create_fee_refundable.xml", FEE_11_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_refundableFee_v12() {
    setEppInput("domain_create_fee_refundable.xml", FEE_12_MAP);
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnsupportedFeeAttributeException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_create_fee.xml", FEE_06_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_create_fee.xml", FEE_11_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.11", "FEE", "24.00"));
  }

  @Test
  void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld",
        "domain_create_response_fee.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE", "24.00"));
  }

  @Test
  void testFailure_eapFee_description_multipleMatch_v06() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "fee-0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "renew transfer"));
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeeDescriptionMultipleMatchesException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("RENEW, TRANSFER");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_unknownCurrency_v12() {
    setEppInput(
        "domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "fee-0.12", "CURRENCY", "BAD"));
    persistContactsAndHosts();
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testTieredPricingPromoResponse_v12() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    setupDefaultTokenWithDiscount("NewRegistrar");
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();

    // Fee in the result should be 24 (create cost of 13 plus renew cost of 11) even though the
    // actual cost is lower (due to the tiered pricing promo)
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE", "24.00")));
    // Expected cost is half off the create cost (13/2 == 6.50) plus one full-cost renew (11)
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 17.50));
  }

  @Test
  void testSuccess_eapFee_multipleEAPfees_doNotAddToExpectedValue_v06() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "fee-0.6")
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
  void testFailure_eapFee_description_swapped_v06() {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "fee-0.6",
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
  void testSuccess_doesNotApplyNonPremiumDefaultTokenToPremiumName_v12() throws Exception {
    persistContactsAndHosts();
    createTld("example");
    persistResource(
        setupDefaultTokenWithDiscount()
            .asBuilder()
            .setAllowedTlds(ImmutableSet.of("example"))
            .build());
    setEppInput("domain_create_premium.xml", FEE_12_MAP);
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of(
                "FEE_VERSION", "fee-0.12", "EXDATE", "2001-04-03T22:00:00.0Z", "FEE", "200.00")));
    assertSuccessfulCreate("example", ImmutableSet.of(), 200);
  }

  @Test
  void testSuccess_superuserOverridesPremiumNameBlock_v12() throws Exception {
    createTld("example");
    setEppInput("domain_create_premium.xml", FEE_12_MAP);
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        SUPERUSER,
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of(
                "FEE_VERSION", "fee-0.12", "EXDATE", "2001-04-03T22:00:00.0Z", "FEE", "200.00")));
    assertSuccessfulCreate("example", ImmutableSet.of(), 200);
  }

  @Test
  void testFailure_eapFee_combined_v06() {
    setEppInput("domain_create_eap_combined_fee.xml", FEE_06_MAP);
    persistContactsAndHosts();
    setEapForTld("tld");
    EppException thrown = assertThrows(FeeDescriptionParseException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains("No fee description");
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_nonpremiumCreateToken_v06() throws Exception {
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
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "YEARS", "1", "FEE", "13.00"));
    runFlowAssertResponse(loadFile("domain_create_nonpremium_token_response.xml", FEE_06_MAP));
  }

  @Test
  void testSuccess_eapFee_fullDescription_includingArbitraryExpiryTime_v06() throws Exception {
    setEppInput(
        "domain_create_eap_fee.xml",
        ImmutableMap.of(
            "FEE_VERSION",
            "fee-0.6",
            "DESCRIPTION_1",
            "create",
            "DESCRIPTION_2",
            "Early Access Period, fee expires: 2022-03-01T00:00:00.000Z"));
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest("tld", "domain_create_response_eap_fee.xml", FEE_06_MAP);
  }

  @Test
  void testSuccess_allocationToken_multiYearDiscount_worksForPremiums_v06() throws Exception {
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
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "YEARS", "3", "FEE", "104.00"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of(
                "FEE_VERSION", "fee-0.6", "EXDATE", "2002-04-03T22:00:00.0Z", "FEE", "104.00")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("rich.example");
    // 1yr @ $100 + 2yrs @ $100 * (1 - 0.98) = $104
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 104.00));
  }

  @Test
  void testSuccess_eapFee_multipleEAPfees_addToExpectedValue_v06() throws Exception {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "fee-0.6")
            .put("DESCRIPTION_1", "create")
            .put("FEE_1", "24")
            .put("DESCRIPTION_2", "Early Access Period")
            .put("FEE_2", "55")
            .put("DESCRIPTION_3", "Early Access Period")
            .put("FEE_3", "45")
            .build());
    persistContactsAndHosts();
    setEapForTld("tld");
    doSuccessfulTest("tld", "domain_create_response_eap_fee.xml", FEE_06_MAP);
  }

  @Test
  void testFailure_eapFee_totalAmountNotMatched_v06() {
    setEppInput(
        "domain_create_extra_fees.xml",
        new ImmutableMap.Builder<String, String>()
            .put("FEE_VERSION", "fee-0.6")
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
  void testSuccess_premiumAndEap_v06() throws Exception {
    createTld("example");
    setEppInput("domain_create_premium_eap.xml", FEE_06_MAP);
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
    assertMutatingFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.NORMAL,
        loadFile("domain_create_response_premium_eap.xml", FEE_06_MAP));
    assertSuccessfulCreate("example", ImmutableSet.of(), 200);
    assertNoLordn();
  }

  @Test
  void testFailure_premiumBlocked_v06() {
    createTld("example");
    setEppInput("domain_create_premium.xml", FEE_06_MAP);
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(loadRegistrar("TheRegistrar").asBuilder().setBlockPremiumNames(true).build());
    EppException thrown = assertThrows(PremiumNameBlockedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_allocationToken_singleYearDiscount_worksForPremiums_v06() throws Exception {
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
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "YEARS", "3", "FEE", "204.44"));
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_premium.xml",
            ImmutableMap.of(
                "FEE_VERSION", "fee-0.6", "EXDATE", "2002-04-03T22:00:00.0Z", "FEE", "204.44")));
    BillingEvent billingEvent =
        Iterables.getOnlyElement(DatabaseHelper.loadAllOf(BillingEvent.class));
    assertThat(billingEvent.getTargetId()).isEqualTo("rich.example");
    // 2yrs @ $100 + 1yr @ $100 * (1 - 0.95555) = $204.44
    assertThat(billingEvent.getCost()).isEqualTo(Money.of(USD, 204.44));
  }

  @Test
  void testTieredPricingPromo_registrarIncluded_noTokenActive_v12() throws Exception {
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

    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();

    // The token hasn't started yet, so the cost should be create (13) plus renew (11)
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE", "24.00")));
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 24));
  }

  @Test
  void testTieredPricingPromo_registrarNotIncluded_standardResponse_v12() throws Exception {
    setupDefaultTokenWithDiscount("NewRegistrar");
    setEppInput("domain_create_fee.xml", FEE_12_MAP);
    persistContactsAndHosts();

    // For a registrar not included in the tiered pricing promo, costs should be 24
    runFlowAssertResponse(
        loadFile(
            "domain_create_response_fee.xml",
            ImmutableMap.of("FEE_VERSION", "fee-0.12", "FEE", "24.00")));
    assertThat(Iterables.getOnlyElement(loadAllOf(BillingEvent.class)).getCost())
        .isEqualTo(Money.of(USD, 24));
  }

  @Test
  void testSuccess_nonAnchorTenant_nonPremiumRenewal_v06() throws Exception {
    createTld("example");
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("rich.example")
                .setRenewalPriceBehavior(NONPREMIUM)
                .build());
    persistContactsAndHosts();
    // Creation is still $100 but it'll create a NONPREMIUM renewal
    setEppInput(
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "YEARS", "2", "FEE", "111.00"));
    runFlow();
    assertSuccessfulCreate("example", ImmutableSet.of(), token, 111);
  }

  @Test
  void testSuccess_specifiedRenewalPriceToken_specifiedRecurrencePrice_v06() throws Exception {
    createTld("example");
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(SINGLE_USE)
                .setDomainName("rich.example")
                .setRenewalPriceBehavior(SPECIFIED)
                .setRenewalPrice(Money.of(USD, 1))
                .build());
    persistContactsAndHosts();
    // Creation is still $100 but it'll create a $1 renewal
    setEppInput(
        "domain_create_premium_allocationtoken.xml",
        ImmutableMap.of("FEE_VERSION", "fee-0.6", "YEARS", "2", "FEE", "101.00"));
    runFlow();
    assertSuccessfulCreate("example", ImmutableSet.of(), token, 101, 1);
  }
}
