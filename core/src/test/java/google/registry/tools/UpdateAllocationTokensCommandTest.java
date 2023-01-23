// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.DEFAULT;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingEvent.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.CANCELLED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.ENDED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.NOT_STARTED;
import static google.registry.model.domain.token.AllocationToken.TokenStatus.VALID;
import static google.registry.model.domain.token.AllocationToken.TokenType.PACKAGE;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.loadByEntity;
import static google.registry.testing.DatabaseHelper.persistActiveDomain;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.RegistrationBehavior;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateAllocationTokensCommand}. */
class UpdateAllocationTokensCommandTest extends CommandTestCase<UpdateAllocationTokensCommand> {

  @Test
  void testUpdateTlds_setTlds() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_tlds", "tld,example");
    assertThat(reloadResource(token).getAllowedTlds()).containsExactly("tld", "example");
  }

  @Test
  void testUpdateTlds_clearTlds() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_tlds", "");
    assertThat(reloadResource(token).getAllowedTlds()).isEmpty();
  }

  @Test
  void testUpdateClientIds_setClientIds() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setAllowedRegistrarIds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_client_ids", "clientone,clienttwo");
    assertThat(reloadResource(token).getAllowedRegistrarIds())
        .containsExactly("clientone", "clienttwo");
  }

  @Test
  void testUpdateClientIds_clearClientIds() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setAllowedRegistrarIds(ImmutableSet.of("toRemove")).build());
    runCommandForced("--prefix", "token", "--allowed_client_ids", "");
    assertThat(reloadResource(token).getAllowedRegistrarIds()).isEmpty();
  }

  @Test
  void testUpdateDiscountFraction() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--discount_fraction", "0.15");
    assertThat(reloadResource(token).getDiscountFraction()).isEqualTo(0.15);
  }

  @Test
  void testUpdateDiscountPremiums() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setDiscountFraction(0.5).setDiscountPremiums(false).build());
    runCommandForced("--prefix", "token", "--discount_premiums", "true");
    assertThat(reloadResource(token).shouldDiscountPremiums()).isTrue();
    runCommandForced("--prefix", "token", "--discount_premiums", "false");
    assertThat(reloadResource(token).shouldDiscountPremiums()).isFalse();
  }

  @Test
  void testUpdateDiscountYears() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--discount_years", "4");
    assertThat(reloadResource(token).getDiscountYears()).isEqualTo(4);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToSpecified() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "SPECIFIED");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(SPECIFIED);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToDefault() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setRenewalPriceBehavior(SPECIFIED).setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "default");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(DEFAULT);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToNonPremium() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setRenewalPriceBehavior(SPECIFIED).setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "NONpremium");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(NONPREMIUM);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setFromDefaultToDefault() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "defauLT");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(DEFAULT);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setFromSpecifiedToSpecified() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setRenewalPriceBehavior(SPECIFIED).setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "SPecified");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(SPECIFIED);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setFromNonPremiumToDefault() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo()
                .setRenewalPriceBehavior(NONPREMIUM)
                .setDiscountFraction(0.5)
                .build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "defauLT");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(DEFAULT);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToMixedCaseDefault() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo().setRenewalPriceBehavior(SPECIFIED).setDiscountFraction(0.5).build());
    runCommandForced("--prefix", "token", "--renewal_price_behavior", "deFauLt");
    assertThat(reloadResource(token).getRenewalPriceBehavior()).isEqualTo(DEFAULT);
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToInvalidBehavior_throwsException() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--prefix", "token", "--renewal_price_behavior", "premium"));
    persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid value for --renewal_price_behavior parameter. Allowed values:[DEFAULT,"
                + " NONPREMIUM, SPECIFIED]");
  }

  @Test
  void testUpdateRenewalPriceBehavior_setToEmptyString_throwsException() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommandForced("--prefix", "token", "--renewal_price_behavior", ""));
    persistResource(builderWithPromo().setDiscountFraction(0.5).build());
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid value for --renewal_price_behavior parameter. Allowed values:[DEFAULT,"
                + " NONPREMIUM, SPECIFIED]");
  }

  @Test
  void testSuccess_registrationBehavior_same() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo()
                .setRegistrationBehavior(AllocationToken.RegistrationBehavior.BYPASS_TLD_STATE)
                .build());
    assertThat(token.getRegistrationBehavior())
        .isEqualTo(AllocationToken.RegistrationBehavior.BYPASS_TLD_STATE);
    runCommandForced("--tokens", "token", "--registration_behavior", "BYPASS_TLD_STATE");
    assertThat(loadByEntity(token).getRegistrationBehavior())
        .isEqualTo(AllocationToken.RegistrationBehavior.BYPASS_TLD_STATE);
  }

  @Test
  void testSuccess_registrationBehavior_different() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().build());
    assertThat(token.getRegistrationBehavior())
        .isEqualTo(AllocationToken.RegistrationBehavior.DEFAULT);
    runCommandForced("--tokens", "token", "--registration_behavior", "BYPASS_TLD_STATE");
    assertThat(loadByEntity(token).getRegistrationBehavior())
        .isEqualTo(RegistrationBehavior.BYPASS_TLD_STATE);
  }

  @Test
  void testFailure_registrationBehavior_enforcesAnchorTenantRestriction() throws Exception {
    AllocationToken token = persistResource(builderWithPromo().build());
    assertThat(token.getRegistrationBehavior())
        .isEqualTo(AllocationToken.RegistrationBehavior.DEFAULT);
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommandForced(
                        "--tokens", "token", "--registration_behavior", "ANCHOR_TENANT")))
        .hasMessageThat()
        .isEqualTo("ANCHOR_TENANT tokens must be tied to a domain");
  }

  @Test
  void testFailure_registrationBehavior_invalid() throws Exception {
    assertThat(
            assertThrows(
                ParameterException.class,
                () -> runCommand("--tokens", "foobar", "--registration_behavior")))
        .hasMessageThat()
        .contains("Expected a value after parameter --registration_behavior");
    assertThat(
            assertThrows(
                ParameterException.class,
                () -> runCommand("--tokens", "foobar", "--registration_behavior", "bad")))
        .hasMessageThat()
        .contains("Invalid value for --registration_behavior");
    assertThat(
            assertThrows(
                ParameterException.class,
                () -> runCommand("--tokens", "foobar", "--registration_behavior", "")))
        .hasMessageThat()
        .contains("Invalid value for --registration_behavior");
  }

  @Test
  void testUpdateStatusTransitions() throws Exception {
    DateTime now = DateTime.now(UTC);
    AllocationToken token = persistResource(builderWithPromo().build());
    runCommandForced(
        "--prefix",
        "token",
        "--token_status_transitions",
        String.format(
            "\"%s=NOT_STARTED,%s=VALID,%s=CANCELLED\"", START_OF_TIME, now.minusDays(1), now));
    token = reloadResource(token);
    assertThat(token.getTokenStatusTransitions().toValueMap())
        .containsExactly(START_OF_TIME, NOT_STARTED, now.minusDays(1), VALID, now, CANCELLED);
  }

  @Test
  void testUpdateStatusTransitions_badTransitions() {
    DateTime now = DateTime.now(UTC);
    persistResource(builderWithPromo().build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--prefix",
                    "token",
                    "--token_status_transitions",
                    String.format(
                        "\"%s=NOT_STARTED,%s=ENDED,%s=VALID\"",
                        START_OF_TIME, now.minusDays(1), now)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("tokenStatusTransitions map cannot transition from NOT_STARTED to ENDED.");
  }

  @Test
  void testUpdateStatusTransitions_endPackageTokenNoDomains() throws Exception {
    DateTime now = DateTime.now(UTC);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(PACKAGE)
                .setRenewalPriceBehavior(SPECIFIED)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, NOT_STARTED)
                        .put(now.minusDays(1), VALID)
                        .build())
                .build());
    runCommandForced(
        "--prefix",
        "token",
        "--token_status_transitions",
        String.format(
            "\"%s=NOT_STARTED,%s=VALID,%s=ENDED\"", START_OF_TIME, now.minusDays(1), now));
    token = reloadResource(token);
    assertThat(token.getTokenStatusTransitions().toValueMap())
        .containsExactly(START_OF_TIME, NOT_STARTED, now.minusDays(1), VALID, now, ENDED);
  }

  @Test
  void testUpdateStatusTransitions_endPackageTokenWithActiveDomainsFails() throws Exception {
    DateTime now = DateTime.now(UTC);
    AllocationToken token =
        persistResource(
            new AllocationToken.Builder()
                .setToken("token")
                .setTokenType(PACKAGE)
                .setRenewalPriceBehavior(SPECIFIED)
                .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar"))
                .setTokenStatusTransitions(
                    ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                        .put(START_OF_TIME, NOT_STARTED)
                        .put(now.minusDays(1), VALID)
                        .build())
                .build());
    createTld("tld");
    persistResource(
        persistActiveDomain("example.tld")
            .asBuilder()
            .setCurrentPackageToken(token.createVKey())
            .build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--prefix",
                    "token",
                    "--token_status_transitions",
                    String.format(
                        "\"%s=NOT_STARTED,%s=VALID,%s=ENDED\"",
                        START_OF_TIME, now.minusDays(1), now)));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Package token token can not end its promotion because it still has 1 domains in the"
                + " package");
  }

  @Test
  void testUpdate_onlyWithPrefix() throws Exception {
    AllocationToken token =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("tld")).build());
    AllocationToken otherToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("otherToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    runCommandForced("--prefix", "other", "--allowed_tlds", "");
    assertThat(reloadResource(token).getAllowedTlds()).containsExactly("tld");
    assertThat(reloadResource(otherToken).getAllowedTlds()).isEmpty();
  }

  @Test
  void testUpdate_onlyTokensProvided() throws Exception {
    AllocationToken firstToken =
        persistResource(builderWithPromo().setAllowedTlds(ImmutableSet.of("tld")).build());
    AllocationToken secondToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("secondToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    AllocationToken thirdToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("thirdToken")
                .setTokenType(SINGLE_USE)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    runCommandForced("--tokens", "secondToken,thirdToken", "--allowed_tlds", "");
    assertThat(reloadResource(firstToken).getAllowedTlds()).containsExactly("tld");
    assertThat(reloadResource(secondToken).getAllowedTlds()).isEmpty();
    assertThat(reloadResource(thirdToken).getAllowedTlds()).isEmpty();
  }

  @Test
  void testDoNothing() throws Exception {
    AllocationToken token =
        persistResource(
            builderWithPromo()
                .setAllowedRegistrarIds(ImmutableSet.of("clientid"))
                .setAllowedTlds(ImmutableSet.of("tld"))
                .setDiscountFraction(0.15)
                .build());
    runCommandForced("--prefix", "token");
    AllocationToken reloaded = reloadResource(token);
    assertThat(reloaded.getAllowedTlds()).isEqualTo(token.getAllowedTlds());
    assertThat(reloaded.getAllowedRegistrarIds()).isEqualTo(token.getAllowedRegistrarIds());
    assertThat(reloaded.getDiscountFraction()).isEqualTo(token.getDiscountFraction());
  }

  @Test
  void testFailure_bothTokensAndPrefix() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommandForced("--prefix", "token", "--tokens", "token")))
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @Test
  void testFailure_neitherTokensNorPrefix() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class, () -> runCommandForced("--allowed_tlds", "tld")))
        .hasMessageThat()
        .isEqualTo("Must provide one of --tokens or --prefix, not both / neither");
  }

  @Test
  void testFailure_emptyPrefix() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--prefix", ""));
    assertThat(thrown).hasMessageThat().isEqualTo("Provided prefix should not be blank");
  }

  private static AllocationToken.Builder builderWithPromo() {
    DateTime now = DateTime.now(UTC);
    return new AllocationToken.Builder()
        .setToken("token")
        .setTokenType(UNLIMITED_USE)
        .setTokenStatusTransitions(
            ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                .put(START_OF_TIME, NOT_STARTED)
                .put(now.minusDays(1), VALID)
                .put(now.plusDays(1), ENDED)
                .build());
  }
}
