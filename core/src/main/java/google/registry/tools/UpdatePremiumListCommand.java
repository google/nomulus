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
import static google.registry.model.tld.label.PremiumListUtils.parseToPremiumList;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import java.nio.file.Files;
import java.util.Optional;

/** Command to safely update {@link PremiumList} in Database for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Database.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Parameter(
      names = {"-u", "--upsert"},
      description = "Create the premium list if it does not already exist.")
  boolean upsert;

  // Indicates if there is a new change made by this command
  private boolean newChange = false;

  @Override
  protected String prompt() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    Optional<PremiumList> existingList = PremiumListDao.getLatestRevision(name);
    checkArgument(
        upsert || existingList.isPresent(),
        "Could not update premium list %s because it doesn't exist",
        name);
    inputData = Files.readAllLines(inputFile, UTF_8);
    checkArgument(!inputData.isEmpty(), "New premium list data cannot be empty");
    // If the list already exists in the database, pass its persisted currency so that all entries
    // are validated against it. Otherwise (when creating a new list via --upsert), pass null so
    // that parseToPremiumList infers the currency from the input file entries.
    PremiumList updatedPremiumList =
        parseToPremiumList(
            name, existingList.map(PremiumList::getCurrency).orElse(null), inputData, clock.now());
    currency = updatedPremiumList.getCurrency();
    if (existingList.isEmpty()) {
      newChange = true;
      return String.format(
          "Create new premium list for %s?\n New List: %s", name, updatedPremiumList);
    }
    if (!existingList
        .get()
        .getLabelsToPrices()
        .entrySet()
        .equals(updatedPremiumList.getLabelsToPrices().entrySet())) {
      newChange = true;
      return String.format(
          "Update premium list for %s?\n Old List: %s\n New List: %s",
          name, existingList.get(), updatedPremiumList);
    } else {
      return String.format(
          "This update contains no changes to the premium list for %s.\n List Contents: %s",
          name, existingList.get());
    }
  }

  @Override
  protected boolean dontRunCommand() {
    return dryRun || !newChange;
  }
}
