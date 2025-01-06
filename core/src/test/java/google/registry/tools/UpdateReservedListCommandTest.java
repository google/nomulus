// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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
import static google.registry.model.tld.label.ReservationType.FULLY_BLOCKED;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;
import google.registry.model.tld.label.ReservedList;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateReservedListCommand}. */
class UpdateReservedListCommandTest
    extends CreateOrUpdateReservedListCommandTestCase<UpdateReservedListCommand> {

  @BeforeEach
  void beforeEach() {
    populateInitialReservedListInDatabase();
  }

  private void populateInitialReservedListInDatabase() {
    persistReservedList(
        new ReservedList.Builder()
            .setName("xn--q9jyb4c_common-reserved")
            .setReservedListMapFromLines(ImmutableList.of("helicopter,FULLY_BLOCKED"))
            .setCreationTimestamp(START_OF_TIME)
            .build());
  }

  @Test
  void testSuccess() throws Exception {
    runSuccessfulUpdateTest("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);
  }

  @Test
  void testSuccess_unspecifiedNameDefaultsToFileName() throws Exception {
    runSuccessfulUpdateTest("--input=" + reservedTermsPath);
  }

  private void runSuccessfulUpdateTest(String... args) throws Exception {
    runCommandForced(args);
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
    assertThat(reservedList.getReservationInList("baddies")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("ford")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("helicopter")).isEmpty();
    assertInStdout("Update reserved list for xn--q9jyb4c_common-reserved?");
    assertInStdout("helicopter: helicopter,FULLY_BLOCKED -> null");
    assertInStdout("baddies: null -> baddies,FULLY_BLOCKED");
    assertInStdout("ford: null -> ford,FULLY_BLOCKED # random comment");
  }

  @Test
  void testFailure_reservedListDoesntExist() {
    String errorMessage =
        "Could not update reserved list xn--q9jyb4c_poobah because it doesn't exist.";
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=xn--q9jyb4c_poobah", "--input=" + reservedTermsPath));
    assertThat(thrown).hasMessageThat().contains(errorMessage);
  }

  @Test
  void testSuccess_noChanges() throws Exception {
    File reservedTermsFile = tmpDir.resolve("xn--q9jyb4c_common-reserved.txt").toFile();
    // after running runCommandForced, the file now contains "helicopter,FULLY_BLOCKED" which is
    // populated in the @BeforeEach method of this class and the rest of terms from
    // example_reserved_terms.csv, which are populated in the @BeforeEach of
    // CreateOrUpdateReservedListCommandTestCases.java.
    runCommandForced("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);

    // set up to write content already in file
    String reservedTermsCsv =
        loadFile(CreateOrUpdateReservedListCommandTestCase.class, "example_reserved_terms.csv");
    Files.asCharSink(reservedTermsFile, UTF_8).write(reservedTermsCsv);
    reservedTermsPath = reservedTermsFile.getPath();
    // create a command instance and assign its input
    UpdateReservedListCommand command = new UpdateReservedListCommand();
    command.input = Paths.get(reservedTermsPath);
    // run again with terms from example_reserved_terms.csv
    command.init();

    assertThat(command.prompt()).isEqualTo("No entity changes to apply.");
  }

  @Test
  void testSuccess_withChanges() throws Exception {
    // changes come from example_reserved_terms.csv, which are populated in @BeforeEach of
    // CreateOrUpdateReservedListCommandTestCases.java
    UpdateReservedListCommand command = new UpdateReservedListCommand();
    command.input = Paths.get(reservedTermsPath);
    command.init();

    assertThat(command.prompt()).contains("Update reserved list for xn--q9jyb4c_common-reserved?");
    assertThat(command.prompt()).contains("helicopter: helicopter,FULLY_BLOCKED -> null");
    assertThat(command.prompt()).contains("baddies: null -> baddies,FULLY_BLOCKED");
    assertThat(command.prompt()).contains("ford: null -> ford,FULLY_BLOCKED # random comment");
  }

  @Test
  void testSuccess_dryRun() throws Exception {
    runCommandForced("--input=" + reservedTermsPath, "--dry_run");
    assertThat(command.prompt()).contains("Update reserved list for xn--q9jyb4c_common-reserved?");
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(1);
    assertThat(reservedList.getReservationInList("helicopter")).hasValue(FULLY_BLOCKED);
  }

  @Test
  void testFailure_runCommandOnProduction_noFlag() throws Exception {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandInEnvironment(
                    RegistryToolEnvironment.PRODUCTION,
                    "--name=xn--q9jyb4c_common-reserved",
                    "--input=" + reservedTermsPath));
    assertThat(thrown.getMessage())
        .isEqualTo(
            "The --build_environment flag must be used when running update_reserved_list in"
                + " production");
  }

  @Test
  void testSuccess_runCommandOnProduction_buildEnvFlag() throws Exception {
    runCommandInEnvironment(
        RegistryToolEnvironment.PRODUCTION,
        "--name=xn--q9jyb4c_common-reserved",
        "--input=" + reservedTermsPath,
        "--build_environment",
        "-f");
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
    assertThat(reservedList.getReservationInList("baddies")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("ford")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("helicopter")).isEmpty();
    assertInStdout("Update reserved list for xn--q9jyb4c_common-reserved?");
    assertInStdout("helicopter: helicopter,FULLY_BLOCKED -> null");
    assertInStdout("baddies: null -> baddies,FULLY_BLOCKED");
    assertInStdout("ford: null -> ford,FULLY_BLOCKED # random comment");
  }
}
