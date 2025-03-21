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
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForCommandException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.model.tld.Tld;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaIntegrationTestExtension;
import google.registry.testing.DatabaseHelper;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@link AllocationTokenFlowUtils}. */
class AllocationTokenFlowUtilsTest {

  private final AllocationTokenFlowUtils flowUtils = new AllocationTokenFlowUtils();

  @RegisterExtension
  final JpaIntegrationTestExtension jpa =
      new JpaTestExtensions.Builder().buildIntegrationTestExtension();

  private final AllocationTokenExtension allocationTokenExtension =
      mock(AllocationTokenExtension.class);

  @BeforeEach
  void beforeEach() {
    createTld("tld");
  }

  @Test
  void test_validateToken_successfullyVerifiesValidTokenOnCreate() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("tokeN")
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.RESTORE))
                .setTokenType(SINGLE_USE)
                .build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            flowUtils
                .verifyAllocationTokenCreateIfPresent(
                    createCommand("blah.tld"),
                    Tld.get("tld"),
                    "TheRegistrar",
                    DateTime.now(UTC),
                    Optional.of(allocationTokenExtension))
                .get())
        .isEqualTo(token);
  }

  @Test
  void test_validateToken_successfullyVerifiesValidTokenExistingDomain() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("tokeN")
                .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.RENEW))
                .setTokenType(SINGLE_USE)
                .build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            flowUtils
                .verifyAllocationTokenIfPresent(
                    DatabaseHelper.newDomain("blah.tld"),
                    Tld.get("tld"),
                    "TheRegistrar",
                    DateTime.now(UTC),
                    CommandName.RENEW,
                    Optional.of(allocationTokenExtension))
                .get())
        .isEqualTo(token);
  }

  void test_validateToken_emptyAllowedEppActions_successfullyVerifiesValidTokenExistingDomain()
      throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    when(allocationTokenExtension.getAllocationToken()).thenReturn("tokeN");
    assertThat(
            flowUtils
                .verifyAllocationTokenIfPresent(
                    DatabaseHelper.newDomain("blah.tld"),
                    Tld.get("tld"),
                    "TheRegistrar",
                    DateTime.now(UTC),
                    CommandName.RENEW,
                    Optional.of(allocationTokenExtension))
                .get())
        .isEqualTo(token);
  }

  @Test
  void test_validateTokenCreate_failsOnNonexistentToken() {
    assertValidateCreateThrowsEppException(InvalidAllocationTokenException.class);
  }

  @Test
  void test_validateTokenExistingDomain_failsOnNonexistentToken() {
    assertValidateExistingDomainThrowsEppException(InvalidAllocationTokenException.class);
  }

  @Test
  void test_validateTokenCreate_failsOnNullToken() {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                InvalidAllocationTokenException.class,
                () ->
                    flowUtils.verifyAllocationTokenCreateIfPresent(
                        createCommand("blah.tld"),
                        Tld.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC),
                        Optional.of(allocationTokenExtension))))
        .marshalsToXml();
  }

  @Test
  void test_validateTokenExistingDomain_failsOnNullToken() {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                InvalidAllocationTokenException.class,
                () ->
                    flowUtils.verifyAllocationTokenIfPresent(
                        DatabaseHelper.newDomain("blah.tld"),
                        Tld.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC),
                        CommandName.RENEW,
                        Optional.of(allocationTokenExtension))))
        .marshalsToXml();
  }

  @Test
  void test_validateTokenCreate_invalidForClientId() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
            .build());
    assertValidateCreateThrowsEppException(AllocationTokenNotValidForRegistrarException.class);
  }

  @Test
  void test_validateTokenExistingDomain_invalidForClientId() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedRegistrarIds(ImmutableSet.of("NewRegistrar"))
            .build());
    assertValidateExistingDomainThrowsEppException(
        AllocationTokenNotValidForRegistrarException.class);
  }

  @Test
  void test_validateTokenCreate_invalidForTld() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedTlds(ImmutableSet.of("nottld"))
            .build());
    assertValidateCreateThrowsEppException(AllocationTokenNotValidForTldException.class);
  }

  @Test
  void test_validateTokenExistingDomain_invalidForTld() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedTlds(ImmutableSet.of("nottld"))
            .build());
    assertValidateExistingDomainThrowsEppException(AllocationTokenNotValidForTldException.class);
  }

  @Test
  void test_validateTokenCreate_beforePromoStart() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).plusDays(1)).build());
    assertValidateCreateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenExistingDomain_beforePromoStart() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).plusDays(1)).build());
    assertValidateExistingDomainThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenCreate_afterPromoEnd() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusMonths(2)).build());
    assertValidateCreateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenExistingDomain_afterPromoEnd() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusMonths(2)).build());
    assertValidateExistingDomainThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenCreate_promoCancelled() {
    // the promo would be valid, but it was cancelled 12 hours ago
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, NOT_STARTED)
                    .put(DateTime.now(UTC).minusMonths(1), VALID)
                    .put(DateTime.now(UTC).minusHours(12), CANCELLED)
                    .build())
            .build());
    assertValidateCreateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenExistingDomain_promoCancelled() {
    // the promo would be valid, but it was cancelled 12 hours ago
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, NOT_STARTED)
                    .put(DateTime.now(UTC).minusMonths(1), VALID)
                    .put(DateTime.now(UTC).minusHours(12), CANCELLED)
                    .build())
            .build());
    assertValidateExistingDomainThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  void test_validateTokenCreate_invalidCommand() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedEppActions(ImmutableSet.of(CommandName.RENEW))
            .build());
    assertValidateCreateThrowsEppException(AllocationTokenNotValidForCommandException.class);
  }

  @Test
  void test_validateTokenExistingDomain_invalidCommand() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE))
            .build());
    assertValidateExistingDomainThrowsEppException(
        AllocationTokenNotValidForCommandException.class);
  }

  @Test
  void test_checkDomainsWithToken_successfullyVerifiesValidToken() {
    persistResource(
        new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    assertThat(
            flowUtils
                .checkDomainsWithToken(
                    ImmutableList.of(
                        InternetDomainName.from("blah.tld"), InternetDomainName.from("blah2.tld")),
                    "tokeN",
                    "TheRegistrar",
                    DateTime.now(UTC))
                .domainCheckResults())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                InternetDomainName.from("blah.tld"), "", InternetDomainName.from("blah2.tld"), ""))
        .inOrder();
  }

  @Test
  void test_checkDomainsWithToken_showsFailureMessageForRedeemedToken() {
    Domain domain = persistActiveDomain("example.tld");
    HistoryEntryId historyEntryId = new HistoryEntryId(domain.getRepoId(), 1051L);
    persistResource(
        new AllocationToken.Builder()
            .setToken("tokeN")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryId(historyEntryId)
            .build());
    assertThat(
            flowUtils
                .checkDomainsWithToken(
                    ImmutableList.of(
                        InternetDomainName.from("blah.tld"), InternetDomainName.from("blah2.tld")),
                    "tokeN",
                    "TheRegistrar",
                    DateTime.now(UTC))
                .domainCheckResults())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                InternetDomainName.from("blah.tld"),
                "Alloc token was already redeemed",
                InternetDomainName.from("blah2.tld"),
                "Alloc token was already redeemed"))
        .inOrder();
  }

  private void assertValidateCreateThrowsEppException(Class<? extends EppException> clazz) {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                clazz,
                () ->
                    flowUtils.verifyAllocationTokenCreateIfPresent(
                        createCommand("blah.tld"),
                        Tld.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC),
                        Optional.of(allocationTokenExtension))))
        .marshalsToXml();
  }

  private void assertValidateExistingDomainThrowsEppException(Class<? extends EppException> clazz) {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                clazz,
                () ->
                    flowUtils.verifyAllocationTokenIfPresent(
                        DatabaseHelper.newDomain("blah.tld"),
                        Tld.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC),
                        CommandName.RENEW,
                        Optional.of(allocationTokenExtension))))
        .marshalsToXml();
  }

  private static DomainCommand.Create createCommand(String domainName) {
    DomainCommand.Create command = mock(DomainCommand.Create.class);
    when(command.getDomainName()).thenReturn(domainName);
    return command;
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
