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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  protected static final String TLD_TEST = "prime";
  protected String premiumTermsPath;

  @BeforeEach
  void beforeEachCreateOrUpdateReservedListCommandTestCase() throws IOException {
    // set up for initial data
    File premiumTermsFile = tmpDir.resolve("prime.txt").toFile();
    String premiumTermsCsv = "foo,USD 2021";
    Files.asCharSink(premiumTermsFile, UTF_8).write(premiumTermsCsv);
    premiumTermsPath = premiumTermsFile.getPath();
  }
}
