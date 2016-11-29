// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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
import com.google.common.collect.ImmutableMap;
import google.registry.tools.server.ListDomainsAction;
import java.util.List;

/** Command to list all second-level domains on specified TLD(s). */
@Parameters(separators = " =", commandDescription = "List domains on TLD(s).")
final class ListDomainsCommand extends ListObjectsCommand {

  @Parameter(
      names = {"-t", "--tld", "--tlds"},
      description = "Comma-delimited list of top-level domain(s) to list second-level domains of.",
      required = true)
  private List<String> tlds;

  @Override
  String getCommandPath() {
    return ListDomainsAction.PATH;
  }

  /** Returns a map of parameters to be sent to the server
   * (in addition to the usual ones). */
  @Override
  ImmutableMap<String, Object> getParameterMap() {
    String tldsParam = Joiner.on(',').join(tlds);
    checkArgument(tldsParam.length() < 1024, "Total length of TLDs is too long for URL parameter");
    return ImmutableMap.<String, Object>of("tlds", tldsParam);
  }
}
