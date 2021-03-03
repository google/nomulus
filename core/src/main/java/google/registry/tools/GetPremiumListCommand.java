// Copyright 2021 The Nomulus Authors. All Rights Reserved.
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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Streams;
import google.registry.model.registry.label.PremiumList.PremiumListEntry;
import google.registry.model.registry.label.PremiumListDualDao;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/** Retrieves and prints one or more premium lists. */
@Parameters(separators = " =", commandDescription = "Show one or more premium lists")
public class GetPremiumListCommand implements CommandWithRemoteApi {

  @Parameter(description = "Name(s) of the premium list(s) to retrieve", required = true)
  private List<String> mainParameters;

  @Override
  public void run() throws Exception {
    for (String premiumListName : mainParameters) {
      if (PremiumListDualDao.exists(premiumListName)) {
        System.out.printf(
            "%s:\n%s\n",
            premiumListName,
            Streams.stream(PremiumListDualDao.loadAllPremiumListEntries(premiumListName))
                .sorted(Comparator.comparing(PremiumListEntry::getLabel))
                .map(PremiumListEntry::toString)
                .collect(Collectors.joining("\n")));
      } else {
        System.out.println(String.format("No list found with name %s.", premiumListName));
      }
    }
  }
}
