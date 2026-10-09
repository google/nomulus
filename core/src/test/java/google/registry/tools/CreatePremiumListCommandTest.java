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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.io.Files;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.testing.DatabaseHelper;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreatePremiumListCommand}. */
class CreatePremiumListCommandTest<C extends CreatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Tld registry;

  @BeforeEach
  void beforeEach() {
    registry = createTld(TLD_TEST, null, null);
  }

  @Test
  void verify_registryIsSetUpCorrectly() {
    // ensure that no premium list is created before running the command
    // this check also implicitly verifies the TLD is successfully created;
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST).isPresent()).isFalse();
  }

  @Test
  void commandRun_successCreateList() throws Exception {
    runCommandForced("--name=" + TLD_TEST, "--input=" + premiumTermsPath, "--currency=USD");
    assertThat(registry.getTld().toString()).isEqualTo(TLD_TEST);
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
  }

  @Test
  // since the old entity is always null and file cannot be empty, the prompt will NOT be "No entity
  // changes to apply."
  void commandPrompt_successStageNewEntity() throws Exception {
    command.inputFile = Paths.get(premiumTermsPath);
    command.currencyUnit = "USD";
    command.prompt();
    assertThat(command.prompt()).isEqualTo("Create new premium list for prime?");
  }

  @Test
  void commandPrompt_successStageNewEntityWithOverride() throws Exception {
    String alterTld = "override";
    command.inputFile = Paths.get(premiumTermsPath);
    command.override = true;
    command.name = alterTld;
    command.currencyUnit = "USD";
    command.prompt();
    assertThat(command.prompt()).isEqualTo("Create new premium list for override?");
  }

  @Test
  void commandPrompt_failureNoInputFile() {
    assertThrows(NullPointerException.class, command::prompt);
  }

  @Test
  void commandPrompt_failureEmptyInputFile() throws Exception {
    Path emptyFile = tmpDir.resolve(TLD_TEST + "_empty.txt");
    Files.write(new byte[0], emptyFile.toFile());
    command.name = TLD_TEST;
    command.inputFile = emptyFile;
    command.currencyUnit = "USD";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown).hasMessageThat().isEqualTo("New premium list data cannot be empty");
  }

  @Test
  void commandPrompt_failureMalformedInputFile() throws Exception {
    Path malformedFile = tmpDir.resolve(TLD_TEST + "_malformed_prompt.txt");
    Files.asCharSink(malformedFile.toFile(), UTF_8).write("malformed_line_without_comma\n");
    command.name = TLD_TEST;
    command.inputFile = malformedFile;
    command.currencyUnit = "USD";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown).hasMessageThat().contains("Could not parse line in premium list");
  }

  @Test
  void commandRun_failureMalformedInputFile() throws Exception {
    File malformedFile = tmpDir.resolve(TLD_TEST + "_malformed.txt").toFile();
    Files.asCharSink(malformedFile, UTF_8).write("malformed_line_without_comma\n");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            runCommandForced(
                "--name=" + TLD_TEST, "--input=" + malformedFile.getPath(), "--currency=USD"));
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST)).isEmpty();
  }

  @Test
  void commandRun_failureCurrencyMismatchInInputFile() throws Exception {
    File mismatchedFile = tmpDir.resolve(TLD_TEST + "_jpy.txt").toFile();
    Files.asCharSink(mismatchedFile, UTF_8).write("doge,JPY 9090\n");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=" + TLD_TEST, "--input=" + mismatchedFile.getPath(), "--currency=USD"));
    assertThat(thrown).hasMessageThat().isEqualTo("The currency unit must be USD");
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST)).isEmpty();
  }

  @Test
  void commandPrompt_failurePremiumListAlreadyExists() {
    String randomStr = "random";
    DatabaseHelper.createTld(randomStr);
    command.name = randomStr;
    command.currencyUnit = "USD";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown).hasMessageThat().isEqualTo("A premium list already exists by this name");
  }

  @Test
  void commandPrompt_failureMismatchedTldFileName_noOverride() throws Exception {
    String fileName = "random";
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", fileName));
    Files.write(new byte[0], tmpPath.toFile());
    command.inputFile = tmpPath;
    command.currencyUnit = "USD";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Premium names must match the name of the TLD they are "
                    + "intended to be used on (unless --override is specified), "
                    + "yet TLD %s does not exist",
                fileName));
  }

  @Test
  void commandPrompt_failureMismatchedTldName_noOverride() {
    String fileName = "random";
    command.name = fileName;
    command.currencyUnit = "USD";
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::prompt);
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            String.format(
                "Premium names must match the name of the TLD they are "
                    + "intended to be used on (unless --override is specified), "
                    + "yet TLD %s does not exist",
                fileName));
  }

  @Test
  void testDryRun_doesNotCreateList() throws Exception {
    runCommandForced(
        "--name=" + TLD_TEST, "--input=" + premiumTermsPath, "--currency=USD", "--dry_run");
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST)).isEmpty();
  }

  @Test
  void testFailure_runCommandOnProduction_noFlag() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--name=" + TLD_TEST,
                    "--input=" + premiumTermsPath,
                    "--currency=USD"));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("The --build_environment flag must be used when running in production");
  }

  @Test
  void testSuccess_runCommandOnProduction_buildEnvFlag() throws Exception {
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--name=" + TLD_TEST,
        "--input=" + premiumTermsPath,
        "--currency=USD",
        "--build_environment",
        "-f");
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST)).isPresent();
  }
}
