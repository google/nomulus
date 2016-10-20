// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

import static google.registry.model.eppoutput.CheckData.DomainCheck.create;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.newDomainApplication;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.flows.ResourceCheckFlowTestCase;
import google.registry.flows.domain.DomainCheckFlow.OnlyCheckedNamesCanBeFeeCheckedException;
import google.registry.flows.domain.DomainFlowUtils.BadCommandForRegistryPhaseException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.UnknownFeeCommandException;
import google.registry.flows.exceptions.TooManyResourceChecksException;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.launch.ApplicationStatus;
import google.registry.model.domain.launch.LaunchPhase;
import google.registry.model.registrar.Registrar;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservedList;
import google.registry.testing.DatastoreHelper;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/** Unit tests for {@link DomainCheckFlow}. */
public class DomainCheckFlowTest
    extends ResourceCheckFlowTestCase<DomainCheckFlow, DomainResource> {

  public DomainCheckFlowTest() {
    setEppInput("domain_check_one_tld.xml");
  }

  private ReservedList createReservedList() throws Exception {
      return persistReservedList(
          "tld-reserved",
          "reserved,FULLY_BLOCKED",
          "anchor,RESERVED_FOR_ANCHOR_TENANT,foo2BAR",
          "allowedinsunrise,ALLOWED_IN_SUNRISE",
          "collision,NAME_COLLISION",
          "premiumcollision,NAME_COLLISION");
  }

  @Before
  public void initCheckTest() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistResource(Registry.get("tld").asBuilder().setReservedLists(createReservedList()).build());
  }

  @Test
  public void testSuccess_nothingExists() throws Exception {
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_oneExists() throws Exception {
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_oneReserved() throws Exception {
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Reserved for non-sunrise"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_oneExtra() throws Exception {
    setEppInput("domain_check_one_tld_extra_validation.xml");
    doCheckTest(
        create(false, "extra.tld", "Extra"),
        create(true, "extra2.tld", null));
  }

  @Test
  public void testSuccess_anchorTenantReserved() throws Exception {
    setEppInput("domain_check_anchor.xml");
    doCheckTest(create(false, "anchor.tld", "Reserved"));
  }

  @Test
  public void testSuccess_multipartTld_oneReserved() throws Exception {
    createTld("tld.foo");
    persistResource(
        Registry.get("tld.foo")
            .asBuilder()
            .setReservedLists(persistReservedList(
                "tld.foo", "reserved,FULLY_BLOCKED", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    setEppInput("domain_check_one_multipart_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld.foo", "Reserved"),
        create(false, "allowedinsunrise.tld.foo", "Reserved for non-sunrise"),
        create(true, "example2.tld.foo", null),
        create(true, "example3.tld.foo", null));
  }

  @Test
  public void testSuccess_oneExistsButWasDeleted() throws Exception {
    persistDeletedDomain("example1.tld", clock.nowUtc().minusDays(1));
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_duplicatesAllowed() throws Exception {
    setEppInput("domain_check_duplicates.xml");
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example1.tld", null));
  }

  @Test
  public void testSuccess_xmlMatches() throws Exception {
    persistActiveDomain("example2.tld");
    runFlowAssertResponse(readFile("domain_check_one_tld_response.xml"));
  }

  @Test
  public void testSuccess_50IdsAllowed() throws Exception {
    // Make sure we don't have a regression that reduces the number of allowed checks.
    setEppInput("domain_check_50.xml");
    runFlow();
  }

  @Test
  public void testSuccess_pendingSunriseApplicationInGeneralAvailability() throws Exception {
    createTld("tld", TldState.GENERAL_AVAILABILITY);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(false, "example2.tld", "Pending allocation"),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_pendingLandrushApplicationInGeneralAvailability() throws Exception {
    createTld("tld", TldState.GENERAL_AVAILABILITY);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .setPhase(LaunchPhase.LANDRUSH)
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(false, "example2.tld", "Pending allocation"),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_pendingSunriseApplicationInQuietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(false, "example2.tld", "Pending allocation"),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_pendingLandrushApplicationInQuietPeriod() throws Exception {
    createTld("tld", TldState.QUIET_PERIOD);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .setPhase(LaunchPhase.LANDRUSH)
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(false, "example2.tld", "Pending allocation"),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_pendingSunriseApplicationInSunrise() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_pendingLandrushApplicationInLandrush() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .setPhase(LaunchPhase.LANDRUSH)
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_rejectedApplication() throws Exception {
    createTld("tld", TldState.LANDRUSH);
    persistResource(newDomainApplication("example2.tld").asBuilder()
        .setPhase(LaunchPhase.LANDRUSH)
        .setApplicationStatus(ApplicationStatus.REJECTED)
        .build());
    doCheckTest(
        create(true, "example1.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testFailure_tooManyIds() throws Exception {
    setEppInput("domain_check_51.xml");
    thrown.expect(TooManyResourceChecksException.class);
    runFlow();
  }

  @Test
  public void testFailure_wrongTld() throws Exception {
    setEppInput("domain_check.xml");
    thrown.expect(TldDoesNotExistException.class);
    runFlow();
  }

  @Test
  public void testFailure_notAuthorizedForTld() throws Exception {
    DatastoreHelper.persistResource(
        Registrar.loadByClientId("TheRegistrar")
            .asBuilder()
            .setAllowedTlds(ImmutableSet.<String>of())
            .build());
    thrown.expect(NotAuthorizedForTldException.class);
    runFlow();
  }

  private void doFailingBadLabelTest(String label, Class<? extends Exception> expectedException)
      throws Exception {
    setEppInput("domain_check_template.xml", ImmutableMap.of("LABEL", label));
    thrown.expect(expectedException);
    runFlow();
  }

  @Test
  public void testFailure_uppercase() throws Exception {
    doFailingBadLabelTest("FOO.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_badCharacter() throws Exception {
    doFailingBadLabelTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_leadingDash() throws Exception {
    doFailingBadLabelTest("-example.tld", LeadingDashException.class);
  }

  @Test
  public void testFailure_trailingDash() throws Exception {
    doFailingBadLabelTest("example-.tld", TrailingDashException.class);
  }

  @Test
  public void testFailure_tooLong() throws Exception {
    doFailingBadLabelTest(Strings.repeat("a", 64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  public void testFailure_leadingDot() throws Exception {
    doFailingBadLabelTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_leadingDotTld() throws Exception {
    doFailingBadLabelTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_tooManyParts() throws Exception {
    doFailingBadLabelTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_tooFewParts() throws Exception {
    doFailingBadLabelTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_invalidPunycode() throws Exception {
    doFailingBadLabelTest("xn--abcdefg.tld", InvalidPunycodeException.class);
  }

  @Test
  public void testFailure_dashesInThirdAndFourthPosition() throws Exception {
    doFailingBadLabelTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  public void testFailure_tldDoesNotExist() throws Exception {
    doFailingBadLabelTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  public void testFailure_invalidIdnCodePoints() throws Exception {
    // ❤☀☆☂☻♞☯.tld
    doFailingBadLabelTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  public void testFailure_predelegation() throws Exception {
    createTld("tld", TldState.PREDELEGATION);
    thrown.expect(BadCommandForRegistryPhaseException.class);
    runFlow();
  }

  @Test
  public void testAvailExtension() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_avail.xml");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  /**
   * Test that premium names are shown as unavailable if the premium pricing extension is not
   * declared at login.
   */
  @Test
  public void testAvailExtension_premiumDomainsAreUnavailableWithoutExtension() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    createTld("example");
    setEppInput("domain_check_premium.xml");
    doCheckTest(create(false, "rich.example", "Premium names require EPP ext."));
  }

  /**
   * Test that premium names are always shown as available if the TLD does not require the premium
   * pricing extension to register premium names.
   */
  @Test
  public void testAvailExtension_premiumDomainsAvailableIfNotRequiredByTld() throws Exception {
    sessionMetadata.setServiceExtensionUris(ImmutableSet.<String>of());
    createTld("example");
    persistResource(Registry.get("example").asBuilder().setPremiumPriceAckRequired(false).build());
    setEppInput("domain_check_premium.xml");
    doCheckTest(create(true, "rich.example", null));
  }

  /** Test multiyear periods and explicitly correct currency and that the avail extension is ok. */
  @Test
  public void testFeeExtension_v06() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml");
    runFlowAssertResponse(readFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_v11() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml");
    runFlowAssertResponse(readFile("domain_check_fee_response_v11.xml"));
  }

  @Test
  public void testFeeExtension_v12() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(readFile("domain_check_fee_response_v12.xml"));
  }

  /**
   * Test commands for create, renew, transfer, restore and update with implicit period and
   * currency. */
  @Test
  public void testFeeExtension_multipleCommands_v06() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(readFile("domain_check_fee_multiple_commands_response_v06.xml"));
  }

  // Version 11 cannot have multiple commands.

  @Test
  public void testFeeExtension_multipleCommands_v12() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(readFile("domain_check_fee_multiple_commands_response_v12.xml"));
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  public void testFeeExtension_premiumLabels_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_create() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_renew() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_renew.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v11_renew.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_transfer() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_transfer.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v11_transfer.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_restore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_update() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_update.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v11_update.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v12() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(readFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_fractionalCost() throws Exception {
    // Note that the response xml expects to see "11.10" with two digits after the decimal point.
    // This works because Money.getAmount(), used in the flow, returns a BigDecimal that is set to
    // display the number of digits that is conventional for the given currency.
    persistResource(Registry.get("tld").asBuilder()
        .setCreateBillingCost(Money.of(CurrencyUnit.USD, 11.1))
        .build());
    setEppInput("domain_check_fee_fractional.xml");
    runFlowAssertResponse(readFile("domain_check_fee_fractional_response.xml"));
  }

  /** Test that create fees are properly omitted/classed on names on reserved lists. */
  @Test
  public void testFeeExtension_reservedName_v06() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v06.xml"));
  }

  /** The tests must be split up for version 11, which allows only one command at a time. */
  @Test
  public void testFeeExtension_reservedName_v11_create() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v11_create.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_renew() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v11_renew.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_transfer() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v11_transfer.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_restore() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v12() throws Exception {
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v06.xml"));
  }

  @Test
  public void
      testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_create() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v11_create.xml"));
  }

  @Test
  public void
      testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_renew() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v11_renew.xml"));
  }

  @Test
  public void
      testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_transfer() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v11_transfer.xml"));
  }

  @Test
  public void
      testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_restore() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v12() throws Exception {
    createTld("tld", TldState.SUNRISE);
    persistResource(Registry.get("tld").asBuilder()
        .setReservedLists(createReservedList())
        .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
        .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(readFile("domain_check_fee_reserved_sunrise_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_wrongCurrency_v06() throws Exception {
    setEppInput("domain_check_fee_euro_v06.xml");
    thrown.expect(CurrencyUnitMismatchException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_wrongCurrency_v11() throws Exception {
    setEppInput("domain_check_fee_euro_v11.xml");
    thrown.expect(CurrencyUnitMismatchException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_wrongCurrency_v12() throws Exception {
    setEppInput("domain_check_fee_euro_v12.xml");
    thrown.expect(CurrencyUnitMismatchException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v06() throws Exception {
    setEppInput("domain_check_fee_bad_period_v06.xml");
    thrown.expect(BadPeriodUnitException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v11() throws Exception {
    setEppInput("domain_check_fee_bad_period_v11.xml");
    thrown.expect(BadPeriodUnitException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v12() throws Exception {
    setEppInput("domain_check_fee_bad_period_v12.xml");
    thrown.expect(BadPeriodUnitException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v06() throws Exception {
    setEppInput("domain_check_fee_command_phase_v06.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v11() throws Exception {
    setEppInput("domain_check_fee_command_phase_v11.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v12() throws Exception {
    setEppInput("domain_check_fee_command_phase_v12.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandSubphase_v06() throws Exception {
    setEppInput("domain_check_fee_command_subphase_v06.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandSubphase_v11() throws Exception {
    setEppInput("domain_check_fee_command_subphase_v11.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_commandSubphase_v12() throws Exception {
    setEppInput("domain_check_fee_command_subphase_v12.xml");
    thrown.expect(FeeChecksDontSupportPhasesException.class);
    runFlow();
  }

  // This test is only relevant for v06, since domain names are not specified in v11 or v12.
  @Test
  public void testFeeExtension_feeCheckNotInAvailabilityCheck() throws Exception {
    setEppInput("domain_check_fee_not_in_avail.xml");
    thrown.expect(OnlyCheckedNamesCanBeFeeCheckedException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v06() throws Exception {
    setEppInput("domain_check_fee_multiyear_restore_v06.xml");
    thrown.expect(RestoresAreAlwaysForOneYearException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v11() throws Exception {
    setEppInput("domain_check_fee_multiyear_restore_v11.xml");
    thrown.expect(RestoresAreAlwaysForOneYearException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v12() throws Exception {
    setEppInput("domain_check_fee_multiyear_restore_v12.xml");
    thrown.expect(RestoresAreAlwaysForOneYearException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_unknownCommand_v06() throws Exception {
    setEppInput("domain_check_fee_unknown_command_v06.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_unknownCommand_v11() throws Exception {
    setEppInput("domain_check_fee_unknown_command_v11.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_unknownCommand_v12() throws Exception {
    setEppInput("domain_check_fee_unknown_command_v12.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_invalidCommand_v06() throws Exception {
    setEppInput("domain_check_fee_invalid_command_v06.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_invalidCommand_v11() throws Exception {
    setEppInput("domain_check_fee_invalid_command_v11.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  @Test
  public void testFeeExtension_invalidCommand_v12() throws Exception {
    setEppInput("domain_check_fee_invalid_command_v12.xml");
    thrown.expect(UnknownFeeCommandException.class);
    runFlow();
  }

  private void runEapFeeCheckTest(String inputFile, String outputFile) throws Exception {
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistActiveDomain("example1.tld");
    persistResource(Registry.get("tld").asBuilder()
        .setEapFeeSchedule(ImmutableSortedMap.of(
            START_OF_TIME, Money.of(USD, 0),
            clock.nowUtc().minusDays(1), Money.of(USD, 100),
            clock.nowUtc().plusDays(1), Money.of(USD, 50),
            clock.nowUtc().plusDays(2), Money.of(USD, 0)))
        .build());
    setEppInput(inputFile);
    runFlowAssertResponse(readFile(outputFile));
  }

  @Test
  public void testSuccess_eapFeeCheck_v06() throws Exception {
    runEapFeeCheckTest("domain_check_fee_v06.xml", "domain_check_eap_fee_response_v06.xml");
  }

  @Test
  public void testSuccess_eapFeeCheck_v11() throws Exception {
    runEapFeeCheckTest("domain_check_fee_v11.xml", "domain_check_eap_fee_response_v11.xml");
  }

  @Test
  public void testSuccess_eapFeeCheck_v12() throws Exception {
    runEapFeeCheckTest("domain_check_fee_v12.xml", "domain_check_eap_fee_response_v12.xml");
  }

  @Test
  public void testSuccess_eapFeeCheck_date_v12() throws Exception {
    runEapFeeCheckTest("domain_check_fee_date_v12.xml",
        "domain_check_eap_fee_response_date_v12.xml");
  }

  @Ignore
  @Test
  public void testSuccess_feeCheck_multipleRanges() throws Exception {
    // TODO: If at some point we have more than one type of fees that are time dependent, populate
    // this test to test if the notAfter date is the earliest of the end points of the ranges.
  }
}
