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
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import google.registry.model.tld.label.PremiumListUtils;
import java.nio.file.Files;
import java.util.Optional;

/** Command to safely update {@link PremiumList} in Database for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Database.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Override
  protected String prompt() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    Optional<PremiumList> list = PremiumListDao.getLatestRevision(name);
    checkArgument(
        list.isPresent(),
        String.format("Could not update premium list %s because it doesn't exist.", name));
    inputData = Files.readAllLines(inputFile, UTF_8);
    checkArgument(!inputData.isEmpty(), "New premium list data cannot be empty");
    currency = list.get().getCurrency();
    PremiumList updatedPremiumList = PremiumListUtils.parseToPremiumList(name, currency, inputData);
    return String.format(
        "Update premium list for %s?\n Old List: %s\n New List: %s",
        name, list, updatedPremiumList);
  }
}
