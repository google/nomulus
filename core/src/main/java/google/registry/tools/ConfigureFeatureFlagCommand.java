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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.ImmutableSortedMap;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureName;
import google.registry.model.common.FeatureFlag.FeatureStatus;
import google.registry.tools.params.TransitionListParameter.FeatureStatusTransitions;
import java.util.List;
import java.util.Optional;
import org.joda.time.DateTime;

/** Command for creating and updating {@link FeatureFlag} objects. */
@Parameters(separators = " =", commandDescription = "Create or update a feature flag.")
public class ConfigureFeatureFlagCommand extends MutatingCommand {

  @Parameter(description = "Feature flag name(s) to create or update", required = true)
  private List<FeatureName> mainParameters;

  @Parameter(
      names = "--status_map",
      converter = FeatureStatusTransitions.class,
      validateWith = FeatureStatusTransitions.class,
      description =
          "Comma-delimited list of feature status transitions effective on specific dates, of the"
              + " form <time>=<status>[,<time>=<status>]* where each status represents the status"
              + " of the feature flag.",
      required = true)
  private ImmutableSortedMap<DateTime, FeatureStatus> featureStatusTransitions;

  @Override
  protected void init() throws Exception {
    for (FeatureName name : mainParameters) {
      Optional<FeatureFlag> oldFlag = FeatureFlag.getUncached(name);
      FeatureFlag.Builder newFlagBuilder =
          new FeatureFlag().asBuilder().setFeatureName(name).setStatusMap(featureStatusTransitions);
      stageEntityChange(oldFlag.orElse(null), newFlagBuilder.build());
    }
  }
}
