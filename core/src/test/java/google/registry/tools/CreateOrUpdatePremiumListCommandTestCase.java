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
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.base.Ascii;
import com.google.common.io.Files;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.RegistryNotFoundException;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  protected String premiumTermsPath;
  protected static final String TLD_TEST = "prime";
  protected static final String TLD_FAIL = "failure";

  @BeforeEach
  void beforeEachCreateOrUpdateReservedListCommandTestCase() throws IOException {
    // set up for initial data
    File premiumTermsFile = tmpDir.resolve("prime.txt").toFile();
    String premiumTermsCsv = "doge,USD 2021";
    Files.asCharSink(premiumTermsFile, UTF_8).write(premiumTermsCsv);
    premiumTermsPath = premiumTermsFile.getPath();
  }

  @Test
  void testSuccess() throws Exception {
    persistResource(
        newRegistry("tmptld", Ascii.toUpperCase("tmptld"))
            .asBuilder()
            .setPremiumList(null)
            .build());
    Registry registry = Registry.get("tmptld");
    assertThat(registry).isNotNull();
    assertThat(registry.getTld().toString()).isEqualTo("tmptld");
  }

  @Test
  void test_tldFailure() throws Exception {
    assertThrows(
        RegistryNotFoundException.class, () -> assertThat(Registry.get(TLD_FAIL)).isNotNull());
  }
}
