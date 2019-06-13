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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import google.registry.model.registry.label.ReservedList;

/**
 * Command to delete a {@link ReservedList} in Datastore. This command will fail if the reserved
 * list is currently in use on a tld.
 */
@Parameters(separators = " =", commandDescription = "Deletes a ReservedList in Datastore.")
final class DeleteReservedListCommand extends MutatingCommand {

  @Parameter(
      names = {"-n", "--name"},
      description = "The name of the reserved list to delete.",
      required = true)
  private String name;

  @Override
  protected void init() {
    checkArgument(
        ReservedList.get(name).isPresent(),
        "Cannot delete the reserved list %s because it doesn't exist.",
        name);
    ReservedList existing = ReservedList.get(name).get();
    ImmutableSet<String> tldsUsedOn = existing.getReferencingTlds();
    checkArgument(
        tldsUsedOn.isEmpty(),
        "Cannot delete reserved list because it is used on these tld(s): %s",
        Joiner.on(", ").join(tldsUsedOn));
    stageEntityChange(existing, null);
  }
}
