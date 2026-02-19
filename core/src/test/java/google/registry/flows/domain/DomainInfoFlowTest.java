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

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.eppcommon.EppXmlTransformer.marshal;
import static google.registry.model.tld.Tld.TldState.QUIET_PERIOD;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistBillingRecurrenceForDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.TestDataHelper.updateSubstitutions;
import static google.registry.util.DateTimeUtils.END_OF_TIME;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static google.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceFlowTestCase;
import google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException;
import google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.GracePeriod;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.secdns.DomainDsData;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenType;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.host.Host;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.persistence.VKey;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import google.registry.testing.DatabaseHelper;
import google.registry.xml.ValidationMode;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DomainInfoFlow}. */
class DomainInfoFlowTest extends ResourceFlowTestCase<DomainInfoFlow, Domain> {

  /**
   * The domain_info_fee.xml default substitutions common to most tests.
   *
   * <p>It doesn't set a default value to the COMMAND and PERIOD keys, because they are different in
   * every test.
   */
  private static final ImmutableMap<String, String> SUBSTITUTION_BASE =
      ImmutableMap.of(
          "NAME", "example.tld",
          "CURRENCY", "USD",
          "UNIT", "y");

  private static final Pattern OK_PATTERN = Pattern.compile("\"ok\"");

  private Host host1;
  private Host host2;
  private Host host3;
  private Domain domain;

  @BeforeEach
  void setup() {
    setEppInput("domain_info.xml");
    clock.setTo(DateTime.parse("2005-03-03T22:00:00.000Z"));
    sessionMetadata.setRegistrarId("NewRegistrar");
    createTld("tld");
    persistResource(
        JpaTransactionManagerExtension.makeRegistrar1()
            .asBuilder()
            .setRegistrarId("ClientZ")
            .build());
  }

