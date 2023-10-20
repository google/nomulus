// Copyright 2023 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.EntityYamlUtils.createObjectMapper;
import static google.registry.model.domain.token.AllocationToken.TokenType.DEFAULT_PROMO;
import static google.registry.testing.DatabaseHelper.createTld;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.testing.LogsSubject.assertAboutLogs;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.tldconfig.idn.IdnTableEnum.EXTENDED_LATIN;
import static google.registry.tldconfig.idn.IdnTableEnum.JA;
import static google.registry.tldconfig.idn.IdnTableEnum.UNCONFUSABLE_LATIN;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.INFO;
import static org.joda.money.CurrencyUnit.JPY;
import static org.joda.money.CurrencyUnit.USD;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.testing.TestLogHandler;
import google.registry.model.domain.token.AllocationToken;
import google.registry.model.tld.Tld;
import google.registry.model.tld.Tld.TldNotFoundException;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import java.io.File;
import java.util.logging.Logger;
import org.joda.money.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

/** Unit tests for {@link ConfigureTldCommand} */
public class ConfigureTldCommandTest extends CommandTestCase<ConfigureTldCommand> {

  PremiumList premiumList;
  ObjectMapper objectMapper = createObjectMapper();
  private final TestLogHandler logHandler = new TestLogHandler();
  private final Logger logger = Logger.getLogger(ConfigureTldCommand.class.getCanonicalName());

  @BeforeEach
  void beforeEach() {
    command.mapper = objectMapper;
    premiumList = persistPremiumList("test", USD, "silver,USD 50", "gold,USD 80");
    command.validDnsWriterNames = ImmutableSet.of("VoidDnsWriter", "FooDnsWriter");
    logger.addHandler(logHandler);
  }

  private void testTldConfiguredSuccessfully(Tld tld, String filename)
      throws JsonProcessingException {
    String yaml = objectMapper.writeValueAsString(tld);
    assertThat(yaml).isEqualTo(loadFile(getClass(), filename));
  }

  @Test
  void testSuccess_createNewTld() throws Exception {
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld tld = Tld.get("tld");
    assertThat(tld).isNotNull();
    assertThat(tld.getDriveFolderId()).isEqualTo("driveFolder");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    testTldConfiguredSuccessfully(tld, "tld.yaml");
    assertThat(tld.getBreakglassMode()).isFalse();
  }

  @Test
  void testSuccess_updateTld() throws Exception {
    Tld tld = createTld("tld");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    testTldConfiguredSuccessfully(updatedTld, "tld.yaml");
    assertThat(updatedTld.getBreakglassMode()).isFalse();
  }

