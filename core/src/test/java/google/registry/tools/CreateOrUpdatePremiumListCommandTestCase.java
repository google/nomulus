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
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.beust.jcommander.ParameterException;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import google.registry.testing.UriParameters;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  String premiumTermsPath;
  private String invalidPremiumTermsPath;

  @BeforeEach
  void beforeEachCreateOrUpdateReservedListCommandTestCase() throws IOException {
    File premiumTermsFile = tmpDir.resolve("xn--q9jyb4c_common-premium.txt").toFile();
    File invalidPremiumTermsFile = tmpDir.resolve("reserved-terms-wontparse.csv").toFile();
    String premiumTermsCsv =
        loadFile(CreateOrUpdateReservedListCommandTestCase.class, "example_premium_terms.csv");
    Files.asCharSink(premiumTermsFile, UTF_8).write(premiumTermsCsv);
    Files.asCharSink(invalidPremiumTermsFile, UTF_8)
        .write("sdfgagmsdgs,sdfgsd\nasdf234tafgs,asdfaw\n\n");
    premiumTermsPath = premiumTermsFile.getPath();
    invalidPremiumTermsPath = invalidPremiumTermsFile.getPath();
  }

  @Test
  void testFailure_fileDoesntExist() {
    assertThat(
        assertThrows(
            ParameterException.class,
            () ->
                runCommandForced(
                    "--name=xn--q9jyb4c_common-premium",
                    "--input=" + premiumTermsPath + "-nonexistent")))
        .hasMessageThat()
        .contains("-i not found");
  }
  @Test
  void testFailure_fileDoesntParse() {
    assertThat(
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runCommandForced(
                    "--name=xn--q9jyb4c_common-premium",
                    "--input=" + invalidPremiumTermsPath)))
        .hasMessageThat()
        .contains("No enum constant");
  }

  static String generateInputData(String premiumTermsPath) throws Exception {
    return Files.asCharSource(new File(premiumTermsPath), StandardCharsets.UTF_8).read();
  }

}
