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
import static google.registry.model.tld.label.ReservationType.FULLY_BLOCKED;
import static google.registry.testing.DatabaseHelper.createTlds;
import static google.registry.testing.DatabaseHelper.persistReservedList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.tools.CreateReservedListCommand.INVALID_FORMAT_ERROR_MESSAGE;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.io.Files;
import google.registry.model.tld.Tld;
import google.registry.model.tld.label.ReservedList;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CreateReservedListCommand}. */
class CreateReservedListCommandTest
    extends CreateOrUpdateReservedListCommandTestCase<CreateReservedListCommand> {

  @BeforeEach
  void beforeEach() {
    createTlds("xn--q9jyb4c", "soy");
  }

  @Test
  void testSuccess() throws Exception {
    runCommandForced("--name=xn--q9jyb4c_common-reserved", "--input=" + reservedTermsPath);
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
    ReservedList reservedList = ReservedList.get("xn--q9jyb4c_common-reserved").get();
    assertThat(reservedList.getReservedListEntries()).hasSize(2);
    assertThat(reservedList.getReservedEntryForLabel("baddies").get().getValue())
        .isEqualTo(FULLY_BLOCKED);
    assertThat(reservedList.getReservedEntryForLabel("ford").get().getValue())
        .isEqualTo(FULLY_BLOCKED);
  }

  @Test
  void testSuccess_unspecifiedNameDefaultsToFileName() throws Exception {
    runCommandForced("--input=" + reservedTermsPath);
    assertThat(ReservedList.get("xn--q9jyb4c_common-reserved")).isPresent();
  }

  @Test
  void testFailure_reservedListWithThatNameAlreadyExists() {
    ReservedList rl = persistReservedList("xn--q9jyb4c_foo", "jones,FULLY_BLOCKED");
    persistResource(Tld.get("xn--q9jyb4c").asBuilder().setReservedLists(rl).build());
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=xn--q9jyb4c_foo", "--input=" + reservedTermsPath));
    assertThat(thrown).hasMessageThat().contains("A reserved list already exists by this name");
  }

  @Test
  void testNamingRules_commonReservedList() throws Exception {
    runCommandForced("--name=common_abuse-list", "--input=" + reservedTermsPath);
    assertThat(ReservedList.get("common_abuse-list")).isPresent();
  }

  @Test
  void testNamingRules_tldThatDoesNotExist_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("footld_reserved-list");
  }

  @Test
  void testNamingRules_tldThatDoesNotExist_failsWithoutOverride() {
    runNameTestExpectedFailure(
        "footld_reserved-list",
        "The name must be in the format {tld|common}_list-name, yet TLD footld does not exist");
  }

  @Test
  void testNamingRules_underscoreIsMissing_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("random-reserved-list");
  }

  @Test
  void testNamingRules_underscoreIsMissing_failsWithoutOverride() {
    runNameTestExpectedFailure("random-reserved-list", INVALID_FORMAT_ERROR_MESSAGE);
  }

  @Test
  void testNamingRules_secondHalfOfNameIsMissing_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("soy_");
  }

  @Test
  void testNamingRules_secondHalfOfNameIsMissing_failsWithoutOverride() {
    runNameTestExpectedFailure("soy_", INVALID_FORMAT_ERROR_MESSAGE);
  }

  @Test
  void testNamingRules_onlyTldIsSpecifiedAsName_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("soy");
  }

  @Test
  void testNamingRules_onlyTldIsSpecifiedAsName_failsWithoutOverride() {
    runNameTestExpectedFailure("soy", INVALID_FORMAT_ERROR_MESSAGE);
  }

  @Test
  void testNamingRules_commonAsListName_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("invalidtld_common");
  }

  @Test
  void testNamingRules_commonAsListName_failsWithoutOverride() {
    runNameTestExpectedFailure(
        "invalidtld_common",
        "The name must be in the format {tld|common}_list-name, yet TLD invalidtld does not exist");
  }

  @Test
  void testNamingRules_too_many_underscores_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("soy_buffalo_buffalo_buffalo");
  }

  @Test
  void testNamingRules_too_many_underscores_failsWithoutOverride() {
    runNameTestExpectedFailure("soy_buffalo_buffalo_buffalo", INVALID_FORMAT_ERROR_MESSAGE);
  }

  @Test
  void testNamingRules_withWeirdCharacters_succeedsWithOverride() throws Exception {
    runNameTestWithOverride("soy_$oy");
  }

  @Test
  void testNamingRules_withWeirdCharacters_failsWithoutOverride() {
    runNameTestExpectedFailure("soy_$oy", INVALID_FORMAT_ERROR_MESSAGE);
  }

  private void runNameTestExpectedFailure(String name, String expectedErrorMsg) {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () -> runCommandForced("--name=" + name, "--input=" + reservedTermsPath));
    assertThat(ReservedList.get(name)).isEmpty();
    assertThat(thrown).hasMessageThat().isEqualTo(expectedErrorMsg);
  }

  private void runNameTestWithOverride(String name) throws Exception {
    runCommandForced("--name=" + name, "--override", "--input=" + reservedTermsPath);
    assertThat(ReservedList.get(name)).isPresent();
  }

  @Test
  void testStageEntityChange_succeeds() throws Exception {
    CreateReservedListCommand command = new CreateReservedListCommand();
    // file content is populated in @BeforeEach of CreateOrUpdateReservedListCommandTestCase.java
    command.input = Paths.get(reservedTermsPath);
    command.init();
    assertThat(command.prompt())
        .contains(
            "reservedListMap=[(baddies,FULLY_BLOCKED), "
                + "(ford,FULLY_BLOCKED # random comment)]");
  }

  @Test
  void testStageEntityChange_succeedsWithEmptyFile() throws Exception {
    Path tmpPath = tmpDir.resolve("xn--q9jyb4c_common-tmp.txt");
    Files.write(new byte[0], tmpPath.toFile());
    CreateReservedListCommand command = new CreateReservedListCommand();
    command.input = tmpPath;
    command.init();
    assertThat(command.prompt()).contains("reservedListMap=[]");
  }
}
