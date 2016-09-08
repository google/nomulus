// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.fee.Fee.FEE_EXTENSION_URIS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.pricing.PricingEngineProxy.getPricesForDomainName;
import static google.registry.testing.DatastoreHelper.assertBillingEvents;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.deleteTld;
import static google.registry.testing.DatastoreHelper.getHistoryEntries;
import static google.registry.testing.DatastoreHelper.newContactResource;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.newDomainResource;
import static google.registry.testing.DatastoreHelper.newHostResource;
import static google.registry.testing.DatastoreHelper.persistActiveContact;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistActiveDomainApplication;
import static google.registry.testing.DatastoreHelper.persistActiveHost;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.DomainResourceSubject.assertAboutDomains;
import static google.registry.testing.TaskQueueHelper.assertDnsTasksEnqueued;
import static google.registry.testing.TaskQueueHelper.assertNoDnsTasksEnqueued;
import static google.registry.testing.TestDataHelper.loadFileWithSubstitutions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.EUR;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException.UnimplementedExtensionException;
import google.registry.flows.EppRequestSource;
import google.registry.flows.LoggedInFlow.UndeclaredServiceExtensionException;
import google.registry.flows.ResourceCreateFlow.ResourceAlreadyExistsException;
import google.registry.flows.ResourceCreateOrMutateFlow.OnlyToolCanPassMetadataException;
import google.registry.flows.ResourceFlow.BadCommandForRegistryPhaseException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.domain.BaseDomainCreateFlow.AcceptedTooLongAgoException;
import google.registry.flows.domain.BaseDomainCreateFlow.ClaimsPeriodEndedException;
import google.registry.flows.domain.BaseDomainCreateFlow.ExpiredClaimException;
import google.registry.flows.domain.BaseDomainCreateFlow.InvalidTcnIdChecksumException;
import google.registry.flows.domain.BaseDomainCreateFlow.InvalidTrademarkValidatorException;
import google.registry.flows.domain.BaseDomainCreateFlow.MalformedTcnIdException;
import google.registry.flows.domain.BaseDomainCreateFlow.MaxSigLifeNotSupportedException;
import google.registry.flows.domain.BaseDomainCreateFlow.MissingClaimsNoticeException;
import google.registry.flows.domain.BaseDomainCreateFlow.UnexpectedClaimsNoticeException;
import google.registry.flows.domain.BaseDomainCreateFlow.UnsupportedMarkTypeException;
import google.registry.flows.domain.DomainCreateFlow.DomainHasOpenApplicationsException;
import google.registry.flows.domain.DomainCreateFlow.NoGeneralRegistrationsInCurrentPhaseException;
import google.registry.flows.domain.DomainCreateFlow.SignedMarksNotAcceptedInCurrentPhaseException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyValueScaleException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainReservedException;
import google.registry.flows.domain.DomainFlowUtils.DuplicateContactForRoleException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.FeesMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeesRequiredForPremiumNameException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourceInPendingDeleteProhibitsOperationException;
import google.registry.flows.domain.DomainFlowUtils.LinkedResourcesDoNotExistException;
import google.registry.flows.domain.DomainFlowUtils.MissingAdminContactException;
import google.registry.flows.domain.DomainFlowUtils.MissingContactTypeException;
import google.registry.flows.domain.DomainFlowUtils.MissingRegistrantException;
import google.registry.flows.domain.DomainFlowUtils.MissingTechnicalContactException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.NameserversNotSpecifiedException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.PremiumNameBlockedException;
import google.registry.flows.domain.DomainFlowUtils.RegistrantNotAllowedException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TooManyDsRecordsException;
import google.registry.flows.domain.DomainFlowUtils.TooManyNameserversException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.UnsupportedFeeAttributeException;
import google.registry.model.billing.BillingEvent;
import google.registry.model.billing.BillingEvent.Flag;
import google.registry.model.billing.BillingEvent.Reason;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.LrpToken;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchNotice;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DelegationSignerData;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.reporting.HistoryEntry;
import google.registry.testing.DatastoreHelper;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DomainCreateFlow}. */
public class DomainCreateFlowTest extends ResourceFlowTestCase<DomainCreateFlow, DomainResource> {

  private static final String CLAIMS_KEY = "2013041500/2/6/9/rJ1NrDO92vDsAzf7EQzgjX4R0000000001";

  public DomainCreateFlowTest() {
    setEppInput("domain_create.xml");
    clock.setTo(DateTime.parse("1999-04-03T22:00:00.0Z").minus(1));
  }

