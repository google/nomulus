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

import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.eppoutput.CheckData.DomainCheck.create;
import static google.registry.model.registry.Registry.TldState.PREDELEGATION;
import static google.registry.model.registry.Registry.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.createTlds;
import static google.registry.testing.DatastoreHelper.loadRegistrar;
import static google.registry.testing.DatastoreHelper.persistActiveDomain;
import static google.registry.testing.DatastoreHelper.persistDeletedDomain;
import static google.registry.testing.DatastoreHelper.persistPremiumList;
import static google.registry.testing.DatastoreHelper.persistReservedList;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.Assert.assertThrows;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.UnknownCurrencyEppException;
import google.registry.flows.ResourceCheckFlowTestCase;
import google.registry.flows.domain.DomainCheckFlow.OnlyCheckedNamesCanBeFeeCheckedException;
import google.registry.flows.domain.DomainFlowUtils.BadCommandForRegistryPhaseException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNameCharacterException;
import google.registry.flows.domain.DomainFlowUtils.BadDomainNamePartsCountException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.DashesInThirdAndFourthException;
import google.registry.flows.domain.DomainFlowUtils.DomainLabelTooLongException;
import google.registry.flows.domain.DomainFlowUtils.DomainNameExistsAsTldException;
import google.registry.flows.domain.DomainFlowUtils.EmptyDomainNamePartException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.InvalidIdnDomainLabelException;
import google.registry.flows.domain.DomainFlowUtils.InvalidPunycodeException;
import google.registry.flows.domain.DomainFlowUtils.LeadingDashException;
import google.registry.flows.domain.DomainFlowUtils.NotAuthorizedForTldException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TldDoesNotExistException;
import google.registry.flows.domain.DomainFlowUtils.TrailingDashException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.UnknownFeeCommandException;
import google.registry.flows.exceptions.TooManyResourceChecksException;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldState;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/** Unit tests for {@link DomainCheckFlow}. */
public class DomainCheckFlowTest extends ResourceCheckFlowTestCase<DomainCheckFlow, DomainBase> {

  public DomainCheckFlowTest() {
    setEppInput("domain_check_one_tld.xml");
  }

