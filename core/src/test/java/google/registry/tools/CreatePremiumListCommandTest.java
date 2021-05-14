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
import static google.registry.testing.DatabaseHelper.createTld;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.io.Files;
import google.registry.model.registry.Registry;
import google.registry.schema.tld.PremiumListDao;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreatePremiumListCommand}. */
class CreatePremiumListCommandTest<C extends CreatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Registry registry;

  @BeforeEach
  void beforeEach() {
    registry = createRegistry(TLD_TEST, null);
  }

  @Test
  void verify_registryIsSetUpCorrectly() {
    // ensure that no premium list is created before running the command
    // this check also implicitly verifies the TLD is successfully created;
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST).isPresent()).isFalse();
  }

  @Test
  void commandRun_successCreateList() throws Exception {
    runCommandForced("--name=" + TLD_TEST, "--input=" + premiumTermsPath);
    assertThat(registry.getTld().toString()).isEqualTo(TLD_TEST);
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
  }

  @Test
  // since the old entity is always null and file cannot be empty, the prompt will NOT be "No entity
  // changes to apply."
  void commandInit_successStageNewEntity() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    command.inputFile = Paths.get(premiumTermsPath);
    command.init();
    assertThat(command.prompt()).contains("Create PremiumList@");
    assertThat(command.prompt()).contains(String.format("name=%s", TLD_TEST));
  }

  @Test
  void commandInit_successStageNewEntityWithOverride() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String alterTld = "override";
    command.inputFile = Paths.get(premiumTermsPath);
    command.override = true;
    command.name = alterTld;
    command.init();
    assertThat(command.prompt()).contains("Create PremiumList@");
    assertThat(command.prompt()).contains(String.format("name=%s", alterTld));
  }

  @Test
  void commandInit_failureNoInputFile() {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    assertThrows(NullPointerException.class, command::init);
  }

  @Test
  void commandInit_failurePremiumListAlreadyExists() {
    String randomStr = "random";
    createTld(randomStr);
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    command.name = randomStr;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown).hasMessageThat().isEqualTo("A premium list already exists by this name");
  }

  @Test
  void commandInit_failureMismatchedTldFileName_noOverride() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String fileName = "random";
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", fileName));
    Files.write(new byte[0], tmpPath.toFile());
    command.inputFile = tmpPath;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
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
  void commandInit_failureMismatchedTldName_noOverride() {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String fileName = "random";
    command.name = fileName;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
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
  void commandInit_failureUseEmptyFile() throws Exception {
    CreatePremiumListCommand command = new CreatePremiumListCommand();
    String fileName = "empty";
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", fileName));
    Files.write(new byte[0], tmpPath.toFile());
    command.inputFile = tmpPath;
    command.name = TLD_TEST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("The Cloud SQL schema requires exactly one currency");
  }
}
