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
import google.registry.model.tld.label.PremiumList;
import google.registry.model.tld.label.PremiumListDao;
import javax.annotation.Nullable;

/**
 * Command to delete a {@link PremiumList}. This command will fail if the premium list is currently
 * in use on a tld.
 */
@Parameters(separators = " =", commandDescription = "Delete a PremiumList.")
final class DeletePremiumListCommand extends ConfirmingCommand implements CommandWithRemoteApi {

  @Nullable PremiumList premiumList;

  @Parameter(
      names = {"-n", "--name"},
      description = "The name of the premium list to delete.",
      required = true)
  private String name;

  @Override
  protected void init() {
    checkArgument(
        PremiumListDao.getLatestRevision(name).isPresent(),
        "Cannot delete the premium list %s because it doesn't exist.",
        name);
    premiumList = PremiumListDao.getLatestRevision(name).get();
    ImmutableSet<String> tldsUsedOn = premiumList.getReferencingTlds();
    checkArgument(
        tldsUsedOn.isEmpty(),
        "Cannot delete premium list because it is used on these tld(s): %s",
        Joiner.on(", ").join(tldsUsedOn));
  }

  @Override
  protected String prompt() {
    return "You are about to delete the premium list: \n" + premiumList.getName();
  }

  @Override
  protected String execute() {
    PremiumListDao.delete(premiumList);
    return String.format("Deleted premium list '%s'.\n", premiumList.getName());
  }
}
