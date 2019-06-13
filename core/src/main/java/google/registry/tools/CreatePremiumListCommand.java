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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableMap;
import google.registry.model.registry.label.PremiumList;
import google.registry.tools.server.CreatePremiumListAction;

/** Command to create a {@link PremiumList} on Datastore. */
@Parameters(separators = " =", commandDescription = "Create a PremiumList in Datastore.")
public class CreatePremiumListCommand extends CreateOrUpdatePremiumListCommand {

  @Parameter(
      names = {"-o", "--override"},
      description = "Override restrictions on premium list naming")
  boolean override;

  /** Returns the path to the servlet task. */
  @Override
  public String getCommandPath() {
    return CreatePremiumListAction.PATH;
  }

  @Override
  ImmutableMap<String, ?> getParameterMap() {
    if (override) {
      return ImmutableMap.of("override", override);
    } else {
      return ImmutableMap.of();
    }
  }

}
