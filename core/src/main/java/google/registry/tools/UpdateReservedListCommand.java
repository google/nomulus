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
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.ReservedList;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

/** Command to safely update {@link ReservedList}. */
@Parameters(separators = " =", commandDescription = "Update a ReservedList.")
final class UpdateReservedListCommand extends CreateOrUpdateReservedListCommand {

  @Parameter(
      names = {"-u", "--upsert"},
      description = "Create the reserved list if it does not already exist.")
  boolean upsert;

  // indicates if there is a new change made by this command
  private boolean newChange = true;

  @Override
  protected String prompt() throws Exception {
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(input) : name;
    Optional<ReservedList> existingReservedList = ReservedList.get(name);
    checkArgument(
        upsert || existingReservedList.isPresent(),
        "Could not update reserved list %s because it doesn't exist.",
        name);
    List<String> allLines = Files.readAllLines(input, UTF_8);
    if (existingReservedList.isEmpty()) {
      newChange = true;
      reservedList =
          new ReservedList.Builder()
              .setName(name)
              .setReservedListMapFromLines(allLines)
              .setCreationTimestamp(clock.now())
              .build();
      return String.format(
          "Create new reserved list for %s?\n%s\nreservedListMap=%s\n",
          name, reservedList, outputReservedListEntries(reservedList));
    }
    ReservedList existing = existingReservedList.get();
    ReservedList.Builder updated = existing.asBuilder().setReservedListMapFromLines(allLines);
    reservedList = updated.build();
    boolean reservedListEntriesChanged =
        !existing.getReservedListEntries().equals(reservedList.getReservedListEntries());
    if (!reservedListEntriesChanged) {
      newChange = false;
      return "No entity changes to apply.";
    }
    return String.format("Update reserved list for %s?\n", name)
        + prettyPrintEntityDeepDiff(
            existing.getReservedListEntries(), reservedList.getReservedListEntries());
  }

  @Override
  protected boolean dontRunCommand() {
    return dryRun || !newChange;
  }
}
