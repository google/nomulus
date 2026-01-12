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
import static google.registry.flows.domain.DomainCheckFlowTest.createReservedList;
import static google.registry.flows.domain.DomainCheckFlowTest.setUpDefaultToken;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.model.eppoutput.CheckData.DomainCheck.create;
import static google.registry.model.tld.Tld.TldState.START_DATE_SUNRISE;
import static google.registry.testing.DatabaseHelper.assertNoBillingEvents;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistBillingRecurrenceForDomain;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Ordering;
import google.registry.flows.EppException;
import google.registry.flows.FlowUtils.NotLoggedInException;
import google.registry.flows.domain.DomainCheckFlow.OnlyCheckedNamesCanBeFeeCheckedException;
import google.registry.flows.domain.DomainFlowUtils.BadPeriodUnitException;
import google.registry.flows.domain.DomainFlowUtils.CurrencyUnitMismatchException;
import google.registry.flows.domain.DomainFlowUtils.FeeChecksDontSupportPhasesException;
import google.registry.flows.domain.DomainFlowUtils.RestoresAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.TransfersAreAlwaysForOneYearException;
import google.registry.flows.domain.DomainFlowUtils.UnknownFeeCommandException;
import google.registry.model.billing.BillingBase.Flag;
import google.registry.model.billing.BillingBase.Reason;
import google.registry.model.billing.BillingRecurrence;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainHistory;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.CheckData;
import google.registry.model.registrar.Registrar;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldState;
import google.registry.testing.DatabaseHelper;
import java.math.BigDecimal;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/** Tests for {@link DomainCheckFlow} that use the older (0.6, 0.11, 0.12) fee extensions. */
public class DomainCheckFlowOldFeeExtensionsTest
    extends ProductionSimulatingFeeExtensionsTest<DomainCheckFlow> {

  @BeforeEach
  void oldFeeExtensionTestBeforeEach() {
    createTld("tld", TldState.QUIET_PERIOD);
    persistResource(Tld.get("tld").asBuilder().setReservedLists(createReservedList()).build());
  }

  @Test
  void testNotLoggedIn_takesPrecedenceOverUndeclaredExtensions() {
    // Attempt to use the fee extension, but there is no login session and no supported extensions.
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    sessionMetadata.setRegistrarId(null);
    sessionMetadata.setServiceExtensionUris(ImmutableSet.of());
    // NotLoggedIn should be thrown, not UndeclaredServiceExtensionException.
    EppException thrown = assertThrows(NotLoggedInException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  void testFeeExtension_premiumLabels_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v06.xml"));
  }

  /** Test the same as {@link #testFeeExtension_multipleCommands_v06} with premium labels. */
  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v06() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v06.xml"));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior_renewPriceOnly()
      throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06_renew_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06_renew_only.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withNonPremiumRenewalBehavior_transferPriceOnly()
      throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(persistActiveDomain("rich.example"), NONPREMIUM, null);
    setEppInput("domain_check_fee_premium_v06_transfer_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06_transfer_only.xml",
            ImmutableMap.of("RENEWPRICE", "11.00")));
  }

  @Test
  void testFeeExtension_existingPremiumDomain_withSpecifiedRenewalBehavior() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(
        persistActiveDomain("rich.example"), SPECIFIED, Money.of(USD, new BigDecimal("15.55")));
    setEppInput("domain_check_fee_premium_v06.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_response_domain_exists_v06.xml",
            ImmutableMap.of("RENEWPRICE", "15.55")));
  }

  @Test
  void testFeeExtension_premium_eap_v06() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v06.xml");
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistResource(
        Tld.get("example")
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
  void testFeeExtension_premium_eap_v06_withRenewalOnRestore() throws Exception {
    createTld("example");
    DateTime startTime = DateTime.parse("2010-01-01T10:00:00Z");
    clock.setTo(startTime);
    persistResource(
        persistActiveDomain("rich.example")
            .asBuilder()
            .setDeletionTime(clock.nowUtc().plusDays(25))
            .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
            .build());
    persistPendingDeleteDomain("rich.example");
    setEppInput("domain_check_fee_premium_v06.xml");
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(startTime.minusDays(1), Money.of(USD, 100))
                    .put(startTime.plusDays(1), Money.of(USD, 50))
                    .put(startTime.plusDays(2), Money.of(USD, 0))
                    .build())
            .build());
    runFlowAssertResponse(loadFile("domain_check_fee_premium_eap_response_v06_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_create() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v11() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_renew() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_transfer() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_restore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_restore_withRenewal() throws Exception {
    setEppInput("domain_check_fee_premium_v11_restore.xml");
    createTld("example");
    persistPendingDeleteDomain("rich.example");
    runFlowAssertResponse(
        loadFile("domain_check_fee_premium_response_v11_restore_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v11_update() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v11_update.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v11_update.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v12() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_defaultTokenOnlyOnCreate_v12() throws Exception {
    setUpDefaultToken();
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_default_token_response_v12.xml"));
  }

  @Disabled("TODO(b/454680236): broken test")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v06() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_default_token_multiple_names_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_default_token_multiple_names_response_v06.xml"));
  }

  @Disabled("TODO(b/454680236): broken")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v11() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_default_token_multiple_names_v11.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_default_token_multiple_names_response_v11.xml"));
  }

  @Disabled("TODO(b/454680236): broken test")
  @Test
  void testFeeExtension_defaultToken_notValidForAllLabels_v12() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_default_token_multiple_names_v12.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_default_token_multiple_names_response_v12.xml"));
  }

  /**
   * Test commands for create, renew, transfer, restore and update with implicit period and
   * currency.
   */
  @Test
  void testFeeExtension_multipleCommands_v06() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_tokenNotValidForSome_v06() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.TRANSFER))
            .setDiscountFraction(0.1)
            .build());
    setEppInput("domain_check_fee_multiple_commands_allocationtoken_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_allocationtoken_response_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_defaultTokenOnlyOnCreate_v06() throws Exception {
    setUpDefaultToken();
    setEppInput("domain_check_fee_multiple_commands_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_default_token_response_v06.xml"));
  }

  // Version 11 cannot have multiple commands.

  @Test
  void testFeeExtension_multipleCommands_v12() throws Exception {
    setEppInput("domain_check_fee_multiple_commands_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_commands_response_v12.xml"));
  }

  @Test
  void testFeeExtension_multipleCommands_tokenNotValidForSome_v12() throws Exception {
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.TRANSFER))
            .setDiscountFraction(0.1)
            .build());
    setEppInput("domain_check_fee_multiple_commands_allocationtoken_v12.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_multiple_commands_allocationtoken_response_v12.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v06.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v06_withRestoreRenewals()
      throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    persistPendingDeleteDomain("reserved.tld");
    persistPendingDeleteDomain("allowedinsunrise.tld");
    persistPendingDeleteDomain("collision.tld");
    persistPendingDeleteDomain("premiumcollision.tld");
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_reserved_sunrise_response_v06_with_renewals.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_create() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_renew() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_transfer() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v11_restore() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_feesNotOmittedOnReservedNamesInSunrise_v12() throws Exception {
    createTld("tld", START_DATE_SUNRISE);
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_sunrise_response_v12.xml"));
  }

  @Test
  void testFeeExtension_wrongCurrency_v06() {
    setEppInput("domain_check_fee_euro_v06.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_wrongCurrency_v11() {
    setEppInput("domain_check_fee_euro_v11.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v06() {
    setEppInput("domain_check_fee_invalid_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v11() {
    setEppInput("domain_check_fee_invalid_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_invalidCommand_v12() {
    setEppInput("domain_check_fee_invalid_command_v12.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_reservedName_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v06.xml"));
  }

  @Test
  void testFeeExtension_reservedName_restoreFeeWithDupes_v06() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    // The domain needs to exist in order for it to be loaded to check for restore fee.
    persistBillingRecurrenceForDomain(persistActiveDomain("allowedinsunrise.tld"), DEFAULT, null);
    setEppInput("domain_check_fee_reserved_dupes_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_dupes_v06.xml"));
  }

  /** The tests must be split up for version 11, which allows only one command at a time. */
  @Test
  void testFeeExtension_reservedName_v11_create() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_create.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_create.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_renew() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_renew.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_renew.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_transfer() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_transfer.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_transfer.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_restore() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v11_restore.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v11_restore_withRenewals() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    persistPendingDeleteDomain("reserved.tld");
    persistPendingDeleteDomain("allowedinsunrise.tld");
    persistPendingDeleteDomain("collision.tld");
    persistPendingDeleteDomain("premiumcollision.tld");
    setEppInput("domain_check_fee_reserved_v11_restore.xml");
    runFlowAssertResponse(
        loadFile("domain_check_fee_reserved_response_v11_restore_with_renewals.xml"));
  }

  @Test
  void testFeeExtension_reservedName_v12() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    setEppInput("domain_check_fee_reserved_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_response_v12.xml"));
  }

  @Test
  void testFeeExtension_reservedName_restoreFeeWithDupes_v12() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(createReservedList())
            .setPremiumList(persistPremiumList("tld", USD, "premiumcollision,USD 70"))
            .build());
    // The domain needs to exist in order for it to be loaded to check for restore fee.
    setEppInput("domain_check_fee_reserved_dupes_v12.xml");
    persistBillingRecurrenceForDomain(persistActiveDomain("allowedinsunrise.tld"), DEFAULT, null);
    runFlowAssertResponse(loadFile("domain_check_fee_reserved_dupes_response_v12.xml"));
  }

  @Test
  void testFeeExtension_periodNotInYears_v06() {
    setEppInput("domain_check_fee_bad_period_v06.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_periodNotInYears_v11() {
    setEppInput("domain_check_fee_bad_period_v11.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCommand_v06() {
    setEppInput("domain_check_fee_unknown_command_v06.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCommand_v11() {
    setEppInput("domain_check_fee_unknown_command_v11.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  /** Test multiyear periods and explicitly correct currency and that the avail extension is ok. */
  @Test
  void testFeeExtension_v06() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v06() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v06.xml"));
  }

  @Test
  void testFeeExtension_multipleReservations() throws Exception {
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setReservedLists(
                persistReservedList("example-sunrise", "allowedinsunrise,ALLOWED_IN_SUNRISE"))
            .build());
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_v06.xml"));
  }

  @Test
  void testFeeExtension_v11() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v11.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v11() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v11.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v11.xml"));
  }

  @Test
  void testFeeExtension_v12() throws Exception {
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  @Test
  void testFeeExtension_defaultToken_v12() throws Exception {
    setUpDefaultToken();
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml", ImmutableMap.of("CURRENCY", "USD"));
    runFlowAssertResponse(loadFile("domain_check_fee_response_default_token_v12.xml"));
  }

  @Test
  void testFeeExtension_premiumLabels_v12_specifiedPriceRenewal_renewPriceOnly() throws Exception {
    createTld("example");
    persistBillingRecurrenceForDomain(
        persistActiveDomain("rich.example"), SPECIFIED, Money.of(USD, new BigDecimal("27.74")));
    setEppInput("domain_check_fee_premium_v12_renew_only.xml");
    runFlowAssertResponse(
        loadFile(
            "domain_check_fee_premium_response_v12_renew_only.xml",
            ImmutableMap.of("RENEWPRICE", "27.74")));
  }

  @Test
  void testFeeExtension_premiumLabels_doesNotApplyDefaultToken_v12() throws Exception {
    createTld("example");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setAllowedTlds(ImmutableSet.of("example"))
                .setDiscountPremiums(false)
                .setDiscountFraction(0.5)
                .build());
    persistResource(
        Tld.get("example")
            .asBuilder()
            .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
            .build());
    setEppInput("domain_check_fee_premium_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12.xml"));
  }

  @Test
  void testFeeExtension_commandSubphase_v06() {
    setEppInput("domain_check_fee_command_subphase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandSubphase_v11() {
    setEppInput("domain_check_fee_command_subphase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  // This test is only relevant for v06, since domain names are not specified after.
  @Test
  void testFeeExtension_feeCheckNotInAvailabilityCheck() {
    setEppInput("domain_check_fee_not_in_avail.xml");
    EppException thrown =
        assertThrows(OnlyCheckedNamesCanBeFeeCheckedException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearRestore_v06() {
    setEppInput("domain_check_fee_multiyear_restore_v06.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearRestore_v11() {
    setEppInput("domain_check_fee_multiyear_restore_v11.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearTransfer_v06() {
    setEppInput("domain_check_fee_multiyear_transfer_v06.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearTransfer_v11() {
    setEppInput("domain_check_fee_multiyear_transfer_v11.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_premiumLabels_v12_withRenewalOnRestore() throws Exception {
    createTld("example");
    setEppInput("domain_check_fee_premium_v12.xml");
    persistPendingDeleteDomain("rich.example");
    runFlowAssertResponse(loadFile("domain_check_fee_premium_response_v12_with_renewal.xml"));
  }

  @Test
  void testFeeExtension_commandWithPhase_v06() {
    setEppInput("domain_check_fee_command_phase_v06.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandWithPhase_v11() {
    setEppInput("domain_check_fee_command_phase_v11.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearRestore_v12() {
    setEppInput("domain_check_fee_multiyear_restore_v12.xml");
    EppException thrown = assertThrows(RestoresAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_multiyearTransfer_v12() {
    setEppInput("domain_check_fee_multiyear_transfer_v12.xml");
    EppException thrown = assertThrows(TransfersAreAlwaysForOneYearException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_unknownCommand_v12() {
    setEppInput("domain_check_fee_unknown_command_v12.xml");
    EppException thrown = assertThrows(UnknownFeeCommandException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_wrongCurrency_v12() {
    setEppInput("domain_check_fee_euro_v12.xml");
    EppException thrown = assertThrows(CurrencyUnitMismatchException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_periodNotInYears_v12() {
    setEppInput("domain_check_fee_bad_period_v12.xml");
    EppException thrown = assertThrows(BadPeriodUnitException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandWithPhase_v12() {
    setEppInput("domain_check_fee_command_phase_v12.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testFeeExtension_commandSubphase_v12() {
    setEppInput("domain_check_fee_command_subphase_v12.xml");
    EppException thrown = assertThrows(FeeChecksDontSupportPhasesException.class, this::runFlow);
    assertAboutEppExceptions().that(thrown).marshalsToXml();
  }

  @Test
  void testSuccess_multipleCurencies_v12() throws Exception {
    persistResource(
        createTld("example")
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
    persistResource(
        Registrar.loadByRegistrarId("TheRegistrar")
            .get()
            .asBuilder()
            .setBillingAccountMap(ImmutableMap.of(USD, "foo", JPY, "bar"))
            .build());
    setEppInput("domain_check_fee_multiple_currencies_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_multiple_currencies_response_v12.xml"));
  }

  @Test
  void testTieredPricingPromoResponse_v12() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    setUpDefaultToken("NewRegistrar");
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_tiered_promotion_fee_response_v12.xml"));
  }

  @Test
  void testTieredPricingPromo_registrarNotIncluded_standardResponse_v12() throws Exception {
    setUpDefaultToken("NewRegistrar");
    persistActiveDomain("example1.tld");
    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  @Test
  void testTieredPricingPromo_registrarIncluded_noTokenActive_v12() throws Exception {
    sessionMetadata.setRegistrarId("NewRegistrar");
    persistActiveDomain("example1.tld");

    persistResource(
        setUpDefaultToken("NewRegistrar")
            .asBuilder()
            .setTokenStatusTransitions(
                ImmutableSortedMap.of(
                    START_OF_TIME,
                    TokenStatus.NOT_STARTED,
                    clock.nowUtc().plusDays(1),
                    TokenStatus.VALID))
            .build());

    setEppInput("domain_check_fee_v12.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_response_v12.xml"));
  }

  @Test
  void testFeeExtension_fractionalCost_v06() throws Exception {
    // Note that the response xml expects to see "11.10" with two digits after the decimal point.
    // This works because Money.getAmount(), used in the flow, returns a BigDecimal that is set to
    // display the number of digits that is conventional for the given currency.
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setCreateBillingCostTransitions(
                ImmutableSortedMap.of(START_OF_TIME, Money.of(USD, 11.1)))
            .build());
    setEppInput("domain_check_fee_fractional_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_fee_fractional_response_v06.xml"));
  }

  @Test
  void testSuccess_allocationTokenPromotion_doesNotUseValidDefaultToken_singleYear_v06()
      throws Exception {
    setUpDefaultToken();
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setDiscountYears(2)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_response_v06.xml"));
  }

  @Test
  void testSuccess_thirtyDomains_restoreFees_v06() throws Exception {
    // Note that 30 is more than 25, which is the maximum # of entity groups you can enlist in a
    // single database transaction (each Domain entity is in a separate entity group).
    // It's also pretty common for registrars to send large domain checks.
    setEppInput("domain_check_fee_thirty_domains_v06.xml");
    // example-00.tld won't exist and thus will not have a renew fee like the others.
    for (int i = 1; i < 30; i++) {
      persistPendingDeleteDomain(String.format("example-%02d.tld", i));
    }
    runFlowAssertResponse(loadFile("domain_check_fee_response_thirty_domains_v06.xml"));
  }

  @Test
  void testSuccess_allocationTokenPromotion_multiYear_v06() throws Exception {
    createTld("tld");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("single.tld")
            .setDiscountFraction(0.444)
            .setDiscountYears(2)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput(
        "domain_check_allocationtoken_promotion_v06.xml", ImmutableMap.of("DOMAIN", "single.tld"));
    // 1-yr: 13 * .556
    // 2-yr: (13 + 11) * .556
    // 5-yr: 2-yr-cost + 3 * 11
    runFlowAssertResponse(
        loadFile(
            "domain_check_allocationtoken_promotion_response_v06.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "single.tld")
                .put("COST_1YR", "7.23")
                .put("COST_2YR", "13.34")
                .put("COST_5YR", "46.34")
                .put("FEE_CLASS", "")
                .build()));
  }

  @Test
  void testSuccess_allocationTokenPromotion_multiYearAndPremiums() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setDomainName("rich.example")
            .setDiscountFraction(0.9)
            .setDiscountYears(3)
            .setDiscountPremiums(true)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput(
        "domain_check_allocationtoken_promotion_v06.xml",
        ImmutableMap.of("DOMAIN", "rich.example"));
    runFlowAssertResponse(
        loadFile(
            "domain_check_allocationtoken_promotion_response_v06.xml",
            new ImmutableMap.Builder<String, String>()
                .put("DOMAIN", "rich.example")
                .put("COST_1YR", "10.00")
                .put("COST_2YR", "20.00")
                .put("COST_5YR", "230.00")
                .put("FEE_CLASS", "<fee:class>premium</fee:class>")
                .build()));
  }

  @Test
  void testSuccess_allocationToken_premiumAnchorTenant_noFee_v06() throws Exception {
    createTld("example");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setPremiumList(persistPremiumList("example1", USD, "example1,USD 100"))
            .build());
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .setRegistrationBehavior(AllocationToken.RegistrationBehavior.ANCHOR_TENANT)
            .setDomainName("example1.tld")
            .build());
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_anchor_response_v06.xml"));
  }

  @Test
  void testSuccess_promotionNotActive_v06() throws Exception {
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
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    assertMutatingFlow(false);
    assertThat(((CheckData) runFlow().getResponse().getResponseData().get(0)).getChecks())
        .containsExactly(
            create(false, "example1.tld", "Alloc token not in promo period"),
            create(false, "example2.example", "Alloc token not in promo period"),
            create(false, "reserved.tld", "Alloc token not in promo period"),
            create(false, "rich.example", "Alloc token not in promo period"));
    assertNoBillingEvents(); // Checks are always free.
    assertNoHistory(); // Checks don't create a history event.
  }

  @Test
  void testSuccess_allocationTokenPromotion_singleYear_v06() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setDiscountFraction(0.5)
            .setDiscountYears(2)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    runFlowAssertResponse(loadFile("domain_check_allocationtoken_fee_response_v06.xml"));
  }

  @Test
  void testSuccess_promoTokenNotValidForTld_v06() throws Exception {
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
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    assertMutatingFlow(false);
    assertThat(((CheckData) runFlow().getResponse().getResponseData().get(0)).getChecks())
        .containsExactly(
            create(true, "example1.tld", null),
            create(true, "example2.example", null),
            create(false, "reserved.tld", "Reserved"),
            create(true, "rich.example", null));
    assertNoBillingEvents(); // Checks are always free.
    assertNoHistory(); // Checks don't create a history event.
  }

  @Test
  void testSuccess_promoTokenNotValidForRegistrar_v06() throws Exception {
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setToken("abc123")
            .setTokenType(UNLIMITED_USE)
            .setDiscountFraction(0.5)
            .setAllowedRegistrarIds(ImmutableSet.of("someOtherClient"))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(clock.nowUtc().minusDays(1), TokenStatus.VALID)
                    .put(clock.nowUtc().plusDays(1), TokenStatus.ENDED)
                    .build())
            .build());
    setEppInput("domain_check_allocationtoken_fee_v06.xml");
    assertMutatingFlow(false);
    assertThat(((CheckData) runFlow().getResponse().getResponseData().get(0)).getChecks())
        .containsExactly(
            create(false, "example1.tld", "Alloc token invalid for client"),
            create(false, "example2.example", "Alloc token invalid for client"),
            create(false, "reserved.tld", "Alloc token invalid for client"),
            create(false, "rich.example", "Alloc token invalid for client"));
    assertNoBillingEvents(); // Checks are always free.
    assertNoHistory(); // Checks don't create a history event.
  }

  @Test
  void testSuccess_allocationTokenForReservedDomain_showsFee_v06() throws Exception {
    setEppInput("domain_check_allocationtoken_fee_specificuse_v06.xml");
    createTld("example");
    persistResource(
        new AllocationToken.Builder()
            .setDomainName("specificuse.tld")
            .setToken("abc123")
            .setTokenType(SINGLE_USE)
            .build());
    // Fees are shown for all non-reserved domains and the reserved domain matching this
    // allocation token.
    runFlowAssertResponse(
        loadFile("domain_check_allocationtoken_fee_specificuse_response_v06.xml"));
  }

  @Test
  void testSuccess_eapFeeCheck_date_v12() throws Exception {
    runEapFeeCheckTestWithXmlInputOutput(
        loadFile("domain_check_fee_date_v12.xml", ImmutableMap.of("CURRENCY", "USD")),
        loadFile("domain_check_eap_fee_response_date_v12.xml"));
  }

  @Test
  void testSuccess_eapFeeCheck_v06() throws Exception {
    runEapFeeCheckTestWithXmlInputOutput(
        loadFile("domain_check_fee_v06.xml", ImmutableMap.of("CURRENCY", "USD")),
        loadFile("domain_check_eap_fee_response_v06.xml"));
  }

  @Test
  void testSuccess_eapFeeCheck_v11() throws Exception {
    runEapFeeCheckTestWithXmlInputOutput(
        loadFile("domain_check_fee_v11.xml", ImmutableMap.of("CURRENCY", "USD")),
        loadFile("domain_check_eap_fee_response_v11.xml"));
  }

  @Test
  void testSuccess_eapFeeCheck_v12() throws Exception {
    runEapFeeCheckTestWithXmlInputOutput(
        loadFile("domain_check_fee_v12.xml", ImmutableMap.of("CURRENCY", "USD")),
        loadFile("domain_check_eap_fee_response_v12.xml"));
  }

  private Domain persistPendingDeleteDomain(String domainName) {
    Domain existingDomain =
        persistResource(
            DatabaseHelper.newDomain(domainName)
                .asBuilder()
                .setDeletionTime(clock.nowUtc().plusDays(25))
                .setRegistrationExpirationTime(clock.nowUtc().minusDays(1))
                .setStatusValues(ImmutableSet.of(StatusValue.PENDING_DELETE))
                .build());
    DomainHistory historyEntry =
        persistResource(
            new DomainHistory.Builder()
                .setDomain(existingDomain)
                .setType(HistoryEntry.Type.DOMAIN_DELETE)
                .setModificationTime(existingDomain.getCreationTime())
                .setRegistrarId(existingDomain.getCreationRegistrarId())
                .build());
    BillingRecurrence renewEvent =
        persistResource(
            new BillingRecurrence.Builder()
                .setReason(Reason.RENEW)
                .setFlags(ImmutableSet.of(Flag.AUTO_RENEW))
                .setTargetId(existingDomain.getDomainName())
                .setRegistrarId("TheRegistrar")
                .setEventTime(existingDomain.getCreationTime())
                .setRecurrenceEndTime(clock.nowUtc())
                .setDomainHistory(historyEntry)
                .build());
    return persistResource(
        existingDomain.asBuilder().setAutorenewBillingEvent(renewEvent.createVKey()).build());
  }

  private void runEapFeeCheckTestWithXmlInputOutput(String inputXml, String outputXml)
      throws Exception {
    clock.setTo(DateTime.parse("2010-01-01T10:00:00Z"));
    persistActiveDomain("example1.tld");
    persistResource(
        Tld.get("tld")
            .asBuilder()
            .setEapFeeSchedule(
                new ImmutableSortedMap.Builder<DateTime, Money>(Ordering.natural())
                    .put(START_OF_TIME, Money.of(USD, 0))
                    .put(clock.nowUtc().minusDays(1), Money.of(USD, 100))
                    .put(clock.nowUtc().plusDays(1), Money.of(USD, 50))
                    .put(clock.nowUtc().plusDays(2), Money.of(USD, 0))
                    .build())
            .build());
    setEppInputXml(inputXml);
    runFlowAssertResponse(outputXml);
  }
}
