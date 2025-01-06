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

import static com.google.common.base.Preconditions.checkArgument;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.tld.label.PremiumListUtils;
import java.nio.file.Files;

/** Command to safely update {@link PremiumList} in Database for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Database.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Parameter(
      names = {"-d", "--dry_run"},
      description = "Does not execute the entity mutation")
  boolean dryRun;

  @Parameter(
      names = {"--build_environment"},
      description =
          "DO NOT USE THIS FLAG ON THE COMMAND LINE! This flag indicates the command is being run"
              + " by the build environment tools. This flag should never be used by a human user"
              + " from the command line.")
  boolean buildEnv;

  // Indicates if there is a new change made by this command
  private boolean newChange = false;

  @Override
  protected String prompt() throws Exception {
    checkArgument(
        !RegistryToolEnvironment.get().equals(RegistryToolEnvironment.PRODUCTION) || buildEnv,
        "The --build_environment flag must be used when running update_premium_list in production");
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    PremiumList existingList =
        PremiumListDao.getLatestRevision(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update premium list %s because it doesn't exist", name)));
    inputData = Files.readAllLines(inputFile, UTF_8);
    checkArgument(!inputData.isEmpty(), "New premium list data cannot be empty");
    currency = existingList.getCurrency();
    PremiumList updatedPremiumList = PremiumListUtils.parseToPremiumList(name, currency, inputData);
    if (!existingList
        .getLabelsToPrices()
        .entrySet()
        .equals(updatedPremiumList.getLabelsToPrices().entrySet())) {
      newChange = true;
      return String.format(
          "Update premium list for %s?\n Old List: %s\n New List: %s",
          name, existingList, updatedPremiumList);
    } else {
      return String.format(
          "This update contains no changes to the premium list for %s.\n List Contents: %s",
          name, existingList);
    }
  }

  @Override
  protected boolean dontRunCommand() {
    return dryRun || !newChange;
  }
}
