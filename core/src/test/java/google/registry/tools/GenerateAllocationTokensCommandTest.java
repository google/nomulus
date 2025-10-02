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

package google.registry.tools;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.NONPREMIUM;
import static google.registry.model.billing.BillingBase.RenewalPriceBehavior.SPECIFIED;
import static google.registry.model.domain.token.AllocationToken.TokenType.SINGLE_USE;
import static google.registry.model.domain.token.AllocationToken.TokenType.UNLIMITED_USE;
import static google.registry.testing.DatabaseHelper.assertAllocationTokens;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.loadAllOf;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.joda.time.DateTimeZone.UTC;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import google.registry.model.domain.fee.FeeQueryCommandExtensionItem.CommandName;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.domain.token.AllocationToken.TokenStatus;
import google.registry.model.reporting.HistoryEntry.HistoryEntryId;
import google.registry.testing.DatabaseHelper;
import google.registry.testing.DeterministicStringGenerator;
import google.registry.testing.DeterministicStringGenerator.Rule;
import google.registry.util.StringGenerator.Alphabets;
import java.io.File;
import java.util.Collection;
import javax.annotation.Nullable;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link GenerateAllocationTokensCommand}. */
class GenerateAllocationTokensCommandTest extends CommandTestCase<GenerateAllocationTokensCommand> {

  @BeforeEach
  void beforeEach() {
    DatabaseHelper.createTlds("tld", "example");
    command.stringGenerator = new DeterministicStringGenerator(Alphabets.BASE_58);
  }

  @Test
  void testSuccess_oneToken() throws Exception {
    runCommand("--prefix", "blah", "--number", "1", "--length", "9");
    assertAllocationTokens(createToken("blah123456789", null, null));
    assertInStdout("blah123456789");
  }

  @Test
  void testSuccess_threeTokens() throws Exception {
    runCommand("--prefix", "foo", "--number", "3", "--length", "10");
    assertAllocationTokens(
        createToken("foo123456789A", null, null),
        createToken("fooBCDEFGHJKL", null, null),
        createToken("fooMNPQRSTUVW", null, null));
    assertInStdout("foo123456789A\nfooBCDEFGHJKL\nfooMNPQRSTUVW");
  }

  @Test
  void testSuccess_defaults() throws Exception {
    runCommand("--number", "1");
    assertAllocationTokens(createToken("123456789ABCDEFG", null, null));
    assertInStdout("123456789ABCDEFG");
  }

