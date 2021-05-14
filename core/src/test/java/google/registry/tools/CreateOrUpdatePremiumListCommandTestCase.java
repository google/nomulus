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

import static google.registry.model.registry.Registry.TldState.GENERAL_AVAILABILITY;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.newRegistry;
import static google.registry.testing.DatabaseHelper.persistPremiumList;
import static google.registry.testing.DatabaseHelper.persistResource;
import static google.registry.util.DateTimeUtils.START_OF_TIME;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Files;
import google.registry.dns.writer.VoidDnsWriter;
import google.registry.model.pricing.StaticPremiumListPricingEngine;
import google.registry.model.registry.Registry;
import google.registry.model.registry.Registry.TldType;
import java.io.File;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  protected static final String TLD_TEST = "prime";
  protected String premiumTermsPath;
  protected String initialPremiumListData;

  @BeforeEach
  void beforeEachCreateOrUpdatePremiumListCommandTestCase() throws IOException {
    // initial set up for both CreatePremiumListCommand and UpdatePremiumListCommand test cases;
    initialPremiumListData = "doge,USD 9090";
    File premiumTermsFile = tmpDir.resolve(TLD_TEST + ".txt").toFile();
    Files.asCharSink(premiumTermsFile, UTF_8).write(initialPremiumListData);
    premiumTermsPath = premiumTermsFile.getPath();
  }

  Registry createRegistry(String tldStr, String premiumListInput) {
    Registry registry;
    if (premiumListInput != null) {
      registry =
          newRegistry(
              tldStr,
              Ascii.toUpperCase(tldStr),
              ImmutableSortedMap.of(START_OF_TIME, GENERAL_AVAILABILITY),
              TldType.TEST);
      persistPremiumList(tldStr, premiumListInput);
      persistResource(registry);
    } else {
      registry =
          new Registry.Builder()
              .setTldStr(tldStr)
              .setPremiumPricingEngine(StaticPremiumListPricingEngine.NAME)
              .setDnsWriters(ImmutableSet.of(VoidDnsWriter.NAME))
              .setPremiumList(null)
              .build();
      tm().transact(() -> tm().put(registry));
    }
    return registry;
  }
}
