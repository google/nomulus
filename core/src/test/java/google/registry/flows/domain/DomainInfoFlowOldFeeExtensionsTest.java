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

import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveHost;
import static google.registry.testing.DatabaseHelper.persistBillingRecurrenceForDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.testing.TestDataHelper.updateSubstitutions;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.model.billing.BillingBase.RenewalPriceBehavior;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainAuthInfo;
import google.registry.model.eppcommon.AuthInfo.PasswordAuth;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.Host;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTransactionManagerExtension;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DomainInfoFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainInfoFlow> {

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
  void beforeEachDomainInfoFlowOldFeeExtensionsTest() {
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