  private ReservedList createReservedList() {
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("anchor.tld")
            .setToken("2fooBAR")
            .setTokenType(SINGLE_USE)
            .build());
    return persistReservedList(
        "tld-reserved",
        "allowedinsunrise,ALLOWED_IN_SUNRISE",
        "anchor,RESERVED_FOR_ANCHOR_TENANT",
        "collision,NAME_COLLISION",
        "premiumcollision,NAME_COLLISION",
        "reserved,FULLY_BLOCKED",
        "specificuse,RESERVED_FOR_SPECIFIC_USE");
  }

  @Before
  public void initCheckTest() {
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
  public void testSuccess_clTridNotSpecified() throws Exception {
    setEppInput("domain_check_no_cltrid.xml");
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_oneExists_allocationTokenIsInvalid() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(false, "example2.tld", "The allocation token is invalid"),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Allocation token required"));
  }

  @Test
  public void testSuccess_oneExists_allocationTokenIsValid() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder().setToken("abc123").setTokenType(SINGLE_USE).build());
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(true, "example2.tld", null),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Allocation token required"));
  }

  @Test
  public void testSuccess_oneExists_allocationTokenIsRedeemed() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryEntry(
                VKey.createOfy(HistoryEntry.class, Key.create(HistoryEntry.class, 1L)))
            .build());
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(false, "example2.tld", "Alloc token was already redeemed"),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Allocation token required"));
  }

  @Test
  public void testSuccess_oneExists_allocationTokenForReservedDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("specificuse.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Reserved"),
        create(true, "specificuse.tld", null));
  }

  @Test
  public void testSuccess_oneExists_allocationTokenForWrongDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistActiveDomain("example1.tld");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("someotherdomain.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    doCheckTest(
        create(false, "example1.tld", "In use"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Allocation token required"));
  }

  @Test
  public void testSuccess_notOutOfDateToken_forSpecificDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("specificuse.tld")
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for domain"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Reserved"),
        create(true, "specificuse.tld", null));
  }

  @Test
  public void testSuccess_outOfDateToken_forSpecificDomain() throws Exception {
    setEppInput("domain_check_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("specificuse.tld")
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(2), TokenStatus.VALID)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for domain"),
        create(false, "example2.tld", "Alloc token invalid for domain"),
        create(false, "reserved.tld", "Reserved"),
        create(false, "specificuse.tld", "Alloc token not in promo period"));
  }

  @Test
  public void testSuccess_nothingExists_reservationsOverrideInvalidAllocationTokens()
      throws Exception {
    setEppInput("domain_check_reserved_allocationtoken.xml");
    // Fill out these reasons
    doCheckTest(
        create(false, "collision.tld", "Cannot be delegated"),
        create(false, "reserved.tld", "Reserved"),
        create(false, "anchor.tld", "Allocation token required"),
        create(false, "allowedinsunrise.tld", "Reserved"),
        create(false, "premiumcollision.tld", "Cannot be delegated"));
  }

  @Test
  public void testSuccess_allocationTokenPromotion() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_response.xml"));
  }

  @Test
  public void testSuccess_promotionNotActive() throws Exception {
    createTld("example");
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
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(false, "example1.tld", "Alloc token not in promo period"),
        create(false, "example2.example", "Alloc token not in promo period"),
        create(false, "reserved.tld", "Reserved"));
  }

  @Test
  public void testSuccess_promoTokenNotValidForTld() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setAllowedTlds(ImmutableSet.of("example"))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for TLD"),
        create(true, "example2.example", null),
        create(false, "reserved.tld", "Reserved"));
  }

  @Test
  public void testSuccess_promoTokenNotValidForRegistrar() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setAllowedClientIds(ImmutableSet.of("someOtherClient"))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee.xml");
    doCheckTest(
        create(false, "example1.tld", "Alloc token invalid for client"),
        create(false, "example2.example", "Alloc token invalid for client"),
        create(false, "reserved.tld", "Reserved"));
  }

  @Test
  public void testSuccess_oneReservedInSunrise() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(Registry.get("tld").asBuilder().setReservedLists(createReservedList()).build());
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(true, "allowedinsunrise.tld", null),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_twoReservedOutsideSunrise() throws Exception {
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Reserved"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_domainWithMultipleReservationType_useMostSevereMessage()
      throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                createReservedList(),
                persistReservedList("tld-collision", "allowedinsunrise,NAME_COLLISION"))
            .build());
    setEppInput("domain_check_one_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld", "Reserved"),
        create(false, "allowedinsunrise.tld", "Cannot be delegated"),
        create(true, "example2.tld", null),
        create(true, "example3.tld", null));
  }

  @Test
  public void testSuccess_anchorTenantReserved() throws Exception {
    setEppInput("domain_check_anchor.xml");
    doCheckTest(create(false, "anchor.tld", "Allocation token required"));
  }

  @Test
  public void testSuccess_anchorTenantWithToken() throws Exception {
    setEppInput("domain_check_anchor_allocationtoken.xml");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("anchor.tld")
            .build());
    doCheckTest(create(true, "anchor.tld", null));
  }

  @Test
  public void testSuccess_multipartTld_oneReserved() throws Exception {
    createTld("tld.foo");
    persistResource(
        Registry.get("tld.foo")
            .asBuilder()
            .setReservedLists(
                persistReservedList(
                    "tld.foo", "reserved,FULLY_BLOCKED", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    setEppInput("domain_check_one_multipart_tld_reserved.xml");
    doCheckTest(
        create(false, "reserved.tld.foo", "Reserved"),
        create(false, "allowedinsunrise.tld.foo", "Reserved"),
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
    doCheckTest(create(true, "example1.tld", null), create(true, "example1.tld", null));
  }

  @Test
  public void testSuccess_xmlMatches() throws Exception {
    persistActiveDomain("example2.tld");
    runFlowAssertResponse(loadFile("domain_check_one_tld_response.xml"));
  }

  @Test
  public void testSuccess_50IdsAllowed() throws Exception {
    // Make sure we don't have a regression that reduces the number of allowed checks.
    setEppInput("domain_check_50.xml");
    runFlow();
  }

  @Test
  public void testSuccess_50IdsAllowed_withAllocationToken() throws Exception {
    setEppInput("domain_check_50_allocationtoken.xml");
    runFlow();
  }

  @Test
  public void testFailure_tooManyIds() {
    setEppInput("domain_check_51.xml");
    EppException thrown = assertThrows(TooManyResourceChecksException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_wrongTld() {
    setEppInput("domain_check.xml");
    EppException thrown = assertThrows(TldDoesNotExistException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_notAuthorizedForTld() {
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    EppException thrown = assertThrows(NotAuthorizedForTldException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testSuccess_superuserNotAuthorizedForTld() throws Exception {
    persistActiveDomain("example2.tld");
    persistResource(
        loadRegistrar("TheRegistrar").asBuilder().setAllowedTlds(ImmutableSet.of()).build());
    runFlowAssertResponse(
        CommitMode.LIVE, UserPrivileges.SUPERUSER, loadFile("domain_check_one_tld_response.xml"));
  }

  private void doFailingBadLabelTest(
      String label, Class<? extends EppException> expectedException) {
    setEppInput("domain_check_template.xml", ImmutableMap.of("LABEL", label));
    EppException thrown = assertThrows(expectedException, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFailure_uppercase() {
    doFailingBadLabelTest("FOO.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_badCharacter() {
    doFailingBadLabelTest("test_example.tld", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_leadingDash() {
    doFailingBadLabelTest("-example.tld", LeadingDashException.class);
  }

  @Test
  public void testFailure_trailingDash() {
    doFailingBadLabelTest("example-.tld", TrailingDashException.class);
  }

  @Test
  public void testFailure_tooLong() {
    doFailingBadLabelTest(Strings.repeat("a", 64) + ".tld", DomainLabelTooLongException.class);
  }

  @Test
  public void testFailure_leadingDot() {
    doFailingBadLabelTest(".example.tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_leadingDotTld() {
    doFailingBadLabelTest("foo..tld", EmptyDomainNamePartException.class);
  }

  @Test
  public void testFailure_tooManyParts() {
    doFailingBadLabelTest("foo.example.tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_tooFewParts() {
    doFailingBadLabelTest("tld", BadDomainNamePartsCountException.class);
  }

  @Test
  public void testFailure_domainNameExistsAsTld_lowercase() {
    createTlds("foo.tld", "tld");
    doFailingBadLabelTest("foo.tld", DomainNameExistsAsTldException.class);
  }

  @Test
  public void testFailure_domainNameExistsAsTld_uppercase() {
    createTlds("foo.tld", "tld");
    doFailingBadLabelTest("FOO.TLD", BadDomainNameCharacterException.class);
  }

  @Test
  public void testFailure_invalidPunycode() {
    doFailingBadLabelTest("xn--abcdefg.tld", InvalidPunycodeException.class);
  }

  @Test
  public void testFailure_dashesInThirdAndFourthPosition() {
    doFailingBadLabelTest("ab--cdefg.tld", DashesInThirdAndFourthException.class);
  }

  @Test
  public void testFailure_tldDoesNotExist() {
    doFailingBadLabelTest("foo.nosuchtld", TldDoesNotExistException.class);
  }

  @Test
  public void testFailure_invalidIdnCodePoints() {
    // ❤☀☆☂☻♞☯.tld
    doFailingBadLabelTest("xn--k3hel9n7bxlu1e.tld", InvalidIdnDomainLabelException.class);
  }

  @Test
  public void testFailure_predelegation() {
    createTld("tld", PREDELEGATION);
    EppException thrown = assertThrows(BadCommandForRegistryPhaseException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
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

  /** Test that premium names are shown as available even if the fee extension is not used. */
  @Test
  public void testAvailExtension_premiumDomainsAreAvailableWithoutExtension() throws Exception {
    createTld("example");
    setEppInput("domain_check_premium.xml");
    doCheckTest(create(true, "rich.example", null));
  }

  /** Test multiyear periods and explicitly correct currency and that the avail extension is ok. */
  @Test
  public void testFeeExtension_v06() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_multipleReservations() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList("example-sunrise", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_v11() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v11.xml"));
  }

  @Test
  public void testFeeExtension_v12() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  /**
   * Test commands for create, renew, transfer, restore and update with implicit period and
   * currency.
   */
  @Test
  public void testFeeExtension_multipleCommands_v06() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v06.xml"));
  }

  // Version 11 cannot have multiple commands.

  @Test
  public void testFeeExtension_multipleCommands_v12() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v12.xml"));
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  public void testFeeExtension_premiumLabels_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_premium_eap_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistResource(
        Registry.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(clock.nowUtc().minusDays(1), Money.of(USD, 100))
                    .put(clock.nowUtc().plusDays(1), Money.of(USD, 50))
                    .put(clock.nowUtc().plusDays(2), Money.of(USD, 0))
                    .build())
            .build());

    runFlowAssertResponse(loadFile("domain_check_fee_premium_eap_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_create() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_renew() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_renew.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_transfer() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_transfer.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_restore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v11_update() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_update.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_update.xml"));
  }

  @Test
  public void testFeeExtension_premiumLabels_v12() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_fractionalCost() throws Exception {
    // Note that the response xml expects to see "11.10" with two digits after the decimal point.
    // This works because Money.getAmount(), used in the flow, returns a BigDecimal that is set to
    // display the number of digits that is conventional for the given currency.
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setCreateBillingCost(Money.of(CurrencyUnit.USD, 11.1))
            .build());
    setEppInput("domain_check_fee_fractional.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_fractional_response.xml"));
  }

  /** Test that create fees are properly omitted/classed on names on reserved lists. */
  @Test
  public void testFeeExtension_reservedName_v06() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v06.xml"));
  }

  /** The tests must be split up for version 11, which allows only one command at a time. */
  @Test
  public void testFeeExtension_reservedName_v11_create() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_create.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_renew() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_renew.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_transfer() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_transfer.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v11_restore() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_reservedName_v12() throws Exception {
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v06.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_create()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_create.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_renew() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_renew.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_transfer()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_transfer.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_restore()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_restore.xml"));
  }

  @Test
  public void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v12() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v12.xml"));
  }

  @Test
  public void testFeeExtension_wrongCurrency_v06() {
    setEppInput("domain_check_fee_euro_v06.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_wrongCurrency_v11() {
    setEppInput("domain_check_fee_euro_v11.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_wrongCurrency_v12() {
    setEppInput("domain_check_fee_euro_v12.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_badCurrencyType() {
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "BAD"));
    EppException thrown = assertThrows(UnknownCurrencyEppException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v06() {
    setEppInput("domain_check_fee_bad_period_v06.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v11() {
    setEppInput("domain_check_fee_bad_period_v11.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_periodNotInYears_v12() {
    setEppInput("domain_check_fee_bad_period_v12.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v06() {
    setEppInput("domain_check_fee_command_phase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v11() {
    setEppInput("domain_check_fee_command_phase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandWithPhase_v12() {
    setEppInput("domain_check_fee_command_phase_v12.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandSubphase_v06() {
    setEppInput("domain_check_fee_command_subphase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandSubphase_v11() {
    setEppInput("domain_check_fee_command_subphase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_commandSubphase_v12() {
    setEppInput("domain_check_fee_command_subphase_v12.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // This test is only relevant for v06, since domain names are not specified in v11 or v12.
  @Test
  public void testFeeExtension_feeCheckNotInAvailabilityCheck() {
    setEppInput("domain_check_fee_not_in_avail.xml");
    EppException thrown =
        assertThrows(OnlyCheckedNamesCanBeFeeCheckedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v06() {
    setEppInput("domain_check_fee_multiyear_restore_v06.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v11() {
    setEppInput("domain_check_fee_multiyear_restore_v11.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearRestore_v12() {
    setEppInput("domain_check_fee_multiyear_restore_v12.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearTransfer_v06() {
    setEppInput("domain_check_fee_multiyear_transfer_v06.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearTransfer_v11() {
    setEppInput("domain_check_fee_multiyear_transfer_v11.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_multiyearTransfer_v12() {
    setEppInput("domain_check_fee_multiyear_transfer_v12.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_unknownCommand_v06() {
    setEppInput("domain_check_fee_unknown_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_unknownCommand_v11() {
    setEppInput("domain_check_fee_unknown_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_unknownCommand_v12() {
    setEppInput("domain_check_fee_unknown_command_v12.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_invalidCommand_v06() {
    setEppInput("domain_check_fee_invalid_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_invalidCommand_v11() {
    setEppInput("domain_check_fee_invalid_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  public void testFeeExtension_invalidCommand_v12() {
    setEppInput("domain_check_fee_invalid_command_v12.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  private void runEapFeeCheckTest(String inputFile, String outputFile) throws Exception {
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistActiveDomain("example1.tld");
    persistResource(
        Registry.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(clock.nowUtc().minusDays(1), Money.of(USD, 100))
                    .put(clock.nowUtc().plusDays(1), Money.of(USD, 50))
                    .put(clock.nowUtc().plusDays(2), Money.of(USD, 0))
                    .build())
            .build());
    setEppInput(inputFile, ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile(outputFile));
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
    runEapFeeCheckTest(
        "domain_check_fee_date_v12.xml", "domain_check_eap_fee_response_date_v12.xml");
  }

  @Ignore
  @Test
  public void testSuccess_feeCheck_multipleRanges() {
    // TODO: If at some point we have more than one type of fees that are time dependent, populate
    // this test to test if the notAfter date is the earliest of the end points of the ranges.
  }

  @Test
  public void testIcannActivityReportField_getsLogged() throws Exception {
    createTlds("com", "net", "org");
    setEppInput("domain_check.xml");
    runFlow();
    assertIcannReportingActivityFieldLogged("srs-dom-check");
    assertTldsFieldLogged("com", "net", "org");
  }
}
