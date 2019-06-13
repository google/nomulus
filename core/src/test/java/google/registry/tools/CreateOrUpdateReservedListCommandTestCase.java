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

import static google.registry.testing.JUnitBackports.assertThrows;
import static google.registry.testing.TestDataHelper.loadFile;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.ParameterException;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

/**
 * Base class for common testing setup for create and update commands for Reserved Lists.
 *
 * @param <T> command type
 */
public abstract class CreateOrUpdateReservedListCommandTestCase
    <T extends CreateOrUpdateReservedListCommand> extends CommandTestCase<T> {

  String reservedTermsPath;
  String invalidReservedTermsPath;

  @Before
  public void init() throws IOException {
    File reservedTermsFile = tmpDir.newFile("xn--q9jyb4c_common-reserved.txt");
    File invalidReservedTermsFile = tmpDir.newFile("reserved-terms-wontparse.csv");
    String reservedTermsCsv =
        loadFile(CreateOrUpdateReservedListCommandTestCase.class, "example_reserved_terms.csv");
    Files.asCharSink(reservedTermsFile, UTF_8).write(reservedTermsCsv);
    Files.asCharSink(invalidReservedTermsFile, UTF_8)
        .write("sdfgagmsdgs,sdfgsd\nasdf234tafgs,asdfaw\n\n");
    reservedTermsPath = reservedTermsFile.getPath();
    invalidReservedTermsPath = invalidReservedTermsFile.getPath();
  }

  @Test
  public void testFailure_fileDoesntExist() {
    assertThrows(
        ParameterException.class,
        () ->
            runCommandForced(
                "--name=xn--q9jyb4c-blah", "--input=" + reservedTermsPath + "-nonexistent"));
  }

  @Test
  public void testFailure_fileDoesntParse() {
    assertThrows(
        IllegalArgumentException.class,
        () -> runCommandForced("--name=xn--q9jyb4c-blork", "--input=" + invalidReservedTermsPath));
  }
}
