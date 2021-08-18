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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.model.tld.Registries.assertTldExists;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import java.nio.file.Files;
import org.joda.money.CurrencyUnit;

/** Command to create a {@link PremiumList} on Database. */
@Parameters(separators = " =", commandDescription = "Create a PremiumList in Database.")
public class CreatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Parameter(
      names = {"-o", "--override"},
      description = "Override restrictions on premium list naming")
  boolean override;

  @Parameter(
      names = {"-c", "--currency"},
      description = "CurrencyUnit for the list",
      required = true)
  String currencyUnit;

  @Override
  protected String prompt() throws Exception {
    currency = CurrencyUnit.of(currencyUnit);
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    checkArgument(
        !PremiumListDao.getLatestRevision(name).isPresent(),
        "A premium list already exists by this name");
    if (!override) {
      assertTldExists(
          name,
          "Premium names must match the name of the TLD they are intended to be used on"
              + " (unless --override is specified), yet TLD %s does not exist");
    }
    inputData = Files.readAllLines(inputFile, UTF_8);
    return String.format("Create new premium list for %s?", name);
  }
}
