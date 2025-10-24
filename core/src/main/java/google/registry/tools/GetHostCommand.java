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
import google.registry.model.ForeignKeyUtils;
import google.registry.model.host.Host;
import google.registry.util.DomainNameUtils;
import java.util.List;

/** Command to show one or more host resources. */
@Parameters(separators = " =", commandDescription = "Show host resource(s)")
final class GetHostCommand extends GetEppResourceCommand {

  @Parameter(
      description = "Fully qualified host name(s)",
      required = true)
  private List<String> mainParameters;

  @Override
  public void runAndPrint() {
    mainParameters.stream()
        .map(DomainNameUtils::canonicalizeHostname)
        .forEach(
            h ->
                printResource(
                    "Host", h, ForeignKeyUtils.loadResource(Host.class, h, readTimestamp)));
  }
}
