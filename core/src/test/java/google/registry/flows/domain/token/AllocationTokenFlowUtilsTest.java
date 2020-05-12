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
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.testing.DatastoreHelper.persistResource;
import static google.registry.testing.EppExceptionSubject.assertAboutEppExceptions;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.InternetDomainName;
import com.googlecode.objectify.Key;
import google.registry.flows.EppException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotInPromotionException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForRegistrarException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.AllocationTokenNotValidForTldException;
import google.registry.flows.domain.token.AllocationTokenFlowUtils.InvalidAllocationTokenException;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.registry.Registry;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ShardableTestCase;
import java.util.function.Function;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AllocationTokenFlowUtils}. */
@RunWith(JUnit4.class)
public class AllocationTokenFlowUtilsTest extends ShardableTestCase {

  private final AllocationTokenFlowUtils flowUtils =
      new AllocationTokenFlowUtils(new AllocationTokenCustomLogic());

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Before
  public void initTest() {
    createTld("tld");
  }

  @Test
  public void test_validateToken_successfullyVerifiesValidToken() throws Exception {
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    assertThat(
            flowUtils.loadTokenAndValidateDomainCreate(
                createCommand("blah.tld"),
                "tokeN",
                Registry.get("tld"),
                "TheRegistrar",
                DateTime.now(UTC)))
        .isEqualTo(token);
  }

  @Test
  public void test_validateToken_failsOnNonexistentToken() {
    assertValidateThrowsEppException(InvalidAllocationTokenException.class);
  }

  @Test
  public void test_validateToken_failsOnNullToken() {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                InvalidAllocationTokenException.class,
                () ->
                    flowUtils.loadTokenAndValidateDomainCreate(
                        createCommand("blah.tld"),
                        null,
                        Registry.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC))))
        .marshalsToXml();
  }

  @Test
  public void test_validateToken_callsCustomLogic() {
    AllocationTokenFlowUtils failingFlowUtils =
        new AllocationTokenFlowUtils(new FailingAllocationTokenCustomLogic());
    persistResource(
        new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    Exception thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                failingFlowUtils.loadTokenAndValidateDomainCreate(
                    createCommand("blah.tld"),
                    "tokeN",
                    Registry.get("tld"),
                    "TheRegistrar",
                    DateTime.now(UTC)));
    assertThat(thrown).hasMessageThat().isEqualTo("failed for tests");
  }

  @Test
  public void test_validateToken_invalidForClientId() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedClientIds(ImmutableSet.of("NewRegistrar"))
            .build());
    assertValidateThrowsEppException(AllocationTokenNotValidForRegistrarException.class);
  }

  @Test
  public void test_validateToken_invalidForTld() {
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setAllowedTlds(ImmutableSet.of("nottld"))
            .build());
    assertValidateThrowsEppException(AllocationTokenNotValidForTldException.class);
  }

  @Test
  public void test_validateToken_beforePromoStart() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).plusDays(1)).build());
    assertValidateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  public void test_validateToken_afterPromoEnd() {
    persistResource(createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusMonths(2)).build());
    assertValidateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  public void test_validateToken_promoCancelled() {
    // the promo would be valid but it was cancelled 12 hours ago
    persistResource(
        createOneMonthPromoTokenBuilder(DateTime.now(UTC).minusDays(1))
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, NOT_STARTED)
                    .put(DateTime.now(UTC).minusMonths(1), VALID)
                    .put(DateTime.now(UTC).minusHours(12), CANCELLED)
                    .build())
            .build());
    assertValidateThrowsEppException(AllocationTokenNotInPromotionException.class);
  }

  @Test
  public void test_checkDomainsWithToken_successfullyVerifiesValidToken() {
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
  public void test_checkDomainsWithToken_showsFailureMessageForRedeemedToken() {
    persistResource(
        new AllocationToken.Builder()
            .setToken("tokeN")
            .setTokenType(SINGLE_USE)
            .setRedemptionHistoryEntry(
                VKey.createOfy(HistoryEntry.class, Key.create(HistoryEntry.class, 101L)))
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

  @Test
  public void test_checkDomainsWithToken_callsCustomLogic() {
    persistResource(
        new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    AllocationTokenFlowUtils failingFlowUtils =
        new AllocationTokenFlowUtils(new FailingAllocationTokenCustomLogic());
    Exception thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                failingFlowUtils.checkDomainsWithToken(
                    ImmutableList.of(
                        InternetDomainName.from("blah.tld"), InternetDomainName.from("blah2.tld")),
                    "tokeN",
                    "TheRegistrar",
                    DateTime.now(UTC)));
    assertThat(thrown).hasMessageThat().isEqualTo("failed for tests");
  }

  @Test
  public void test_checkDomainsWithToken_resultsFromCustomLogicAreIntegrated() {
    persistResource(
        new AllocationToken.Builder().setToken("tokeN").setTokenType(SINGLE_USE).build());
    AllocationTokenFlowUtils customResultFlowUtils =
        new AllocationTokenFlowUtils(new CustomResultAllocationTokenCustomLogic());
    assertThat(
            customResultFlowUtils
                .checkDomainsWithToken(
                    ImmutableList.of(
                        InternetDomainName.from("blah.tld"), InternetDomainName.from("bunny.tld")),
                    "tokeN",
                    "TheRegistrar",
                    DateTime.now(UTC))
                .domainCheckResults())
        .containsExactlyEntriesIn(
            ImmutableMap.of(
                InternetDomainName.from("blah.tld"),
                "",
                InternetDomainName.from("bunny.tld"),
                "fufu"))
        .inOrder();
  }

  private void assertValidateThrowsEppException(Class<? extends EppException> clazz) {
    assertAboutEppExceptions()
        .that(
            assertThrows(
                clazz,
                () ->
                    flowUtils.loadTokenAndValidateDomainCreate(
                        createCommand("blah.tld"),
                        "tokeN",
                        Registry.get("tld"),
                        "TheRegistrar",
                        DateTime.now(UTC))))
        .marshalsToXml();
  }

  private static DomainCommand.Create createCommand(String domainName) {
    DomainCommand.Create command = mock(DomainCommand.Create.class);
    when(command.getFullyQualifiedDomainName()).thenReturn(domainName);
    return command;
  }

  private static AllocationToken.Builder createOneMonthPromoTokenBuilder(DateTime promoStart) {
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

  /** An {@link AllocationTokenCustomLogic} class that throws exceptions on every method. */
  private static class FailingAllocationTokenCustomLogic extends AllocationTokenCustomLogic {

    @Override
    public AllocationToken validateToken(
        DomainCommand.Create command,
        AllocationToken token,
        Registry registry,
        String clientId,
        DateTime now) {
      throw new IllegalStateException("failed for tests");
    }

    @Override
    public ImmutableMap<InternetDomainName, String> checkDomainsWithToken(
        ImmutableList<InternetDomainName> domainNames,
        AllocationToken tokenEntity,
        String clientId,
        DateTime now) {
      throw new IllegalStateException("failed for tests");
    }
  }

  /** An {@link AllocationTokenCustomLogic} class that returns custom check results for bunnies. */
  private static class CustomResultAllocationTokenCustomLogic extends AllocationTokenCustomLogic {

    @Override
    public ImmutableMap<InternetDomainName, String> checkDomainsWithToken(
        ImmutableList<InternetDomainName> domainNames,
        AllocationToken tokenEntity,
        String clientId,
        DateTime now) {
      return domainNames.stream()
          .collect(
              ImmutableMap.toImmutableMap(
                  Function.identity(),
                  domainName -> domainName.toString().contains("bunny") ? "fufu" : ""));
    }
  }
}