  @Test
  void testSuccess_noDiff() throws Exception {
    Tld tld = createTld("idns");
    persistResource(
        tld.asBuilder()
            .setIdnTables(ImmutableSet.of(JA, UNCONFUSABLE_LATIN, EXTENDED_LATIN))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("zeta", "alpha", "gamma", "beta"))
            .build());
    File tldFile = tmpDir.resolve("idns.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "idns.yaml"));
    runCommandForced("--input=" + tldFile);
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "TLD YAML file contains no new changes");
  }

  @Test
  void testSuccess_outOfOrderFieldsOnCreate() throws Exception {
    File tldFile = tmpDir.resolve("outoforderfields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "outoforderfields.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld tld = Tld.get("outoforderfields");
    // Cannot test that created TLD converted to YAML is equal to original YAML since the created
    // TLD's YAML will contain the fields in the correct order
    assertThat(tld).isNotNull();
    assertThat(tld.getDriveFolderId()).isEqualTo("driveFolder");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    assertThat(tld.getPremiumListName().get()).isEqualTo("test");
  }

  @Test
  void testSuccess_outOfOrderFieldsOnUpdate() throws Exception {
    Tld tld = createTld("outoforderfields");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    File tldFile = tmpDir.resolve("outoforderfields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "outoforderfields.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("outoforderfields");
    // Cannot test that created TLD converted to YAML is equal to original YAML since the created
    // TLD's YAML will contain the fields in the correct order
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
  }

  @Test
  void testFailure_fileMissingNullableFieldsOnCreate() throws Exception {
    File tldFile = tmpDir.resolve("missingnullablefields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "missingnullablefields.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The input file is missing data for the following fields: [tldStateTransitions,"
                + " premiumListName, currency, numDnsPublishLocks]");
  }

  @Test
  void testFailure_fileMissingNullableFieldOnUpdate() throws Exception {
    Tld tld = createTld("missingnullablefields");
    persistResource(
        tld.asBuilder().setNumDnsPublishLocks(5).build()); // numDnsPublishLocks is nullable
    File tldFile = tmpDir.resolve("missingnullablefields.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(), "missingnullablefields.yaml")); // file is missing numDnsPublishLocks
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The input file is missing data for the following fields: [tldStateTransitions,"
                + " premiumListName, currency, numDnsPublishLocks]");
  }

  @Test
  void testSuccess_nullableFieldsAllNullOnCreate() throws Exception {
    File tldFile = tmpDir.resolve("nullablefieldsallnull.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "nullablefieldsallnull.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld tld = Tld.get("nullablefieldsallnull");
    assertThat(tld).isNotNull();
    assertThat(tld.getDriveFolderId()).isEqualTo(null);
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    // cannot test that created TLD converted to YAML is equal to original YAML since the created
    // TLD's YAML will contain empty sets for some of the null fields
    assertThat(tld.getIdnTables()).isEmpty();
    assertThat(tld.getDefaultPromoTokens()).isEmpty();
  }

  @Test
  void testSuccess_nullableFieldsAllNullOnUpdate() throws Exception {
    Tld tld = createTld("nullablefieldsallnull");
    persistResource(
        tld.asBuilder().setIdnTables(ImmutableSet.of(JA)).setDriveFolderId("drive").build());
    File tldFile = tmpDir.resolve("nullablefieldsallnull.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "nullablefieldsallnull.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("nullablefieldsallnull");
    assertThat(updatedTld).isNotNull();
    assertThat(updatedTld.getDriveFolderId()).isEqualTo(null);
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    // cannot test that created TLD converted to YAML is equal to original YAML since the created
    // TLD's YAML will contain empty sets for some of the null fields
    assertThat(updatedTld.getIdnTables()).isEmpty();
    assertThat(updatedTld.getDefaultPromoTokens()).isEmpty();
  }

  @Test
  void testFailure_fileContainsExtraFields() throws Exception {
    File tldFile = tmpDir.resolve("extrafield.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "extrafield.yaml"));
    assertThrows(UnrecognizedPropertyException.class, () -> runCommandForced("--input=" + tldFile));
  }

  @Test
  void testFailure_fileNameDoesNotMatchTldName() throws Exception {
    File tldFile = tmpDir.resolve("othertld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo("The input file name must match the name of the TLD it represents");
  }

  @Test
  void testFailure_tldUnicodeDoesNotMatch() throws Exception {
    File tldFile = tmpDir.resolve("badunicode.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "badunicode.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The value for tldUnicode must equal the unicode representation of the TLD name");
  }

  @Test
  void testFailure_tldUpperCase() throws Exception {
    String name = "TLD";
    File tldFile = tmpDir.resolve("TLD.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wildcard.yaml",
                ImmutableMap.of(
                    "TLDSTR", name, "TLDUNICODE", name, "ROIDSUFFIX", Ascii.toUpperCase(name))));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The value for tldUnicode must equal the unicode representation of the TLD name");
  }

  @Test
  void testSuccess_asciiNameWithOptionalPunycode() throws Exception {
    String name = "xn--q9jyb4c";
    File tldFile = tmpDir.resolve(name + ".yaml").toFile();
    String fileContents =
        loadFile(
            getClass(),
            "wildcard.yaml",
            ImmutableMap.of(
                "TLDSTR",
                "\"" + name + "\"",
                "TLDUNICODE",
                "\"みんな\"",
                "ROIDSUFFIX",
                "\"Q9JYB4C\""));
    Files.asCharSink(tldFile, UTF_8).write(fileContents);
    runCommandForced("--input=" + tldFile);
    Tld tld = Tld.get(name);
    assertThat(tld).isNotNull();
    assertThat(tld.getDriveFolderId()).isEqualTo("driveFolder");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    String yaml = objectMapper.writeValueAsString(tld);
    assertThat(yaml).isEqualTo(fileContents);
  }

  @Test
  void testFailure_punycodeDoesNotMatch() throws Exception {
    String name = "xn--q9jyb4c";
    File tldFile = tmpDir.resolve(name + ".yaml").toFile();
    String fileContents =
        loadFile(
            getClass(),
            "wildcard.yaml",
            ImmutableMap.of(
                "TLDSTR",
                "\"" + name + "\"",
                "TLDUNICODE",
                "\"yoyo\"",
                "ROIDSUFFIX",
                "\"Q9JYB4C\""));
    Files.asCharSink(tldFile, UTF_8).write(fileContents);
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The value for tldUnicode must equal the unicode representation of the TLD name");
  }

  @Test
  @Disabled
  void testFailure_pzunycodeName() throws Exception {
    String name = "みんな";
    File tldFile = tmpDir.resolve(name + ".yaml").toFile();
    String fileContents =
        loadFile(
            getClass(),
            "wildcard.yaml",
            ImmutableMap.of(
                "TLDSTR",
                "\"" + name + "\"",
                "TLDUNICODE",
                "\"みんな\"",
                "ROIDSUFFIX",
                "\"Q9JYB4C\""));
    Files.asCharSink(tldFile, UTF_8).write(fileContents);
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage()).isEqualTo("A TLD name must be in plain ASCII");
  }

  @Test
  void testFailure_invalidRoidSuffix() throws Exception {
    String name = "tld";
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wildcard.yaml",
                ImmutableMap.of(
                    "TLDSTR", name, "TLDUNICODE", name, "ROIDSUFFIX", "TLLLLLLLLLLLLLLLLLLLLLLD")));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage()).isEqualTo("ROID suffix must be in format ^[A-Z\\d_]{1,8}$");
  }

  @Test
  void testFailure_invalidIdnTable() throws Exception {
    File tldFile = tmpDir.resolve("badidn.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "badidn.yaml"));
    assertThrows(InvalidFormatException.class, () -> runCommandForced("--input=" + tldFile));
  }

  @Test
  void testFailure_tldNameStartsWithNumber() throws Exception {
    File tldFile = tmpDir.resolve("1tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "1tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage()).isEqualTo("TLDs cannot begin with a number");
  }

  @Test
  void testFailure_invalidDnsWriter() throws Exception {
    command.validDnsWriterNames = ImmutableSet.of("foo");
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo("Invalid DNS writer name(s) specified: [VoidDnsWriter]");
  }

  @Test
  void testFailure_mismatchedCurrencyUnitsOnCreate() throws Exception {
    File tldFile = tmpDir.resolve("wrongcurrency.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wrongcurrency.yaml",
                ImmutableMap.of("RESTORECURRENCY", "EUR", "RENEWCURRENCY", "USD")));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo("restoreBillingCost must use the same currency as the TLD");
    File tldFile2 = tmpDir.resolve("wrongcurrency.yaml").toFile();
    Files.asCharSink(tldFile2, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wrongcurrency.yaml",
                ImmutableMap.of("RESTORECURRENCY", "USD", "RENEWCURRENCY", "EUR")));
    thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile2));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "All Money values in the renewBillingCostTransitions map must use the TLD's currency"
                + " unit");
  }

  @Test
  void testFailure_mismatchedCurrencyUnitsOnUpdate() throws Exception {
    createTld("wrongcurreency");
    File tldFile = tmpDir.resolve("wrongcurrency.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wrongcurrency.yaml",
                ImmutableMap.of("RESTORECURRENCY", "EUR", "RENEWCURRENCY", "USD")));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo("restoreBillingCost must use the same currency as the TLD");
    File tldFile2 = tmpDir.resolve("wrongcurrency.yaml").toFile();
    Files.asCharSink(tldFile2, UTF_8)
        .write(
            loadFile(
                getClass(),
                "wrongcurrency.yaml",
                ImmutableMap.of("RESTORECURRENCY", "USD", "RENEWCURRENCY", "EUR")));
    thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile2));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "All Money values in the renewBillingCostTransitions map must use the TLD's currency"
                + " unit");
  }

  @Test
  void testSuccess_emptyStringClearsDefaultPromoTokens() throws Exception {
    Tld tld = createTld("tld");
    AllocationToken defaultToken =
        persistResource(
            new AllocationToken.Builder()
                .setToken("bbbbb")
                .setTokenType(DEFAULT_PROMO)
                .setAllowedTlds(ImmutableSet.of("tld"))
                .build());
    persistResource(
        tld.asBuilder().setDefaultPromoTokens(ImmutableList.of(defaultToken.createVKey())).build());
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getDefaultPromoTokens()).isEmpty();
    testTldConfiguredSuccessfully(updatedTld, "tld.yaml");
  }

  @Test
  void testSuccess_emptyStringClearsIdnTables() throws Exception {
    Tld tld = createTld("tld");
    persistResource(tld.asBuilder().setIdnTables(ImmutableSet.of(EXTENDED_LATIN, JA)).build());
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getIdnTables()).isEmpty();
    testTldConfiguredSuccessfully(updatedTld, "tld.yaml");
  }

  @Test
  void testFailure_premiumListDoesNotExist() throws Exception {
    PremiumListDao.delete(premiumList);
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage()).isEqualTo("The premium list with the name test does not exist");
  }

  @Test
  void testFailure_premiumListWrongCurrency() throws Exception {
    PremiumListDao.delete(premiumList);
    persistPremiumList("test", JPY, "bronze,JPY 80");
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage()).isEqualTo("The premium list must use the TLD's currency");
  }

  @Test
  void testFailure_breakglassFlag_NoChanges() throws Exception {
    Tld tld = createTld("idns");
    persistResource(
        tld.asBuilder()
            .setIdnTables(ImmutableSet.of(JA, UNCONFUSABLE_LATIN, EXTENDED_LATIN))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("zeta", "alpha", "gamma", "beta"))
            .build());
    File tldFile = tmpDir.resolve("idns.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "idns.yaml"));
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile, "-b"));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Breakglass mode can only be set when making new changes to a TLD configuration");
  }

  @Test
  void testSuccess_breakglassFlag_startsBreakglassMode() throws Exception {
    Tld tld = createTld("tld");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile, "--breakglass");
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    testTldConfiguredSuccessfully(updatedTld, "tld.yaml");
    assertThat(updatedTld.getBreakglassMode()).isTrue();
  }

  @Test
  void testSuccess_breakglassFlag_continuesBreakglassMode() throws Exception {
    Tld tld = createTld("tld");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    persistResource(tld.asBuilder().setBreakglassMode(true).build());
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile, "--breakglass");
    Tld updatedTld = Tld.get("tld");
    assertThat(updatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 25));
    testTldConfiguredSuccessfully(updatedTld, "tld.yaml");
    assertThat(updatedTld.getBreakglassMode()).isTrue();
  }

  @Test
  void testSuccess_NoDiffNoBreakglassFlag_endsBreakglassMode() throws Exception {
    Tld tld = createTld("idns");
    persistResource(
        tld.asBuilder()
            .setIdnTables(ImmutableSet.of(JA, UNCONFUSABLE_LATIN, EXTENDED_LATIN))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("zeta", "alpha", "gamma", "beta"))
            .setBreakglassMode(true)
            .build());
    File tldFile = tmpDir.resolve("idns.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "idns.yaml"));
    runCommandForced("--input=" + tldFile);
    Tld updatedTld = Tld.get("idns");
    assertThat(updatedTld.getBreakglassMode()).isFalse();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "Breakglass mode removed from TLD: idns");
  }

  @Test
  void testSuccess_noDiffBreakglassFlag_continuesBreakglassMode() throws Exception {
    Tld tld = createTld("idns");
    persistResource(
        tld.asBuilder()
            .setIdnTables(ImmutableSet.of(JA, UNCONFUSABLE_LATIN, EXTENDED_LATIN))
            .setAllowedFullyQualifiedHostNames(ImmutableSet.of("zeta", "alpha", "gamma", "beta"))
            .setBreakglassMode(true)
            .build());
    File tldFile = tmpDir.resolve("idns.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "idns.yaml"));
    runCommandForced("--input=" + tldFile, "-b");
    Tld updatedTld = Tld.get("idns");
    assertThat(updatedTld.getBreakglassMode()).isTrue();
    assertAboutLogs()
        .that(logHandler)
        .hasLogAtLevelWithMessage(INFO, "TLD YAML file contains no new changes");
  }

  @Test
  void testFailure_noBreakglassFlag_inBreakglassMode() throws Exception {
    Tld tld = createTld("tld");
    persistResource(tld.asBuilder().setBreakglassMode(true).build());
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> runCommandForced("--input=" + tldFile));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "Changes can not be applied since TLD is in breakglass mode but the breakglass flag"
                + " was not used");
  }

  @Test
  void testSuccess_dryRunOnCreate_noChanges() throws Exception {
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile, "--dryrun");
    assertThrows(TldNotFoundException.class, () -> Tld.get("tld"));
  }

  @Test
  void testSuccess_dryRunOnUpdate_noChanges() throws Exception {
    Tld tld = createTld("tld");
    assertThat(tld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
    File tldFile = tmpDir.resolve("tld.yaml").toFile();
    Files.asCharSink(tldFile, UTF_8).write(loadFile(getClass(), "tld.yaml"));
    runCommandForced("--input=" + tldFile, "-d");
    Tld notUpdatedTld = Tld.get("tld");
    assertThat(notUpdatedTld.getCreateBillingCost()).isEqualTo(Money.of(USD, 13));
  }
}
