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

import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.registry.label.PremiumList;
import google.registry.model.registry.label.PremiumListDualDao;
import google.registry.schema.tld.PremiumListUtils;
import java.nio.file.Files;
import java.util.List;

/** Command to safely update {@link PremiumList} in Datastore for a given TLD. */
@Parameters(separators = " =", commandDescription = "Update a PremiumList in Datastore.")
class UpdatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Override
  protected void init() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(inputFile) : name;
    PremiumList existingPremiumList =
        PremiumListDualDao.getLatestRevision(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update premium list %s because it doesn't exist.", name)));
    allLines = Files.readAllLines(inputFile, UTF_8);
    inputLineCount = allLines.size();
    PremiumList premiumList = PremiumListUtils.parseToPremiumList(name, allLines);

    // TODO: determine the kind of change being staged
    stageEntityChange(
        existingPremiumList,
        premiumList
    );

  }

}
