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

import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import google.registry.model.registry.Registry;
import google.registry.schema.tld.PremiumListDao;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdatePremiumListCommand}. */
class UpdatePremiumListCommandTest<C extends UpdatePremiumListCommand>
    extends CreateOrUpdatePremiumListCommandTestCase<C> {
  Registry registry;

  @BeforeEach
  void beforeEach() {
    registry = createRegistry(TLD_TEST, initialPremiumListData);
  }

  @Test
  void verify_registryIsSetUpCorrectly() {
    // ensure that no premium list is created before running the command
    assertThat(PremiumListDao.getLatestRevision(TLD_TEST).isPresent()).isTrue();
    // ensure that there's value in existing premium list;
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    ImmutableSet<String> entries = command.getExistingPremiumListEntry(TLD_TEST);
    assertThat(entries.size()).isEqualTo(1);
    // data from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    assertThat(entries.contains("doge,USD 9090.00")).isTrue();
  }

  @Test
  void commandInit_successStageNoEntityChange() throws Exception {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(premiumTermsPath);
    command.name = TLD_TEST;
    command.init();
    assertThat(command.prompt()).contains("No entity changes to apply.");
  }

  @Test
  void commandInit_successStageEntityChange() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "omg,JPY 1234";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    command.name = TLD_TEST;
    command.init();
    assertThat(command.prompt()).contains("Update PremiumList@");
  }

  @Test
  void commandRun_successUpdateList() throws Exception {
    File tmpFile = tmpDir.resolve(String.format("%s.txt", TLD_TEST)).toFile();
    String newPremiumListData = "eth,USD 9999";
    Files.asCharSink(tmpFile, UTF_8).write(newPremiumListData);

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    // data come from @beforeEach of CreateOrUpdatePremiumListCommandTestCase.java
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    ImmutableSet<String> entries = command.getExistingPremiumListEntry(TLD_TEST);
    assertThat(entries.size()).isEqualTo(1);
    // verify that list is updated; cannot use only string since price is formatted;
    assertThat(entries.contains("eth,USD 9999.00")).isTrue();
  }

  @Test
  void commandRun_successUpdateMultiLineList() throws Exception {
    File tmpFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    String premiumTerms = "foo,USD 9000\ndoge,USD 100\nelon,USD 2021";
    Files.asCharSink(tmpFile, UTF_8).write(premiumTerms);

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = Paths.get(tmpFile.getPath());
    runCommandForced("--name=" + TLD_TEST, "--input=" + command.inputFile);

    // assert all three lines from premiumTerms are added
    ImmutableSet<String> entries = command.getExistingPremiumListEntry(TLD_TEST);
    assertThat(entries.size()).isEqualTo(3);
    assertThat(entries.contains("foo,USD 9000.00")).isTrue();
    assertThat(entries.contains("doge,USD 100.00")).isTrue();
    assertThat(entries.contains("elon,USD 2021.00")).isTrue();
  }

  @Test
  void commandInit_failureUpdateEmptyList() throws Exception {
    Path tmpPath = tmpDir.resolve(String.format("%s.txt", TLD_TEST));
    Files.write(new byte[0], tmpPath.toFile());

    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.inputFile = tmpPath;
    command.name = TLD_TEST;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .contains("The Cloud SQL schema requires exactly one currency");
  }

  @Test
  void commandInit_failureNoPreviousVersion() {
    String fileName = "random";
    registry = createRegistry(fileName, null);
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.name = fileName;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }

  @Test
  void commandInit_failureNoInputFile() {
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    assertThrows(NullPointerException.class, command::init);
  }

  @Test
  void commandInit_failureTldFromNameDoesNotExist() {
    String fileName = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    command.name = fileName;
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }

  @Test
  void commandInit_failureTldFromInputFileDoesNotExist() {
    String fileName = "random";
    UpdatePremiumListCommand command = new UpdatePremiumListCommand();
    // using tld extracted from file name but this tld is not part of the registry
    command.inputFile =
        Paths.get(tmpDir.resolve(String.format("%s.txt", fileName)).toFile().getPath());
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, command::init);
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo(
            String.format("Could not update premium list %s because it doesn't exist.", fileName));
  }
}
