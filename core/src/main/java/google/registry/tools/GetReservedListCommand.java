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
import com.google.appengine.repackaged.com.google.common.collect.Streams;
import google.registry.model.registry.label.ReservedList.ReservedListEntry;
import google.registry.model.registry.label.ReservedListDao;
import java.util.Comparator;
import java.util.stream.Collectors;

/** Retrieves and prints one or more reserved lists. */
@Parameters(separators = " =", commandDescription = "Show one or more reserved lists")
public class GetReservedListCommand implements CommandWithRemoteApi {

  @Parameter(
      names = {"-n", "--name"},
      description = "The name of this reserved list",
      required = true)
  String reservedListName;

  @Override
  public void run() throws Exception {
    if (ReservedListDao.getLatestRevision(reservedListName).isPresent()) {
      System.out.printf(
          "%s\n",
          Streams.stream(
                  ReservedListDao.getLatestRevision(reservedListName)
                      .get()
                      .getReservedListEntries()
                      .values())
              .sorted(Comparator.comparing(ReservedListEntry::getLabel))
              .map(ReservedListEntry::toString)
              .collect(Collectors.joining("\n")));
    } else {
      System.err.printf("No list found with name %s.%n", reservedListName);
    }
  }
}