  @Before
  public void initCreateTest() throws Exception {
    createTld("tld");
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(persistReservedList(
            "tld-reserved",
            "reserved,FULLY_BLOCKED",
            "anchor,RESERVED_FOR_ANCHOR_TENANT,2fooBAR"))
        .build());
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
  }

  /**
   * Create host and contact entries for testing.
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
    persistContactsAndHosts("net");  // domain_create.xml uses hosts on "net".
  }

  private void assertSuccessfulCreate(String domainTld, boolean isAnchorTenant) throws Exception {
    DomainResource domain = reloadResourceByUniqueId();

    // Calculate the total cost.
    Money cost = getPricesForDomainName(getUniqueIdFromCommand(), clock.nowUtc()).isPremium()
        ? Money.of(USD, 200)
        : Money.of(USD, 26);
    Money eapFee = Registry.get(domainTld).getEapFeeFor(clock.nowUtc()).getCost();

    DateTime billingTime = isAnchorTenant
        ? clock.nowUtc().plus(Registry.get(domainTld).getAnchorTenantAddGracePeriodLength())
        : clock.nowUtc().plus(Registry.get(domainTld).getAddGracePeriodLength());
    ImmutableSet<BillingEvent.Flag> billingFlags = isAnchorTenant
        ? ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT)
        : ImmutableSet.<BillingEvent.Flag>of();
    HistoryEntry historyEntry = getHistoryEntries(domain).get(0);
    assertAboutDomains().that(domain)
        .hasRegistrationExpirationTime(
            ofy().load().key(domain.getAutorenewBillingEvent()).now().getEventTime()).and()
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE).and()
        .hasPeriodYears(2);
    // There should be one bill for the create and one for the recurring autorenew event.
    BillingEvent.OneTime createBillingEvent =
        new BillingEvent.OneTime.Builder()
            .setReason(Reason.CREATE)
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("TheRegistrar")
            .setCost(cost)
            .setPeriodYears(2)
            .setEventTime(clock.nowUtc())
            .setBillingTime(billingTime)
            .setFlags(billingFlags)
            .setParent(historyEntry)
            .build();

    BillingEvent.Recurring renewBillingEvent =
        new BillingEvent.Recurring.Builder()
            .setReason(Reason.RENEW)
            .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
            .setTargetId(getUniqueIdFromCommand())
            .setClientId("TheRegistrar")
            .setEventTime(domain.getRegistrationExpirationTime())
            .setRecurrenceEndTime(END_OF_TIME)
            .setParent(historyEntry)
            .build();

    ImmutableSet<BillingEvent> billingEvents = ImmutableSet.of(
        createBillingEvent, renewBillingEvent);

    // If EAP is applied, a billing event for EAP should be present.
    if (!eapFee.isZero()) {
      ImmutableSet<BillingEvent.Flag> eapFlags =
          isAnchorTenant
              ? ImmutableSet.of(BillingEvent.Flag.ANCHOR_TENANT, BillingEvent.Flag.EAP)
              : ImmutableSet.of(BillingEvent.Flag.EAP);
      BillingEvent.OneTime eapBillingEvent =
          new BillingEvent.OneTime.Builder()
              .setReason(Reason.CREATE)
              .setTargetId(getUniqueIdFromCommand())
              .setClientId("TheRegistrar")
              .setCost(eapFee)
              .setPeriodYears(2)
              .setEventTime(clock.nowUtc())
              .setBillingTime(billingTime)
              .setFlags(eapFlags)
              .setParent(historyEntry)
              .build();
      billingEvents = ImmutableSet.<BillingEvent>builder()
          .addAll(billingEvents)
          .add(eapBillingEvent)
          .build();
    }
    assertBillingEvents(billingEvents);
    
    assertGracePeriods(
        domain.getGracePeriods(),
        ImmutableMap.of(
            GracePeriod.create(
                GracePeriodStatus.ADD,
                billingTime,
                "TheRegistrar",
                null),
            createBillingEvent));
    assertDnsTasksEnqueued(getUniqueIdFromCommand());
    assertEppResourceIndexEntityFor(domain);
  }

  private void assertNoLordn() throws Exception {
    // TODO(b/26161326): Assert tasks NOT enqueued.
    assertAboutDomains().that(reloadResourceByUniqueId())
        .hasSmdId(null).and()
        .hasLaunchNotice(null);
  }

  private void assertSunriseLordn() throws Exception {
    // TODO(b/26161326): Assert tasks enqueued.
    assertAboutDomains().that(reloadResourceByUniqueId())
        .hasSmdId("0000001761376042759136-65535").and()
        .hasLaunchNotice(null);
  }

  private void assertClaimsLordn() throws Exception {
    // TODO(b/26161326): Assert tasks enqueued.
    assertAboutDomains().that(reloadResourceByUniqueId())
        .hasSmdId(null).and()
        .hasLaunchNotice(LaunchNotice.create(
            "370d0b7c9223372036854775807",
            "tmch",
            DateTime.parse("2010-08-16T09:00:00.0Z"),
            DateTime.parse("2009-08-16T09:00:00.0Z")));
  }

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      UserPrivileges userPrivileges) throws Exception {
    doSuccessfulTest(domainTld, responseXmlFile, userPrivileges, ImmutableMap.<String, String>of());
  }

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      UserPrivileges userPrivileges,
      Map<String, String> substitutions) throws Exception {
    assertTransactionalFlow(true);
    runFlowAssertResponse(
        CommitMode.LIVE, userPrivileges, readFile(responseXmlFile, substitutions));
    assertSuccessfulCreate(domainTld, false);
    assertNoLordn();
  }

  private void doSuccessfulTest(
      String domainTld,
      String responseXmlFile,
      Map<String, String> substitutions) throws Exception {
    doSuccessfulTest(domainTld, responseXmlFile, UserPrivileges.NORMAL, substitutions);
  }

  private void doSuccessfulTest(String domainTld, String responseXmlFile) throws Exception {
    doSuccessfulTest(domainTld, responseXmlFile, UserPrivileges.NORMAL);
  }

  private void doSuccessfulTest(String domainTld) throws Exception {
    doSuccessfulTest(domainTld, "domain_create_response.xml");
  }

  private void doSuccessfulTest() throws Exception {
    doSuccessfulTest("tld");
  }

  @Test
  public void testDryRun() throws Exception {
    persistContactsAndHosts();
    dryRunFlowAssertResponse(readFile("domain_create_response.xml"));
  }

  @Test
  public void testSuccess_neverExisted() throws Exception {
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_multipartTld() throws Exception {
    createTld("foo.tld");
    setEppInput("domain_create_with_tld.xml", ImmutableMap.of("TLD", "foo.tld"));
    persistContactsAndHosts("foo.tld");
    assertTransactionalFlow(true);
    String expectedResponseXml = loadFileWithSubstitutions(
        DomainCreateFlowTest.class,
        "domain_create_response_wildcard.xml",
        ImmutableMap.of("DOMAIN", "example.foo.tld"));
    runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, expectedResponseXml);
    assertSuccessfulCreate("foo.tld", false);
    assertNoLordn();
  }

  @Test
  public void testSuccess_anchorTenantViaExtension() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_anchor_tenant.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertSuccessfulCreate("tld", true);
    assertNoLordn();
  }

  @Test
  public void testSuccess_fee_v06() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_fee_v11() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_fee_v12() throws Exception {
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v06() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v11() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_fee_withDefaultAttributes_v12() throws Exception {
    setEppInput("domain_create_fee_defaults.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    doSuccessfulTest(
        "tld", "domain_create_response_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testFailure_refundableFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_refundableFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_refundableFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_refundable.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_gracePeriodFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_gracePeriodFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_gracePeriodFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_grace_period.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_appliedFee_v06() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_appliedFee_v11() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_appliedFee_v12() throws Exception {
    thrown.expect(UnsupportedFeeAttributeException.class);
    setEppInput("domain_create_fee_applied.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_metadata() throws Exception {
    eppRequestSource = EppRequestSource.TOOL;
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
    assertAboutDomains().that(reloadResourceByUniqueId())
        .hasOnlyOneHistoryEntryWhich()
        .hasType(HistoryEntry.Type.DOMAIN_CREATE).and()
        .hasMetadataReason("domain-create-test").and()
        .hasMetadataRequestedByRegistrar(false);
  }

  @Test
  public void testFailure_metadataNotFromTool() throws Exception {
    thrown.expect(OnlyToolCanPassMetadataException.class);
    setEppInput("domain_create_metadata.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_premium() throws Exception {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    doSuccessfulTest("example", "domain_create_response_premium.xml");
  }

  /**
   * Test fix for a bug where we were looking at the length of the unicode string but indexing
   * into the punycode string. In rare cases (3 and 4 letter labels) this would cause us to think
   * there was a trailing dash in the domain name and fail to create it.
   */
  @Test
  public void testSuccess_unicodeLengthBug() throws Exception {
    createTld("xn--q9jyb4c");
    persistContactsAndHosts("net");
    eppLoader.replaceAll("example.tld", "osx.xn--q9jyb4c");
    runFlow();
  }

  @Test
  public void testSuccess_nonDefaultAddGracePeriod() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setAddGracePeriodLength(Duration.standardMinutes(6))
        .build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_existedButWasDeleted() throws Exception {
    persistContactsAndHosts();
    persistDeletedDomain(getUniqueIdFromCommand(), clock.nowUtc());
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_maxNumberOfNameservers() throws Exception {
    setEppInput("domain_create_13_nameservers.xml");
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_secDns() throws Exception {
    setEppInput("domain_create_dsdata_no_maxsiglife.xml");
    persistContactsAndHosts("tld");  // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    assertAboutDomains().that(reloadResourceByUniqueId()).hasExactlyDsData(
        DelegationSignerData.create(12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC")));
  }

  @Test
  public void testSuccess_secDnsMaxRecords() throws Exception {
    setEppInput("domain_create_dsdata_8_records.xml");
    persistContactsAndHosts("tld");  // For some reason this sample uses "tld".
    doSuccessfulTest("tld");
    assertAboutDomains().that(reloadResourceByUniqueId()).hasNumDsData(8);
  }

  @Test
  public void testSuccess_idn() throws Exception {
    createTld("xn--unup4y");
    setEppInput("domain_create_idn_zh.xml");
    persistContactsAndHosts("net");
    runFlowAssertResponse(readFile("domain_create_response_idn_zh.xml"));
    assertSuccessfulCreate("xn--unup4y", false);
    assertDnsTasksEnqueued("xn--15qt0w.xn--unup4y");
  }

  @Test
  public void testSuccess_noNameserversOrDsData() throws Exception {
    setEppInput("domain_create_no_hosts_or_dsdata.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertNoDnsTasksEnqueued();
  }

  @Test
  public void testSuccess_periodNotSpecified() throws Exception {
    setEppInput("domain_create_missing_period.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"),
        "epp.response.resData.creData.exDate");  // Ignore expiration date; we verify it below
    assertAboutDomains().that(reloadResourceByUniqueId())
        .hasRegistrationExpirationTime(clock.nowUtc().plusYears(1));
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testFailure_periodInMonths() throws Exception {
    thrown.expect(BadPeriodUnitException.class);
    setEppInput("domain_create_months.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_claimsNotice() throws Exception {
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", false);
    assertDnsTasksEnqueued("example-one.tld");
    assertClaimsLordn();
  }

  @Test
  public void testSuccess_noClaimsNotice_forClaimsListName_afterClaimsPeriodEnd() throws Exception {
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    setEppInput("domain_create.xml");
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setClaimsPeriodEnd(clock.nowUtc())
        .build());
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertSuccessfulCreate("tld", false);
    assertDnsTasksEnqueued("example.tld");
  }

  @Test
  public void testFailure_missingClaimsNotice() throws Exception {
    thrown.expect(MissingClaimsNoticeException.class);
    persistClaimsList(ImmutableMap.of("example", CLAIMS_KEY));
    setEppInput("domain_create.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_claimsNoticeProvided_nameNotOnClaimsList() throws Exception {
    thrown.expect(UnexpectedClaimsNoticeException.class);
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.<String, String>of());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_claimsNoticeProvided_claimsPeriodEnded() throws Exception {
    thrown.expect(ClaimsPeriodEndedException.class);
    setEppInput("domain_create_claim_notice.xml");
    persistClaimsList(ImmutableMap.of("example-one", CLAIMS_KEY));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setClaimsPeriodEnd(clock.nowUtc())
        .build());
    runFlow();
  }

  @Test
  public void testFailure_tooManyNameservers() throws Exception {
    thrown.expect(TooManyNameserversException.class);
    setEppInput("domain_create_14_nameservers.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_secDnsMaxSigLife() throws Exception {
    thrown.expect(MaxSigLifeNotSupportedException.class);
    setEppInput("domain_create_dsdata.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_secDnsTooManyDsRecords() throws Exception {
    thrown.expect(TooManyDsRecordsException.class);
    setEppInput("domain_create_dsdata_9_records.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongExtension() throws Exception {
    thrown.expect(UnimplementedExtensionException.class);
    setEppInput("domain_create_wrong_extension.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeAmount_v06() throws Exception {
    thrown.expect(FeesMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeAmount_v11() throws Exception {
    thrown.expect(FeesMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongFeeAmount_v12() throws Exception {
    thrown.expect(FeesMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(Registry.get("tld").asBuilder().setCreateBillingCost(Money.of(USD, 20)).build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v06() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v11() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_wrongCurrency_v12() throws Exception {
    thrown.expect(CurrencyUnitMismatchException.class);
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistResource(Registry.get("tld").asBuilder()
        .setCurrency(CurrencyUnit.EUR)
        .setCreateBillingCost(Money.of(EUR, 13))
        .setRestoreBillingCost(Money.of(EUR, 11))
        .setRenewBillingCostTransitions(ImmutableSortedMap.of(START_OF_TIME, Money.of(EUR, 7)))
        .setEapFeeSchedule(ImmutableSortedMap.of(START_OF_TIME, Money.zero(EUR)))
        .setServerStatusChangeBillingCost(Money.of(EUR, 19))
        .build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_alreadyExists() throws Exception {
    // This fails fast and throws DomainAlreadyExistsException from init() as a special case.
    thrown.expect(
        ResourceAlreadyExistsException.class,
        String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
    persistContactsAndHosts();
    persistActiveDomain(getUniqueIdFromCommand());
    try {
      runFlow();
    } catch (ResourceAlreadyExistsException e) {
      assertThat(e.isFailfast()).isTrue();
      throw e;
    }
  }

  /**
   * There is special logic that disallows a failfast for domains in add grace period and sunrush
   * add grace period, so make sure that they fail anyways in the actual flow.
   */
  private void doNonFailFastAlreadyExistsTest(GracePeriodStatus gracePeriodStatus)
      throws Exception {
    // This doesn't fail fast, so it throws the regular ResourceAlreadyExistsException from run().
    thrown.expect(
        ResourceAlreadyExistsException.class,
        String.format("Object with given ID (%s) already exists", getUniqueIdFromCommand()));
    persistContactsAndHosts();
    persistResource(newDomainResource(getUniqueIdFromCommand()).asBuilder()
        .addGracePeriod(GracePeriod.create(gracePeriodStatus, END_OF_TIME, "", null))
        .build());
    try {
      runFlow();
    } catch (ResourceAlreadyExistsException e) {
      assertThat(e.isFailfast()).isFalse();
      throw e;
    }
  }

  @Test
  public void testFailure_alreadyExists_addGracePeriod() throws Exception {
    doNonFailFastAlreadyExistsTest(GracePeriodStatus.ADD);
  }

  @Test
  public void testFailure_alreadyExists_sunrushAddGracePeriod() throws Exception {
    doNonFailFastAlreadyExistsTest(GracePeriodStatus.SUNRUSH_ADD);
  }

  @Test
  public void testFailure_reserved() throws Exception {
    thrown.expect(DomainReservedException.class);
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_anchorTenantViaAuthCode_wrongAuthCode() throws Exception {
    thrown.expect(DomainReservedException.class);
    setEppInput("domain_create_anchor_wrong_authcode.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_anchorTenantViaAuthCode_matchingLrpToken() throws Exception {
    // This is definitely a corner case, as (without superuser) anchor tenants may only register
    // via auth code during GA, and LRP will almost never be a GA offering. We're running this
    // as superuser to bypass the state checks, though anchor tenant code checks and LRP token
    // redemption still happen regardless.
    createTld("tld", TldState.LANDRUSH);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(persistReservedList(
            "tld-reserved",
            "anchor,RESERVED_FOR_ANCHOR_TENANT,2fooBAR"))
        .build());
    LrpToken token = persistResource(new LrpToken.Builder()
        .setToken("2fooBAR")
        .setAssignee("anchor.tld")
        .build());
    setEppInput("domain_create_anchor_authcode.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("domain_create_anchor_response.xml"));
    assertSuccessfulCreate("tld", true);
    // Token should not be marked as used, since interpreting the authcode as anchor tenant should
    // take precedence.
    assertThat(ofy().load().entity(token).now().getRedemptionHistoryEntry()).isNull();
  }

  @Test
  public void testSuccess_anchorTenantViaAuthCode() throws Exception {
    setEppInput("domain_create_anchor_authcode.xml");
    persistContactsAndHosts();
    runFlow();
    assertSuccessfulCreate("tld", true);
  }

  @Test
  public void testSuccess_anchorTenantViaAuthCode_withClaims() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(persistReservedList(
            "anchor-with-claims",
            "example-one,RESERVED_FOR_ANCHOR_TENANT,2fooBAR"))
        .build());
    setEppInput("domain_create_claim_notice.xml");
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", true);
    assertDnsTasksEnqueued("example-one.tld");
    assertClaimsLordn();
  }

  @Test
  public void testSuccess_superuserReserved() throws Exception {
    setEppInput("domain_create_reserved.xml");
    persistContactsAndHosts();
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("domain_create_reserved_response.xml"));
    assertSuccessfulCreate("tld", false);
  }

  @Test
  public void testFailure_missingHost() throws Exception {
    thrown.expect(
        LinkedResourcesDoNotExistException.class,
        "(ns2.example.net)");
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    runFlow();
  }

  @Test
  public void testFailure_pendingDeleteHost() throws Exception {
    thrown.expect(
        LinkedResourceInPendingDeleteProhibitsOperationException.class,
        "ns2.example.net");
    persistActiveHost("ns1.example.net");
    persistActiveContact("jd1234");
    persistActiveContact("sh8013");
    persistResource(newHostResource("ns2.example.net").asBuilder()
        .addStatusValue(StatusValue.PENDING_DELETE)
        .build());
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_openApplication() throws Exception {
    thrown.expect(DomainHasOpenApplicationsException.class);
    persistContactsAndHosts();
    persistActiveDomainApplication(getUniqueIdFromCommand());
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testSuccess_superuserOpenApplication() throws Exception {
    persistContactsAndHosts();
    persistActiveDomainApplication(getUniqueIdFromCommand());
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_rejectedApplication() throws Exception {
    persistContactsAndHosts();
    persistResource(newDomainApplication(getUniqueIdFromCommand()).asBuilder()
        .setApplicationStatus(ApplicationStatus.REJECTED)
        .build());
    clock.advanceOneMilli();
    doSuccessfulTest();
  }

  @Test
  public void testFailure_missingContact() throws Exception {
    thrown.expect(
        LinkedResourcesDoNotExistException.class,
        "(sh8013)");
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("jd1234");
    runFlow();
  }

  @Test
  public void testFailure_pendingDeleteContact() throws Exception {
    thrown.expect(
        LinkedResourceInPendingDeleteProhibitsOperationException.class,
        "jd1234");
    persistActiveHost("ns1.example.net");
    persistActiveHost("ns2.example.net");
    persistActiveContact("sh8013");
    persistResource(newContactResource("jd1234").asBuilder()
        .addStatusValue(StatusValue.PENDING_DELETE)
        .build());
    clock.advanceOneMilli();
    runFlow();
  }

  @Test
  public void testFailure_wrongTld() throws Exception {
    thrown.expect(TldDoesNotExistException.class);
    persistContactsAndHosts("net");
    deleteTld("tld");
    runFlow();
  }

  @Test
  public void testFailure_predelegation() throws Exception {
    thrown.expect(BadCommandForRegistryPhaseException.class);
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_sunrise() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.SUNRISE);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_sunrush() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_landrush() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.LANDRUSH);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_quietPeriod() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_superuserPredelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_superuserSunrush() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_superuserQuietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_superuserOverridesPremiumNameBlock() throws Exception {
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(Registrar.loadByClientId("TheRegistrar").asBuilder()
        .setBlockPremiumNames(true)
        .build());
    runFlowAssertResponse(
        CommitMode.LIVE,
        UserPrivileges.SUPERUSER,
        readFile("domain_create_response_premium.xml"));
    assertSuccessfulCreate("example", false);
  }

  @Test
  public void testFailure_duplicateContact() throws Exception {
    thrown.expect(DuplicateContactForRoleException.class);
    setEppInput("domain_create_duplicate_contact.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_missingContactType() throws Exception {
    // We need to test for missing type, but not for invalid - the schema enforces that for us.
    thrown.expect(MissingContactTypeException.class);
    setEppInput("domain_create_missing_contact_type.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_missingRegistrant() throws Exception {
    thrown.expect(MissingRegistrantException.class);
    setEppInput("domain_create_missing_registrant.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_missingAdmin() throws Exception {
    thrown.expect(MissingAdminContactException.class);
    setEppInput("domain_create_missing_admin.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_missingTech() throws Exception {
    thrown.expect(MissingTechnicalContactException.class);
    setEppInput("domain_create_missing_tech.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_missingNonRegistrantContacts() throws Exception {
    thrown.expect(MissingAdminContactException.class);
    setEppInput("domain_create_missing_non_registrant_contacts.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_badIdn() throws Exception {
    thrown.expect(InvalidIdnDomainLabelException.class);
    createTld("xn--q9jyb4c");
    setEppInput("domain_create_bad_idn_minna.xml");
    persistContactsAndHosts("net");
    runFlow();
  }

  @Test
  public void testFailure_badValidatorId() throws Exception {
    thrown.expect(InvalidTrademarkValidatorException.class);
    setEppInput("domain_create_bad_validator_id.xml");
    persistClaimsList(ImmutableMap.of("exampleone", CLAIMS_KEY));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_codeMark() throws Exception {
    thrown.expect(UnsupportedMarkTypeException.class);
    setEppInput("domain_create_code_with_mark.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_signedMark() throws Exception {
    thrown.expect(SignedMarksNotAcceptedInCurrentPhaseException.class);
    setEppInput("domain_create_signed_mark.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_expiredClaim() throws Exception {
    thrown.expect(ExpiredClaimException.class);
    clock.setTo(DateTime.parse("2010-08-17T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_expiredAcceptance() throws Exception {
    thrown.expect(AcceptedTooLongAgoException.class);
    clock.setTo(DateTime.parse("2009-09-16T09:00:00.0Z"));
    setEppInput("domain_create_claim_notice.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_malformedTcnIdWrongLength() throws Exception {
    thrown.expect(MalformedTcnIdException.class);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice1.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_malformedTcnIdBadChar() throws Exception {
    thrown.expect(MalformedTcnIdException.class);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_malformed_claim_notice2.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_badTcnIdChecksum() throws Exception {
    thrown.expect(InvalidTcnIdChecksumException.class);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_bad_checksum_claim_notice.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_premiumBlocked() throws Exception {
    thrown.expect(PremiumNameBlockedException.class);
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    // Modify the Registrar to block premium names.
    persistResource(Registrar.loadByClientId("TheRegistrar").asBuilder()
        .setBlockPremiumNames(true)
        .build());
    runFlow();
  }

  @Test
  public void testFailure_feeNotProvidedOnPremiumName() throws Exception {
    thrown.expect(FeesRequiredForPremiumNameException.class);
    createTld("example");
    setEppInput("domain_create_premium.xml");
    persistContactsAndHosts("net");
    runFlow();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v06() throws Exception {
    thrown.expect(UndeclaredServiceExtensionException.class);
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v11() throws Exception {
    thrown.expect(UndeclaredServiceExtensionException.class);
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_omitFeeExtensionOnLogin_v12() throws Exception {
    thrown.expect(UndeclaredServiceExtensionException.class);
    for (String uri : FEE_EXTENSION_URIS) {
      removeServiceExtensionUri(uri);
    }
    createTld("net");
    setEppInput("domain_create_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v06() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v11() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_feeGivenInWrongScale_v12() throws Exception {
    thrown.expect(CurrencyValueScaleException.class);
    setEppInput("domain_create_fee_bad_scale.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    runFlow();
  }

  private void doFailingDomainNameTest(
      String domainName,
      Class<? extends Throwable> exception) throws Exception {
    thrown.expect(exception);
    setEppInput("domain_create_uppercase.xml");
    eppLoader.replaceAll("Example.tld", domainName);
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_uppercase() throws Exception {
    doFailingDomainNameTest("Example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_badCharacter() throws Exception {
    doFailingDomainNameTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_leadingDash() throws Exception {
    doFailingDomainNameTest("-example.tld", LeadingDashException.class);
  }

  @Test
  public void testFailure_trailingDash() throws Exception {
    doFailingDomainNameTest("example-.tld", TrailingDashException.class);
  }

  @Test
  public void testFailure_tooLong() throws Exception {
    doFailingDomainNameTest(Strings.repeat("a", 64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  public void testFailure_leadingDot() throws Exception {
    doFailingDomainNameTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_leadingDotTld() throws Exception {
    doFailingDomainNameTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_tooManyParts() throws Exception {
    doFailingDomainNameTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_tooFewParts() throws Exception {
    doFailingDomainNameTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_invalidPunycode() throws Exception {
    doFailingDomainNameTest("xn--abcdefg.tld", InvalidPunycodeException.class);
  }

  @Test
  public void testFailure_dashesInThirdAndFourthPosition() throws Exception {
    doFailingDomainNameTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  public void testFailure_tldDoesNotExist() throws Exception {
    doFailingDomainNameTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  public void testFailure_invalidIdnCodePoints() throws Exception {
    // ❤☀☆☂☻♞☯.tld
    doFailingDomainNameTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  public void testFailure_sunriseRegistration() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_superuserSunriseRegistration() throws Exception {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_sunrise.xml");
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_qlpSunriseRegistration() throws Exception {
    createTld("tld", TldState.SUNRISE);
    setEppInput("domain_create_registration_qlp_sunrise.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertSuccessfulCreate("tld", true);
    assertNoLordn();
  }

  @Test
  public void testSuccess_qlpSunriseRegistrationWithEncodedSignedMark() throws Exception {
    createTld("tld", TldState.SUNRISE);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_sunrise_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_encoded_signed_mark_name.xml"));
    assertSuccessfulCreate("tld", true);
    assertSunriseLordn();
  }

  @Test
  public void testSuccess_qlpSunriseRegistrationWithClaimsNotice() throws Exception {
    createTld("tld", TldState.SUNRISE);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_sunrise_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", true);
    assertClaimsLordn();
  }

  @Test
  public void testFailure_sunrushRegistration() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_sunrush.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    thrown.expect(NotAuthorizedForTldException.class);
    createTld("irrelevant", "IRR");
    DatastoreHelper.persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.<String>of("irrelevant"))
            .build());
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_superuserSunrushRegistration() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_sunrush.xml");
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_qlpSunrushRegistration() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    setEppInput("domain_create_registration_qlp_sunrush.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertSuccessfulCreate("tld", true);
    assertNoLordn();
  }

  @Test
  public void testSuccess_qlpSunrushRegistrationWithEncodedSignedMark() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_sunrush_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_encoded_signed_mark_name.xml"));
    assertSuccessfulCreate("tld", true);
    assertSunriseLordn();
  }

  @Test
  public void testSuccess_qlpSunrushRegistrationWithClaimsNotice() throws Exception {
    createTld("tld", TldState.SUNRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_sunrush_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", true);
    assertClaimsLordn();
  }

  @Test
  public void testFailure_landrushRegistration() throws Exception {
    thrown.expect(NoGeneralRegistrationsInCurrentPhaseException.class);
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_landrush.xml");
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_superuserLandrushRegistration() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_landrush.xml");
    persistContactsAndHosts();
    doSuccessfulTest("tld", "domain_create_response.xml", UserPrivileges.SUPERUSER);
  }

  @Test
  public void testSuccess_qlpLandrushRegistration() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    setEppInput("domain_create_registration_qlp_landrush.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response.xml"));
    assertSuccessfulCreate("tld", true);
    assertNoLordn();
  }

  @Test
  public void testFailure_qlpLandrushRegistrationWithEncodedSignedMark() throws Exception {
    thrown.expect(SignedMarksNotAcceptedInCurrentPhaseException.class);
    createTld("tld", TldState.LANDRUSH);
    clock.setTo(DateTime.parse("2014-09-09T09:09:09Z"));
    setEppInput("domain_create_registration_qlp_landrush_encoded_signed_mark.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlow();
  }

  @Test
  public void testSuccess_qlpLandrushRegistrationWithClaimsNotice() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    clock.setTo(DateTime.parse("2009-08-16T09:00:00.0Z"));
    setEppInput("domain_create_registration_qlp_landrush_claims_notice.xml");
    eppRequestSource = EppRequestSource.TOOL;  // Only tools can pass in metadata.
    persistContactsAndHosts();
    runFlowAssertResponse(readFile("domain_create_response_claims.xml"));
    assertSuccessfulCreate("tld", true);
    assertClaimsLordn();
  }

  @Test
  public void testFailure_registrantNotWhitelisted() throws Exception {
    persistActiveContact("someone");
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("someone"))
        .build());
    thrown.expect(RegistrantNotAllowedException.class, "jd1234");
    runFlow();
  }

  @Test
  public void testFailure_nameserverNotWhitelisted() throws Exception {
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns2.example.net"))
        .build());
    thrown.expect(NameserversNotAllowedException.class, "ns1.example.net");
    runFlow();
  }

  @Test
  public void testFailure_emptyNameserverFailsWhitelist() throws Exception {
    setEppInput("domain_create_no_hosts_or_dsdata.xml");
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("somethingelse.example.net"))
        .build());
    persistContactsAndHosts();
    thrown.expect(NameserversNotSpecifiedException.class);
    runFlow();
  }

  @Test
  public void testSuccess_nameserverAndRegistrantWhitelisted() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setAllowedRegistrantContactIds(ImmutableSet.of("jd1234"))
        .setAllowedFullyQualifiedHostNames(ImmutableSet.of("ns1.example.net", "ns2.example.net"))
        .build());
    persistContactsAndHosts();
    doSuccessfulTest();
  }

  @Test
  public void testSuccess_eapFeeApplied_v06() throws Exception {
    setEppInput("domain_create_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().minusDays(1), Money.of(USD, 100),
            clock.nowUtc().plusDays(1), Money.of(USD, 0)))
        .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.6"));
  }

  @Test
  public void testSuccess_eapFeeApplied_v11() throws Exception {
    setEppInput("domain_create_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().minusDays(1), Money.of(USD, 100),
            clock.nowUtc().plusDays(1), Money.of(USD, 0)))
        .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.11"));
  }

  @Test
  public void testSuccess_eapFeeApplied_v12() throws Exception {
    setEppInput("domain_create_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().minusDays(1), Money.of(USD, 100),
            clock.nowUtc().plusDays(1), Money.of(USD, 0)))
        .build());
    doSuccessfulTest(
        "tld", "domain_create_response_eap_fee.xml", ImmutableMap.of("FEE_VERSION", "0.12"));
  }

  @Test
  public void testSuccess_eapFee_beforeEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().plusDays(1), Money.of(USD, 10),
            clock.nowUtc().plusDays(2), Money.of(USD, 0)))
        .build());
    doSuccessfulTest("tld", "domain_create_response.xml");
  }

  @Test
  public void testSuccess_eapFee_afterEntireSchedule() throws Exception {
    persistContactsAndHosts();
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().minusDays(2), Money.of(USD, 100),
            clock.nowUtc().minusDays(1), Money.of(USD, 0)))
        .build());
    doSuccessfulTest("tld", "domain_create_response.xml");
  }
}