  @Test
  void testSuccess_tokenCollision() throws Exception {
    AllocationToken existingToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("DEADBEEF123456789ABC")
                .setTokenType(SINGLE_USE)
                .build());
    runCommand("--number", "1", "--prefix", "DEADBEEF", "--length", "12");
    assertAllocationTokens(existingToken, createToken("DEADBEEFDEFGHJKLMNPQ", null, null));
    assertInStdout("DEADBEEFDEFGHJKLMNPQ");
  }

  @Test
  void testSuccess_dryRun_outputsButDoesntSave() throws Exception {
    runCommand("--prefix", "foo", "--number", "2", "--length", "10", "--dry_run");
    assertAllocationTokens();
    assertInStdout("foo123456789A\nfooBCDEFGHJKL");
  }

  @Test
  void testSuccess_largeNumberOfTokens() throws Exception {
    command.stringGenerator =
        new DeterministicStringGenerator(Alphabets.BASE_58, Rule.PREPEND_COUNTER);
    runCommand("--prefix", "ooo", "--number", "100", "--length", "16");
    // The deterministic string generator makes it too much hassle to assert about each token, so
    // just assert total number.
    assertThat(loadAllOf(AllocationToken.class)).hasSize(100);
  }

  @Test
  void testSuccess_domainNames() throws Exception {
    createTlds("tld", "xn--q9jyb4c");
    File domainNamesFile = tmpDir.resolve("domain_names.txt").toFile();
    Files.asCharSink(domainNamesFile, UTF_8).write("foo1.tld\nboo2.tld\nçauçalito.みんな\n");
    runCommand("--domain_names_file", domainNamesFile.getPath());
    assertAllocationTokens(
        createToken("123456789ABCDEFG", null, "foo1.tld"),
        createToken("HJKLMNPQRSTUVWXY", null, "boo2.tld"),
        createToken("Zabcdefghijkmnop", null, "xn--aualito-txac.xn--q9jyb4c"));
    assertInStdout(
        "foo1.tld,123456789ABCDEFG",
        "boo2.tld,HJKLMNPQRSTUVWXY",
        "xn--aualito-txac.xn--q9jyb4c,Zabcdefghijkmnop");
  }

  @Test
  void testSuccess_promotionToken() throws Exception {
    DateTime promoStart = DateTime.now(UTC);
    DateTime promoEnd = promoStart.plusMonths(1);
    runCommand(
        "--number", "1",
        "--prefix", "promo",
        "--type", "UNLIMITED_USE",
        "--allowed_client_ids", "TheRegistrar,NewRegistrar",
        "--allowed_tlds", "tld,example",
        "--allowed_epp_actions", "CREATE,RENEW",
        "--discount_fraction", "0.5",
        "--discount_premiums", "true",
        "--discount_years", "6",
        "--token_status_transitions",
            String.format("%s=NOT_STARTED,%s=VALID,%s=ENDED", START_OF_TIME, promoStart, promoEnd));
    assertAllocationTokens(
        new AllocationToken.Builder()
            .setToken("promo123456789ABCDEFG")
            .setTokenType(UNLIMITED_USE)
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar", "NewRegistrar"))
            .setAllowedTlds(ImmutableSet.of("tld", "example"))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.RENEW))
            .setDiscountFraction(0.5)
            .setDiscountPremiums(true)
            .setDiscountYears(6)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(promoStart, TokenStatus.VALID)
                    .put(promoEnd, TokenStatus.ENDED)
                    .build())
            .build());
  }

  @Test
  void testSuccess_promotionToken_withDiscountPrice() throws Exception {
    DateTime promoStart = DateTime.now(UTC);
    DateTime promoEnd = promoStart.plusMonths(1);
    runCommand(
        "--number",
        "1",
        "--prefix",
        "promo",
        "--type",
        "UNLIMITED_USE",
        "--allowed_client_ids",
        "TheRegistrar,NewRegistrar",
        "--allowed_tlds",
        "tld,example",
        "--allowed_epp_actions",
        "CREATE,RENEW",
        "--discount_price",
        "USD 3",
        "--discount_years",
        "6",
        "--token_status_transitions",
        String.format("%s=NOT_STARTED,%s=VALID,%s=ENDED", START_OF_TIME, promoStart, promoEnd));
    assertAllocationTokens(
        new AllocationToken.Builder()
            .setToken("promo123456789ABCDEFG")
            .setTokenType(UNLIMITED_USE)
            .setAllowedRegistrarIds(ImmutableSet.of("TheRegistrar", "NewRegistrar"))
            .setAllowedTlds(ImmutableSet.of("tld", "example"))
            .setAllowedEppActions(ImmutableSet.of(CommandName.CREATE, CommandName.RENEW))
            .setDiscountPrice(Money.of(CurrencyUnit.USD, 3))
            .setDiscountPremiums(false)
            .setDiscountYears(6)
            .setTokenStatusTransitions(
                ImmutableSortedMap.<DateTime, TokenStatus>naturalOrder()
                    .put(START_OF_TIME, TokenStatus.NOT_STARTED)
                    .put(promoStart, TokenStatus.VALID)
                    .put(promoEnd, TokenStatus.ENDED)
                    .build())
            .build());
  }

  @Test
  void testSuccess_specifyTokens() throws Exception {
    runCommand("--tokens", "foobar,foobaz");
    assertAllocationTokens(createToken("foobar", null, null), createToken("foobaz", null, null));
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testSuccess_renewalPriceBehaviorIsDefault() throws Exception {
    runCommand("--tokens", "foobar,foobaz", "--renewal_price_behavior", "DEFAULT");
    assertAllocationTokens(createToken("foobar", null, null), createToken("foobaz", null, null));
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testSuccess_renewalPriceBehaviorIsSetToDefaultByDefault() throws Exception {
    runCommand("--tokens", "foobar,foobaz");
    assertAllocationTokens(createToken("foobar", null, null), createToken("foobaz", null, null));
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testSuccess_renewalPriceBehaviorIsNonPremium() throws Exception {
    runCommand("--tokens", "foobar,foobaz", "--renewal_price_behavior", "NONPREMIUM");
    assertAllocationTokens(
        createToken("foobar", null, null).asBuilder().setRenewalPriceBehavior(NONPREMIUM).build(),
        createToken("foobaz", null, null).asBuilder().setRenewalPriceBehavior(NONPREMIUM).build());
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testSuccess_renewalPriceBehaviorIsSpecified() throws Exception {
    runCommand(
        "--tokens",
        "foobar,foobaz",
        "--renewal_price_behavior",
        "SPECIFIED",
        "--renewal_price",
        "USD 10");
    assertAllocationTokens(
        createToken("foobar", null, null)
            .asBuilder()
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 10))
            .build(),
        createToken("foobaz", null, null)
            .asBuilder()
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 10))
            .build());
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testSuccess_renewalPriceBehaviorIsSpecifiedButMixedCase() throws Exception {
    runCommand(
        "--tokens",
        "foobar,foobaz",
        "--renewal_price_behavior",
        "speCIFied",
        "--renewal_price",
        "USD 10");
    assertAllocationTokens(
        createToken("foobar", null, null)
            .asBuilder()
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 10))
            .build(),
        createToken("foobaz", null, null)
            .asBuilder()
            .setRenewalPriceBehavior(SPECIFIED)
            .setRenewalPrice(Money.of(CurrencyUnit.USD, 10))
            .build());
    assertInStdout("foobar", "foobaz");
  }

  @Test
  void testFailure_renewalPriceBehaviorIsInvalid() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--tokens", "foobar,foobaz", "--renewal_price_behavior", "SPEXIFIED"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid value for --renewal_price_behavior parameter. Allowed values:[DEFAULT,"
                + " NONPREMIUM, SPECIFIED]");
  }

  @Test
  void testFailure_renewalPriceBehaviorIsEmptyString() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--tokens", "foobar,foobaz", "--renewal_price_behavior", ""));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid value for --renewal_price_behavior parameter. Allowed values:[DEFAULT,"
                + " NONPREMIUM, SPECIFIED]");
  }

  @Test
  void testFailure_specifiedPrice_withoutPrice() throws Exception {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--tokens", "foobar", "--renewal_price_behavior", "SPECIFIED")))
        .hasMessageThat()
        .isEqualTo("renewal_price must be specified iff renewal_price_behavior is SPECIFIED");
  }

  @Test
  void testSuccess_defaultRegistrationBehavior() throws Exception {
    runCommand("--tokens", "foobar,blah");
    assertThat(
            loadAllOf(AllocationToken.class).stream()
                .map(AllocationToken::getRegistrationBehavior)
                .collect(toImmutableList()))
        .containsExactly(
            AllocationToken.RegistrationBehavior.DEFAULT,
            AllocationToken.RegistrationBehavior.DEFAULT);
  }

  @Test
  void testSuccess_defaultRegistrationBehavior_specified() throws Exception {
    runCommand("--tokens", "foobar,blah", "--registration_behavior", "DEFAULT");
    assertThat(
            loadAllOf(AllocationToken.class).stream()
                .map(AllocationToken::getRegistrationBehavior)
                .collect(toImmutableList()))
        .containsExactly(
            AllocationToken.RegistrationBehavior.DEFAULT,
            AllocationToken.RegistrationBehavior.DEFAULT);
  }

  @Test
  void testSuccess_specifiedRegistrationBehavior() throws Exception {
    runCommand("--tokens", "foobar,blah", "--registration_behavior", "BYPASS_TLD_STATE");
    assertThat(
            loadAllOf(AllocationToken.class).stream()
                .map(AllocationToken::getRegistrationBehavior)
                .collect(toImmutableList()))
        .containsExactly(
            AllocationToken.RegistrationBehavior.BYPASS_TLD_STATE,
            AllocationToken.RegistrationBehavior.BYPASS_TLD_STATE);
  }

  @Test
  void testFailure_invalidRegistrationBehaviors() throws Exception {
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
  void testSuccess_specifyManyTokens() throws Exception {
    command.stringGenerator =
        new DeterministicStringGenerator(Alphabets.BASE_58, Rule.PREPEND_COUNTER);
    Collection<String> sampleTokens = command.stringGenerator.createStrings(13, 100);
    runCommand("--tokens", Joiner.on(",").join(sampleTokens));
    assertInStdout(Iterables.toArray(sampleTokens, String.class));
    assertThat(loadAllOf(AllocationToken.class)).hasSize(100);
  }

  @Test
  void testFailure_mustSpecifyNumberOfTokensOrDomainsFile() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--prefix", "FEET"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify exactly one of '--number', '--domain_names_file', and '--tokens'");
  }

  @Test
  void testFailure_mustNotSpecifyBothNumberOfTokensAndDomainsFile() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--prefix", "FEET",
                    "--number", "999",
                    "--domain_names_file", "/path/to/blaaaaah"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify exactly one of '--number', '--domain_names_file', and '--tokens'");
  }

  @Test
  void testFailure_mustNotSpecifyBothNumberOfTokensAndTokenStrings() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--prefix", "FEET",
                    "--number", "999",
                    "--tokens", "token1,token2"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify exactly one of '--number', '--domain_names_file', and '--tokens'");
  }

  @Test
  void testFailure_mustNotSpecifyBothTokenStringsAndDomainsFile() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand(
                    "--prefix", "FEET",
                    "--tokens", "token1,token2",
                    "--domain_names_file", "/path/to/blaaaaah"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Must specify exactly one of '--number', '--domain_names_file', and '--tokens'");
  }

  @Test
  void testFailure_specifiesAlreadyExistingToken() throws Exception {
    runCommand("--tokens", "foobar");
    beforeEachCommandTestCase(); // reset the command variables
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommand("--tokens", "foobar,foobaz"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Cannot create specified tokens; the following tokens already exist: [foobar]");
  }

  @Test
  void testFailure_invalidTokenType() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () -> runCommand("--number", "999", "--type", "INVALID_TYPE"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Invalid value for --type parameter. Allowed values:[BULK_PRICING, DEFAULT_PROMO,"
                + " PACKAGE, SINGLE_USE, UNLIMITED_USE, REGISTER_BSA]");
  }

  @Test
  void testFailure_invalidTokenStatusTransition() {
    assertThat(
            assertThrows(
                ParameterException.class,
                () ->
                    runCommand(
                        "--number",
                        "999",
                        String.format(
                            "--token_status_transitions=\"%s=INVALID_STATUS\"", START_OF_TIME))))
        .hasCauseThat()
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testFailure_invalidBulkTokenStatusTransition() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    runCommand(
                        "--number",
                        "999",
                        "--type",
                        "BULK_PRICING",
                        String.format(
                            "--token_status_transitions=%s=NOT_STARTED,%s=VALID,%s=ENDED",
                            START_OF_TIME, fakeClock.nowUtc(), fakeClock.nowUtc().plusDays(1)))))
        .hasMessageThat()
        .isEqualTo(
            "BULK_PRICING tokens should not be generated with ENDED or CANCELLED in their"
                + " transition map");
  }

  @Test
  void testFailure_lengthOfZero() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommand("--prefix", "somePrefix", "--number", "1", "--length", "0"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            "Token length should not be 0. To generate exact tokens, use the --tokens parameter.");
  }

  @Test
  void testFailure_unlimitedUseMustHaveTransitions() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--number", "999", "--type", "UNLIMITED_USE")))
        .hasMessageThat()
        .isEqualTo("For UNLIMITED_USE tokens, must specify --token_status_transitions");
  }

  @Test
  void testFailure_defaultPromoMustHaveTransitions() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--number", "999", "--type", "DEFAULT_PROMO")))
        .hasMessageThat()
        .isEqualTo("For DEFAULT_PROMO tokens, must specify --token_status_transitions");
  }

  @Test
  void testFailure_badTld() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--number", "10", "--allowed_tlds", "badtld")))
        .hasMessageThat()
        .isEqualTo("Unknown REAL TLD(s) [badtld]");
  }

  @Test
  void testFailure_badRegistrar() {
    assertThat(
            assertThrows(
                IllegalArgumentException.class,
                () -> runCommand("--number", "10", "--allowed_client_ids", "badregistrar")))
        .hasMessageThat()
        .isEqualTo("Unknown registrar ID(s) [badregistrar]");
  }

  private AllocationToken createToken(
      String token,
      @Nullable HistoryEntryId redemptionHistoryEntryId,
      @Nullable String domainName) {
    AllocationToken.Builder builder =
        new AllocationToken.Builder().setToken(token).setTokenType(SINGLE_USE);
    if (redemptionHistoryEntryId != null) {
      builder.setRedemptionHistoryId(redemptionHistoryEntryId);
    }
    builder.setDomainName(domainName);
    return builder.build();
  }
}
