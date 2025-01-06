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
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import google.registry.model.tld.label.ReservedList;
import google.registry.model.tld.label.ReservedList.ReservedListEntry;
import java.io.File;
import java.io.IOException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Base class for common testing setup for create and update commands for Reserved Lists.
 *
 * @param <T> command type
 */
abstract class CreateOrUpdateReservedListCommandTestCase<
        T extends CreateOrUpdateReservedListCommand>
    extends CommandTestCase<T> {

  String reservedTermsPath;
  private String invalidReservedTermsPath;

  @BeforeEach
  void beforeEachCreateOrUpdateReservedListCommandTestCase() throws IOException {
    File reservedTermsFile = tmpDir.resolve("xn--q9jyb4c_common-reserved.txt").toFile();
    File invalidReservedTermsFile = tmpDir.resolve("reserved-terms-wontparse.csv").toFile();
    String reservedTermsCsv =
        loadFile(CreateOrUpdateReservedListCommandTestCase.class, "example_reserved_terms.csv");
    Files.asCharSink(reservedTermsFile, UTF_8).write(reservedTermsCsv);
    Files.asCharSink(invalidReservedTermsFile, UTF_8)
        .write("sdfgagmsdgs,sdfgsd\nasdf234tafgs,asdfaw\n\n");
    reservedTermsPath = reservedTermsFile.getPath();
    invalidReservedTermsPath = invalidReservedTermsFile.getPath();
    command.printStream = System.out;
  }

  @Test
  void testFailure_fileDoesntExist() {
    ParameterException thrown =
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--name=xn--q9jyb4c_common-reserved",
                    "--input=" + reservedTermsPath + "-nonexistent"));
    assertThat(thrown).hasMessageThat().contains("--input not found");
  }

  @Test
  void testFailure_fileDoesntParse() {
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=xn--q9jyb4c_common-reserved", "--input=" + invalidReservedTermsPath));
    assertThat(thrown).hasMessageThat().contains("No enum constant");
  }

  @Test
  void testFailure_invalidLabel_includesFullDomainName() throws Exception {
    Files.asCharSink(new File(invalidReservedTermsPath), UTF_8)
        .write("example.tld,FULLY_BLOCKED\n\n");
    IllegalArgumentException thrown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=xn--q9jyb4c_common-reserved", "--input=" + invalidReservedTermsPath));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Label example.tld must not be a multi-level domain name");
  }

  ReservedList createCloudSqlReservedList(
      String name,
      DateTime creationTime,
      ImmutableMap<String, ReservedListEntry> labelsToEntries) {
    return new ReservedList.Builder()
        .setName(name)
        .setCreationTimestamp(creationTime)
        .setReservedListMap(labelsToEntries)
        .build();
  }
}
