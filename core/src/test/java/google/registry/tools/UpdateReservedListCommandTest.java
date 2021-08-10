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
import static com.google.common.truth.Truth8.assertThat;
import static google.registry.model.registry.label.ReservationType.FULLY_BLOCKED;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.TestDataHelper.loadFile;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import google.registry.model.registry.label.ReservedList;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDao;
import java.io.File;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link UpdateReservedListCommand}. */
class UpdateReservedListCommandTest
    extends CreateOrUpdateReservedListCommandTestCase<UpdateReservedListCommand> {

  @BeforeEach
  void beforeEach() {
    populateInitialReservedListInDatabase(true);
  }

  private void populateInitialReservedListInDatabase(boolean shouldPublish) {
    persistReservedList(
        new ReservedList.Builder()
            .setName("xn--q9jyb4c_common-reserved")
            .setReservedListMapFromLines(ImmutableList.of("helicopter,FULLY_BLOCKED"))
            .setCreationTimestamp(START_OF_TIME)
            .setShouldPublish(shouldPublish)
            .build());
  }

  private void populateInitialReservedListInCloudSql(boolean shouldPublish) {
    ReservedListDao.save(
        createCloudSqlReservedList(
            "xn--q9jyb4c_common-reserved",
            fakeClock.nowUtc(),
            shouldPublish,
            ImmutableMap.of(
                "helicopter", ReservedListEntry.create("helicopter", FULLY_BLOCKED, ""))));
  }

  @Test
  void testSuccess() throws Exception {
    runSuccessfulUpdateTest("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);
  }

  @Test
  void testSuccess_unspecifiedNameDefaultsToFileName() throws Exception {
    runSuccessfulUpdateTest("--input=" + reservedTermsPath);
  }

  @Test
  void testSuccess_shouldPublish_setToFalseCorrectly() throws Exception {
    runSuccessfulUpdateTest("--input=" + reservedTermsPath, "--should_publish=false");
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getShouldPublish()).isFalse();
  }

  @Test
  void testSuccess_shouldPublish_doesntOverrideFalseIfNotSpecified() throws Exception {
    populateInitialReservedListInDatabase(false);
    runCommandForced("--input=" + reservedTermsPath);
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getShouldPublish()).isFalse();
  }

  private void runSuccessfulUpdateTest(String... args) throws Exception {
    runCommandForced(args);
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
    assertThat(reservedList.getReservationInList("baddies")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("ford")).hasValue(FULLY_BLOCKED);
    assertThat(reservedList.getReservationInList("helicopter")).isEmpty();
  }

  @Test
  void testFailure_reservedListDoesntExist() {
    String errorMessage =
        "Could not update reserved list xn--q9jyb4c_poobah because it doesn't exist.";
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommand("--force", "--name=xn--q9jyb4c_poobah", "--input=" + reservedTermsPath));
    assertThat(thrown).hasMessageThat().contains(errorMessage);
  }

  @Test
  void testSaveToCloudSql_succeeds() throws Exception {
    populateInitialReservedListInCloudSql(true);
    runCommandForced("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);
    verifyXnq9jyb4cInDatastore();
    verifyXnq9jyb4cInCloudSql();
  }

  @Test
  void testSaveToCloudSql_succeedsEvenPreviousListNotExist() throws Exception {
    // Note that, during the dual-write phase, we always save the reserved list to Cloud SQL without
    // checking if there is a list with same name. This is to backfill the existing list in Cloud
    // Datastore when we update it.
    runCommandForced("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);
    verifyXnq9jyb4cInDatastore();
    assertThat(ReservedListDao.checkExists("xn--q9jyb4c_common-reserved")).isTrue();
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

    assertThat(command.prompt()).contains("Update ReservedList@xn--q9jyb4c_common-reserved");
  }
}
