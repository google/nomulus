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

package google.registry.flows.domain.token;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.NonexistentAllocationTokenException;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.FakeClock;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AllocationTokenFlowUtils}. */
class AllocationTokenFlowUtilsTest {

  private final FakeClock clock = new FakeClock(DateTime.parse("2025-01-10T01:00:00.000Z"));

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().withClock(clock).buildIntegrationTestExtension();

  private final AllocationTokenExtension allocationTokenExtension =
      mock(AllocationTokenExtension.class);

  private Tld tld;

  @BeforeEach
  void beforeEach() {
    tld = createTld("tld");
  }

  @Test
  void testSuccess_redeemsToken() {
    HistoryEntryId historyEntryId = new HistoryEntryId("repoId", 10L);
    assertThat(
            AllocationTokenFlowUtils.redeemToken(singleUseTokenBuilder().build(), historyEntryId)
                .getRedemptionHistoryId())
        .hasValue(historyEntryId);
  }

  @Test
  void testInvalidForPremiumName_validForPremium() {
    AllocationToken token = singleUseTokenBuilder().setDiscountPremiums(true).build();
    assertThat(AllocationTokenFlowUtils.discountTokenInvalidForPremiumName(token, true)).isFalse();
  }

  @Test
  void testInvalidForPremiumName_notPremium() {
    assertThat(
            AllocationTokenFlowUtils.discountTokenInvalidForPremiumName(
                singleUseTokenBuilder().build(), false))
        .isFalse();
  }

  @Test
  void testInvalidForPremiumName_invalidForPremium() {
    assertThat(
            AllocationTokenFlowUtils.discountTokenInvalidForPremiumName(
                singleUseTokenBuilder().build(), true))
        .isTrue();
  }

