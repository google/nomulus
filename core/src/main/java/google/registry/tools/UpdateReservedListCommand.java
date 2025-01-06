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
import static google.registry.util.DiffUtils.prettyPrintEntityDeepDiff;
import static google.registry.util.ListNamingUtils.convertFilePathToName;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Strings;
import google.registry.model.tld.label.ReservedList;
import java.nio.file.Files;
import java.util.List;

/** Command to safely update {@link ReservedList}. */
@Parameters(separators = " =", commandDescription = "Update a ReservedList.")
final class UpdateReservedListCommand extends CreateOrUpdateReservedListCommand {

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

  // indicates if there is a new change made by this command
  private boolean newChange = true;

  @Override
  protected String prompt() throws Exception {
    checkArgument(
        !RegistryToolEnvironment.get().equals(RegistryToolEnvironment.PRODUCTION) || buildEnv,
        "The --build_environment flag must be used when running update_reserved_list in"
            + " production");
    name = Strings.isNullOrEmpty(name) ? convertFilePathToName(input) : name;
    ReservedList existingReservedList =
        ReservedList.get(name)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        String.format(
                            "Could not update reserved list %s because it doesn't exist.", name)));
    List<String> allLines = Files.readAllLines(input, UTF_8);
    ReservedList.Builder updated =
        existingReservedList.asBuilder().setReservedListMapFromLines(allLines);
    reservedList = updated.build();
    boolean reservedListEntriesChanged =
        !existingReservedList
            .getReservedListEntries()
            .equals(reservedList.getReservedListEntries());
    if (!reservedListEntriesChanged) {
      newChange = false;
      return "No entity changes to apply.";
    }
    return String.format("Update reserved list for %s?\n", name)
        + prettyPrintEntityDeepDiff(
            existingReservedList.getReservedListEntries(), reservedList.getReservedListEntries());
  }

  @Override
  protected boolean dontRunCommand() {
    return dryRun || !newChange;
  }
}
