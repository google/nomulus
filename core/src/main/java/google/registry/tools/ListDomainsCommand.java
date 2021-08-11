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
import static google.registry.model.tld.Registries.getTldsOfType;
import static google.registry.util.CollectionUtils.isNullOrEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import google.registry.model.tld.Registry.TldType;
import google.registry.tools.server.ListDomainsAction;
import java.util.List;

/** Command to list all second-level domains on specified TLD(s). */
@Parameters(separators = " =", commandDescription = "List domains on TLD(s).")
final class ListDomainsCommand extends ListObjectsCommand {

  @Parameter(
      names = {"-t", "--tld", "--tlds"},
      description = "Comma-delimited list of TLDs to list domains on; defaults to all REAL TLDs.")
  private List<String> tlds;

  @Parameter(
      names = {"-n", "--limit"},
      description = "Max number of domains to list, most recent first; defaults to no limit."
  )
  private int maxDomains = Integer.MAX_VALUE;

  @Override
  String getCommandPath() {
    return ListDomainsAction.PATH;
  }

  /** Returns a map of parameters to be sent to the server (in addition to the usual ones). */
  @Override
  ImmutableMap<String, Object> getParameterMap() {
    // Default to all REAL TLDs if not specified.
    if (isNullOrEmpty(tlds)) {
      tlds = getTldsOfType(TldType.REAL).asList();
    }
    String tldsParam = Joiner.on(',').join(tlds);
    checkArgument(tldsParam.length() < 1024, "Total length of TLDs is too long for URL parameter");
    return ImmutableMap.of("tlds", tldsParam, "limit", maxDomains);
  }
}