  @Test
  void testSuccess_loadFromExtension() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("tokeN")
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setTokenType(SINGLE_USE)
                .build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            AllocationTokenFlowUtils.loadAllocationTokenFromExtension(
                "TheRegistrar",
                "example.tld",
                clock.nowUtc(),
                Optional.of(allocationTokenExtension)))
        .hasValue(token);
  }

  @Test
  void testSuccess_loadOrDefault_fromExtensionEvenWhenDefaultPresent() throws Exception {
    persistDefaultToken();
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("tokeN")
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
                .setTokenType(SINGLE_USE)
                .build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.of(allocationTokenExtension),
                tld,
                "example.tld",
                CommandName.CREATE))
        .hasValue(token);
  }

  @Test
  void testSuccess_loadOrDefault_defaultWhenNonePresent() throws Exception {
    AllocationToken defaultToken = persistDefaultToken();
    assertThat(
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.empty(),
                tld,
                "example.tld",
                CommandName.CREATE))
        .hasValue(defaultToken);
  }

  @Test
  void testSuccess_loadOrDefault_defaultWhenTokenIsPresentButNotApplicable() throws Exception {
    AllocationToken defaultToken = persistDefaultToken();
    persistResource(
        new AllocationToken.Builder()
            .setToken("tokeN")
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .setTokenType(SINGLE_USE)
            .setAllowedTlds(ImmutableSet.of("othertld"))
            .build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.of(allocationTokenExtension),
                tld,
                "example.tld",
                CommandName.CREATE))
        .hasValue(defaultToken);
  }

  @Test
  void testValidAgainstDomain_validAllReasons() {
    AllocationToken token = singleUseTokenBuilder().setDiscountPremiums(true).build();
    assertThat(
            AllocationTokenFlowUtils.tokenIsValidAgainstDomain(
                InternetDomainName.from("rich.tld"), token, CommandName.CREATE, clock.nowUtc()))
        .isTrue();
  }

  @Test
  void testValidAgainstDomain_invalidPremium() {
    AllocationToken token = singleUseTokenBuilder().build();
    assertThat(
            AllocationTokenFlowUtils.tokenIsValidAgainstDomain(
                InternetDomainName.from("rich.tld"), token, CommandName.CREATE, clock.nowUtc()))
        .isFalse();
  }

  @Test
  void testValidAgainstDomain_invalidAction() {
    AllocationToken token =
        singleUseTokenBuilder().setAllowedEppActions(ImmutableSet.of(CommandName.RESTORE)).build();
    assertThat(
            AllocationTokenFlowUtils.tokenIsValidAgainstDomain(
                InternetDomainName.from("domain.tld"), token, CommandName.CREATE, clock.nowUtc()))
        .isFalse();
  }

  @Test
  void testValidAgainstDomain_invalidTld() {
    createTld("othertld");
    AllocationToken token = singleUseTokenBuilder().build();
    assertThat(
            AllocationTokenFlowUtils.tokenIsValidAgainstDomain(
                InternetDomainName.from("domain.othertld"),
                token,
                CommandName.CREATE,
                clock.nowUtc()))
        .isFalse();
  }

  @Test
  void testValidAgainstDomain_invalidDomain() {
    AllocationToken token = singleUseTokenBuilder().setDomainName("anchor.tld").build();
    assertThat(
            AllocationTokenFlowUtils.tokenIsValidAgainstDomain(
                InternetDomainName.from("domain.tld"), token, CommandName.CREATE, clock.nowUtc()))
        .isFalse();
  }

  @Test
  void testFailure_redeemToken_nonSingleUse() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AllocationTokenFlowUtils.redeemToken(
                createOneMonthPromoTokenBuilder(clock.nowUtc()).build(),
                new HistoryEntryId("repoId", 10L)));
  }

  @Test
  void testFailure_loadFromExtension_nonexistentToken() {
    assertLoadTokenFromExtensionThrowsException(NonexistentAllocationTokenException.class);
  }

  @Test
  void testFailure_loadFromExtension_nullToken() {
    when(allocationTokenExtension.getAllocationToken()).thenReturn(null);
    assertLoadTokenFromExtensionThrowsException(NonexistentAllocationTokenException.class);
  }

  @Test
  void testFailure_tokenInvalidForRegistrar() {
    persistResource(
        createOneMonthPromoTokenBuilder(clock.nowUtc().minusDays(1))
            .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
            .build());
    assertLoadTokenFromExtensionThrowsException(AllocationTokenNotValidForRegistrarException.class);
  }

  @Test
  void testFailure_beforePromoStart() {
    persistResource(createOneMonthPromoTokenBuilder(clock.nowUtc().plusDays(1)).build());
    assertLoadTokenFromExtensionThrowsException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void testFailure_afterPromoEnd() {
    persistResource(createOneMonthPromoTokenBuilder(clock.nowUtc().minusMonths(2)).build());
    assertLoadTokenFromExtensionThrowsException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void testFailure_promoCancelled() {
    // the promo would be valid, but it was cancelled 12 hours ago
    persistResource(
        createOneMonthPromoTokenBuilder(clock.nowUtc().minusDays(1))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, NOT_STARTED)
                    .put(clock.nowUtc().minusMonths(1), VALID)
                    .put(clock.nowUtc().minusHours(12), CANCELLED)
                    .build())
            .build());
    assertLoadTokenFromExtensionThrowsException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void testFailure_loadOrDefault_badTokenProvided() throws Exception {
    when(allocationTokenExtension.getAllocationToken()).thenReturn("asdf");
    assertThrows(
        NonexistentAllocationTokenException.class,
        () ->
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.of(allocationTokenExtension),
                tld,
                "example.tld",
                CommandName.CREATE));
  }

  @Test
  void testFailure_loadOrDefault_noValidTokens() throws Exception {
    assertThat(
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.empty(),
                tld,
                "example.tld",
                CommandName.CREATE))
        .isEmpty();
  }

  @Test
  void testFailure_loadOrDefault_badDomainName() throws Exception {
    // Tokens tied to a domain should throw a catastrophic exception if used for a different domain
    persistResource(singleUseTokenBuilder().setDomainName("someotherdomain.tld").build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThrows(
        AllocationTokenFlowUtils.AllocationTokenNotValidForDomainException.class,
        () ->
            AllocationTokenFlowUtils.loadTokenFromExtensionOrGetDefault(
                "TheRegistrar",
                clock.nowUtc(),
                Optional.of(allocationTokenExtension),
                tld,
                "example.tld",
                CommandName.CREATE));
  }

  private AllocationToken persistDefaultToken() {
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("defaultToken")
                .setDiscountFraction(0.1)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setTokenType(DEFAULT_PROMO)
                .build());
    tld =
        persistResource(
            tld.asBuilder()
                .setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey()))
                .build());
    return defaultToken;
  }

  private void assertLoadTokenFromExtensionThrowsException(Class<? extends EppException> clazz) {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                clazz,
                () ->
                    AllocationTokenFlowUtils.loadAllocationTokenFromExtension(
                        "TheRegistrar",
                        "example.tld",
                        clock.nowUtc(),
                        Optional.of(allocationTokenExtension))))
        .marshalsToXml();
  }

  private AllocationToken.Builder singleUseTokenBuilder() {
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    return new AllocationToken.Builder()
        .setTokenType(SINGLE_USE)
        .setToken("tokeN")
        .setAllowedTlds(ImmutableSet.of("tld"))
        .setDiscountFraction(0.1)
        .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"));
  }

  private AllocationToken.Builder createOneMonthPromoTokenBuilder(DateTime promoStart) {
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    return new AllocationToken.Builder()
        .setToken("tokeN")
        .setTokenType(UNLIMITED_USE)
        .setTokenStatusTransitions(
            ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                .put(START_OF_TIME, NOT_STARTED)
                .put(promoStart, VALID)
                .put(promoStart.plusMonths(1), ENDED)
                .build());
  }
}
