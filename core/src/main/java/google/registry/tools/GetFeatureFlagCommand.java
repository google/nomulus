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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.fasterxml.jackson.databind.ObjectMapper;
import google.registry.model.common.FeatureFlag;
import google.registry.model.common.FeatureFlag.FeatureFlagNotFoundException;
import google.registry.model.common.FeatureFlag.FeatureName;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;

/** Command to show a {@link FeatureFlag}. */
@Parameters(separators = " =", commandDescription = "Show FeatureFlag record(s)")
public class GetFeatureFlagCommand implements Command {

  @Parameter(description = "Feature flag(s) to show", required = true)
  private List<FeatureName> mainParameters;

  @Inject ObjectMapper objectMapper;

  @Override
  public void run() throws Exception {
    // Don't use try-with-resources to manage standard output streams, closing the stream will
    // cause subsequent output to standard output or standard error to be lost
    // See: https://errorprone.info/bugpattern/ClosingStandardOutputStreams
    PrintStream printStream = new PrintStream(System.out, false, UTF_8);
    for (FeatureName featureFlag : mainParameters) {
      Optional<FeatureFlag> maybeFeatureFlag = FeatureFlag.getUncached(featureFlag);
      if (maybeFeatureFlag.isEmpty()) {
        throw new FeatureFlagNotFoundException(featureFlag);
      }
      printStream.println(objectMapper.writeValueAsString(maybeFeatureFlag.get()));
    }
  }
}