  private void persistTestEntities(String domainName, boolean inactive) {
    host1 = persistActiveHost("ns1.example.tld");
    host2 = persistActiveHost("ns1.example.net");
    domain =
        persistResource(
            new Domain.Builder()
                .setDomainName(domainName)
                .setRepoId("2FF-TLD")
                .setPersistedCurrentSponsorRegistrarId("NewRegistrar")
                .setCreationRegistrarId("TheRegistrar")
                .setLastEppUpdateRegistrarId("NewRegistrar")
                .setCreationTimeForTest(DateTime.parse("1999-04-03T22:00:00.0Z"))
                .setLastEppUpdateTime(DateTime.parse("1999-12-03T09:00:00.0Z"))
                .setLastTransferTime(DateTime.parse("2000-04-08T09:00:00.0Z"))
                .setRegistrationExpirationTime(DateTime.parse("2005-04-03T22:00:00.0Z"))
                .setNameservers(
                    inactive ? null : ImmutableSet.of(host1.createVKey(), host2.createVKey()))
                .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("2fooBAR")))
                .build());
    // Set the superordinate domain of ns1.example.com to example.com. In reality, this would have
    // happened in the flow that created it, but here we just overwrite it in the database.
    host1 = persistResource(host1.asBuilder().setSuperordinateDomain(domain.createVKey()).build());
    // Create a subordinate host that is not delegated to by anyone.
    host3 =
        persistResource(
            new Host.Builder()
                .setHostName("ns2.example.tld")
                .setRepoId("3FF-TLD")
                .setSuperordinateDomain(domain.createVKey())
                .build());
    // Add the subordinate host keys to the existing domain.
    domain =
        persistResource(
            domain
                .asBuilder()
                .setSubordinateHosts(ImmutableSet.of(host1.getHostName(), host3.getHostName()))
                .build());
  }

  private void persistTestEntities(boolean inactive) {
    persistTestEntities("example.tld", inactive);
  }

  private void doSuccessfulTest(
      String expectedXmlFilename,
      boolean inactive,
      ImmutableMap<String, String> substitutions,
      boolean expectHistoryAndBilling)
      throws Exception {
    assertMutatingFlow(true);
    String expected =
        loadFile(expectedXmlFilename, updateSubstitutions(substitutions, "ROID", "2FF-TLD"));
    if (inactive) {
      expected = OK_PATTERN.matcher(expected).replaceAll("\"inactive\"");
    }
    runFlowAssertResponse(expected);
    if (!expectHistoryAndBilling) {
      assertNoHistory();
      assertNoBillingEvents();
    }
  }

  private void doSuccessfulTest(String expectedXmlFilename, boolean inactive) throws Exception {
    doSuccessfulTest(expectedXmlFilename, inactive, ImmutableMap.of(), false);
  }

  private void doSuccessfulTest(String expectedXmlFilename) throws Exception {
    persistTestEntities(false);
    doSuccessfulTest(expectedXmlFilename, false);
  }

  private void doSuccessfulTestNoNameservers(String expectedXmlFilename) throws Exception {
    persistTestEntities(true);
    doSuccessfulTest(expectedXmlFilename, true);
  }

  /** sets up a sample recurring billing event as part of the domain creation process. */
  private void setUpBillingEventForExistingDomain() {
    setUpBillingEventForExistingDomain(DEFAULT, null);
  }

  private void setUpBillingEventForExistingDomain(
      RenewalPriceBehavior renewalPriceBehavior, @Nullable Money renewalPrice) {
    domain = persistBillingRecurrenceForDomain(domain, renewalPriceBehavior, renewalPrice);
  }

  @Test
  void testNotLoggedIn() {
    sessionMetadata.setRegistrarId(null);
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_allHosts() throws Exception {
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  void testSuccess_noContacts() throws Exception {
    persistTestEntities(false);
    doSuccessfulTest("domain_info_response_no_contacts.xml", false);
  }

  @Test
  void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_info_no_cltrid.xml");
    doSuccessfulTest("domain_info_response_no_cltrid.xml");
  }

  @Test
  void testSuccess_allHosts_noDelegatedHosts() throws Exception {
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  void testSuccess_defaultHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  void testSuccess_defaultHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_default_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  void testSuccess_delegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    doSuccessfulTest("domain_info_response_delegated_hosts.xml");
  }

  @Test
  void testSuccess_delegatedHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_delegated_hosts.xml");
    // There aren't any delegated hosts.
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @Test
  void testSuccess_subordinateHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTest("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  void testSuccess_subordinateHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_subordinate_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_subordinate_hosts.xml");
  }

  @Test
  void testSuccess_noneHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTest("domain_info_response_none_hosts.xml");
  }

  @Test
  void testSuccess_noneHosts_noDelegatedHosts() throws Exception {
    setEppInput("domain_info_none_hosts.xml");
    doSuccessfulTestNoNameservers("domain_info_response_none_hosts.xml");
  }

  @Test
  void testSuccess_unauthorized() throws Exception {
    sessionMetadata.setRegistrarId("ClientZ");
    doSuccessfulTest("domain_info_response_unauthorized.xml");
  }

  @Test
  void testSuccess_differentRegistrarWithAuthInfo() throws Exception {
    setEppInput("domain_info_with_auth.xml");
    sessionMetadata.setRegistrarId("ClientZ");
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  void testSuccess_inQuietPeriod() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setTldStateTransitions(ImmutableSortedMap.of(START_OF_TIME, QUIET_PERIOD))
            .build());
    doSuccessfulTest("domain_info_response.xml");
  }

  @Test
  void testSuccess_secDns() throws Exception {
    persistTestEntities(false);
    // Add the dsData to the saved resource and change the nameservers to match the sample xml.
    persistResource(
        domain
            .asBuilder()
            .setDsData(
                ImmutableSet.of(
                    DomainDsData.create(12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
            .setNameservers(ImmutableSet.of(host1.createVKey(), host3.createVKey()))
            .build());
    doSuccessfulTest("domain_info_response_dsdata.xml", false);
  }

  private void doAddPeriodTest(GracePeriodStatus gracePeriodStatus) throws Exception {
    persistTestEntities(false);
    // Add the grace period to the saved resource, and change a few other fields to match the sample
    // xml.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    gracePeriodStatus,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setCreationRegistrarId("NewRegistrar")
            .setCreationTimeForTest(DateTime.parse("2003-11-26T22:00:00.0Z"))
            .setRegistrationExpirationTime(DateTime.parse("2005-11-26T22:00:00.0Z"))
            .setLastTransferTime(null)
            .setLastEppUpdateTime(null)
            .setLastEppUpdateRegistrarId(null)
            .build());
    doSuccessfulTest("domain_info_response_addperiod.xml", false);
  }

  @Test
  void testSuccess_addGracePeriod() throws Exception {
    doAddPeriodTest(GracePeriodStatus.ADD);
  }

  @Test
  void testSuccess_autoRenewGracePeriod() throws Exception {
    persistTestEntities(false);
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(domain)
                .setType(HistoryEntry.Type.DOMAIN_CREATE)
                .setModificationTime(clock.nowUtc())
                .setRegistrarId(domain.getCreationRegistrarId())
                .build());
    BillingRecurrence renewEvent =
        persistResource(
            new BillingRecurrence.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(getUniqueIdFromCommand())
                .setRegistrarId("TheRegistrar")
                .setEventTime(clock.nowUtc())
                .setRecurrenceEndTime(END_OF_TIME)
                .setDomainHistory(historyEntry)
                .build());
    VKey<BillingRecurrence> recurrenceVKey = renewEvent.createVKey();
    // Add an AUTO_RENEW grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.createForRecurrence(
                    GracePeriodStatus.AUTO_RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    recurrenceVKey))
            .build());
    doSuccessfulTest("domain_info_response_autorenewperiod.xml", false, ImmutableMap.of(), true);
  }

  @Test
  void testSuccess_redemptionGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an REDEMPTION grace period to the saved resource, and change a few other fields to match
    // the sample xml.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.REDEMPTION,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest("domain_info_response_redemptionperiod.xml", false);
  }

  @Test
  void testSuccess_renewGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an RENEW grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @Test
  void testSuccess_multipleRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add multiple RENEW grace periods to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(2),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_renewperiod.xml", false);
  }

  @Test
  void testSuccess_transferGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add an TRANSFER grace period to the saved resource.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.TRANSFER,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_transferperiod.xml", false);
  }

  @Test
  void testSuccess_pendingDelete() throws Exception {
    persistTestEntities(false);
    // Set the domain to be pending delete with no grace period, which will cause an RGP status of
    // pending delete to show up, too.
    persistResource(
        domain.asBuilder().setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE)).build());
    doSuccessfulTest("domain_info_response_pendingdelete.xml", false);
  }

  @Test
  void testSuccess_stackedAddRenewGracePeriods() throws Exception {
    persistTestEntities(false);
    // Add both an ADD and RENEW grace period, both which should show up in the RGP status.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.RENEW,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(2),
                    "TheRegistrar",
                    null))
            .build());
    doSuccessfulTest("domain_info_response_stackedaddrenewperiod.xml", false);
  }

  @Test
  void testSuccess_secDnsAndAddGracePeriod() throws Exception {
    persistTestEntities(false);
    // Add both an ADD grace period and SecDNS data.
    persistResource(
        domain
            .asBuilder()
            .addGracePeriod(
                GracePeriod.create(
                    GracePeriodStatus.ADD,
                    domain.getRepoId(),
                    clock.nowUtc().plusDays(1),
                    "TheRegistrar",
                    null))
            .setDsData(
                ImmutableSet.of(
                    DomainDsData.create(12345, 3, 1, base16().decode("49FD46E6C4B45C55D4AC"))))
            .build());
    doSuccessfulTest("domain_info_response_dsdata_addperiod.xml", false);
  }

  @Test
  void testFailure_neverExisted() throws Exception {
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_existedButWasDeleted() throws Exception {
    persistResource(
        DatabaseHelper.newDomain("example.tld")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().minusDays(1))
            .build());
    ResourceDoesNotExistException thrown =
        assertThrows(ResourceDoesNotExistException.class, this::runFlow);
    assertThat(thrown).hasMessageThat().contains(String.format("(%s)", getUniqueIdFromCommand()));
  }

  @Test
  void testFailure_differentRegistrarWrongAuthInfo() {
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    sessionMetadata.setRegistrarId("ClientZ");
    setEppInput("domain_info_with_auth.xml");
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFailure_wrongAuthInfo() {
    persistTestEntities(false);
    // Change the password of the domain so that it does not match the file.
    persistResource(
        domain
            .asBuilder()
            .setAuthInfo(DomainAuthInfo.create(PasswordAuth.create("diffpw")))
            .build());
    setEppInput("domain_info_with_auth.xml");
    EppException thrown = assertThrows(BadAuthInfoForResourceException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testIcannActivityReportField_getsLogged() throws Exception {
    persistTestEntities(false);
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-info");
    assertTldsFieldLogged("tld");
  }

  @Test
  void testBulkInfoExtension_returnsBulkInfo() throws Exception {
    persistTestEntities(false);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    domain = domain.asBuilder().setCurrentBulkToken(token.createVKey()).build();
    persistResource(domain);
    setEppInput("domain_info_bulk.xml");
    doSuccessfulTest("domain_info_response_bulk.xml", false);
  }

  @Test
  void testBulkInfoExtension_returnsBulkInfoForSuperUser() throws Exception {
    persistTestEntities(false);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    domain = domain.asBuilder().setCurrentBulkToken(token.createVKey()).build();
    persistResource(domain);
    sessionMetadata.setRegistrarId("TheRegistrar");
    setEppInput("domain_info_bulk.xml");
    EppOutput output = runFlowAsSuperuser();
    String expectedOutput =
        loadFile(
            "domain_info_response_superuser_bulk.xml",
            updateSubstitutions(ImmutableMap.of(), "ROID", "2FF-TLD"));
    assertXmlEquals(expectedOutput, new String(marshal(output, ValidationMode.LENIENT), UTF_8));
  }

  @Test
  void testBulkInfoExtension_nameNotInBulkPackage() throws Exception {
    setEppInput("domain_info_bulk.xml");
    doSuccessfulTest("domain_info_response_empty_bulk_package.xml");
  }

  @Test
  void testBulkInfoExtension_notCurrentSponsorRegistrar() throws Exception {
    persistTestEntities(false);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("abc123")
                .setTokenType(TokenType.BULK_PRICING)
                .setCreationTimeForTest(DateTime.parse("2010-11-12T05:00:00Z"))
                .setAllowedTlds(ImmutableSet.of("foo"))
                .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
                .setRenewalPriceBehavior(RenewalPriceBehavior.SPECIFIED)
                .setRenewalPrice(Money.of(USD, 0))
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setDiscountFraction(1.0)
                .build());
    domain = domain.asBuilder().setCurrentBulkToken(token.createVKey()).build();
    persistResource(domain);
    sessionMetadata.setRegistrarId("TheRegistrar");
    setEppInput("domain_info_bulk.xml");
    doSuccessfulTest("domain_info_response_unauthorized.xml", false);
  }

  // The fee extension is no longer supported in domain:info commands as of version 1.0.
  // For now, we still support old versions.
  @Test
  void testFeeExtension_restoreCommand_pendingDelete_withRenewal() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    setUpBillingEventForExistingDomain();
    persistResource(
        domain
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest(
        "domain_info_fee_restore_response_with_renewal.xml", false, ImmutableMap.of(), true);
  }

  /**
   * Test create command. Fee extension version 6 is the only one which supports fee extensions on
   * info commands and responses, so we don't need to test the other versions.
   */
  @Test
  void testFeeExtension_createCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "create", "PERIOD", "2"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "create",
            "DESCRIPTION", "create",
            "PERIOD", "2",
            "FEE", "24.00"),
        true);
  }

  /** Test renew command. */
  @Test
  void testFeeExtension_renewCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "2"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "renew",
            "DESCRIPTION", "renew",
            "PERIOD", "2",
            "FEE", "22.00"),
        true);
  }

  /** Test transfer command. */
  @Test
  void testFeeExtension_transferCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "transfer", "PERIOD", "1"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of(
            "COMMAND", "transfer",
            "DESCRIPTION", "renew",
            "PERIOD", "1",
            "FEE", "11.00"),
        true);
  }

  /** Test restore command. */
  @Test
  void testFeeExtension_restoreCommand() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest("domain_info_fee_restore_response.xml", false, ImmutableMap.of(), true);
  }

  @Test
  void testFeeExtension_restoreCommand_pendingDelete_noRenewal() throws Exception {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    persistResource(
        domain
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    doSuccessfulTest(
        "domain_info_fee_restore_response_no_renewal.xml", false, ImmutableMap.of(), true);
  }

  /** Test create command on a premium label. */
  @Test
  void testFeeExtension_createCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "create", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "create", "DESCRIPTION", "create"),
        true);
  }

  /** Test renew command on a premium label. */
  @Test
  void testFeeExtension_renewCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "renew", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew"),
        true);
  }

  @Test
  void testFeeExtension_renewCommandPremium_anchorTenant() throws Exception {
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 70"))
            .build());
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "1"));
    persistTestEntities("example.tld", false);
    setUpBillingEventForExistingDomain(NONPREMIUM, null);
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew", "FEE", "11.00", "PERIOD", "1"),
        true);
  }

  @Test
  void testFeeExtension_renewCommandPremium_internalRegistration() throws Exception {
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 70"))
            .build());
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "1"));
    persistTestEntities("example.tld", false);
    setUpBillingEventForExistingDomain(SPECIFIED, Money.of(USD, 3));
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew", "FEE", "3.00", "PERIOD", "1"),
        true);
  }

  @Test
  void testFeeExtension_renewCommandPremium_anchorTenant_multiYear() throws Exception {
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 70"))
            .build());
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "3"));
    persistTestEntities("example.tld", false);
    setUpBillingEventForExistingDomain(NONPREMIUM, null);
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew", "FEE", "33.00", "PERIOD", "3"),
        true);
  }

  @Test
  void testFeeExtension_renewCommandPremium_internalRegistration_multiYear() throws Exception {
    createTld("tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("tld", USD, "example,USD 70"))
            .build());
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "3"));
    persistTestEntities("example.tld", false);
    setUpBillingEventForExistingDomain(SPECIFIED, Money.of(USD, 3));
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew", "FEE", "9.00", "PERIOD", "3"),
        true);
  }

  @Test
  void testFeeExtension_renewCommandStandard_internalRegistration() throws Exception {
    createTld("tld");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "renew", "PERIOD", "1"));
    persistTestEntities("example.tld", false);
    setUpBillingEventForExistingDomain(SPECIFIED, Money.of(USD, 3));
    doSuccessfulTest(
        "domain_info_fee_response.xml",
        false,
        ImmutableMap.of("COMMAND", "renew", "DESCRIPTION", "renew", "FEE", "3.00", "PERIOD", "1"),
        true);
  }

  /** Test transfer command on a premium label. */
  @Test
  void testFeeExtension_transferCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "transfer", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_premium_response.xml",
        false,
        ImmutableMap.of("COMMAND", "transfer", "DESCRIPTION", "renew"),
        true);
  }

  /** Test restore command on a premium label. */
  @Test
  void testFeeExtension_restoreCommandPremium() throws Exception {
    createTld("example");
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "NAME", "rich.example", "COMMAND", "restore", "PERIOD", "1"));
    persistTestEntities("rich.example", false);
    setUpBillingEventForExistingDomain();
    doSuccessfulTest(
        "domain_info_fee_restore_premium_response.xml", false, ImmutableMap.of(), true);
  }

  /** Test setting the currency explicitly to a wrong value. */
  @Test
  void testFeeExtension_wrongCurrency() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "COMMAND", "create", "CURRENCY", "EUR", "PERIOD", "1"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test requesting a period that isn't in years. */
  @Test
  void testFeeExtension_periodNotInYears() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "create", "PERIOD", "2", "UNIT", "m"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a command that specifies a phase. */
  @Test
  void testFeeExtension_commandPhase() {
    setEppInput("domain_info_fee_command_phase.xml");
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a command that specifies a subphase. */
  @Test
  void testFeeExtension_commandSubphase() {
    setEppInput("domain_info_fee_command_subphase.xml");
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a restore for more than one year. */
  @Test
  void testFeeExtension_multiyearRestore() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "restore", "PERIOD", "2"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test a transfer for more than one year. */
  @Test
  void testFeeExtension_multiyearTransfer() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(SUBSTITUTION_BASE, "COMMAND", "transfer", "PERIOD", "2"));
    persistTestEntities(false);
    setUpBillingEventForExistingDomain();
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCurrency() {
    setEppInput(
        "domain_info_fee.xml",
        updateSubstitutions(
            SUBSTITUTION_BASE, "COMMAND", "create", "CURRENCY", "BAD", "PERIOD", "1"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }
}
