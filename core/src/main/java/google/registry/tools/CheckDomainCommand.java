// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

import static com.google.common.base.Strings.isNullOrEmpty;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Multimap;
import com.google.template.soy.data.SoyMapData;
import google.registry.config.RegistryConfig.Config;
import google.registry.tools.soy.DomainCheckSoyInfo;
import java.util.Collection;
import java.util.List;
import javax.inject.Inject;

/** A command to execute a domain check EPP command (including cost of a 1 year create). */
@Parameters(separators = " =", commandDescription = "Check domain availability")
final class CheckDomainCommand extends NonMutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description =
          "Client ID of the registrar to execute the command as, otherwise the registry registrar")
  String clientId;

  @Parameter(
      description = "List of domains to check.",
      required = true)
  private List<String> mainParameters;

  @Parameter(
      names = {"-t", "--token"},
      description = "Allocation token to use in the command, if desired")
  private String allocationToken;

  @Inject
  @Config("registryAdminClientId")
  String registryAdminClientId;

  @Override
  void initEppToolCommand() {
    // Default clientId to the registry registrar account if otherwise unspecified.
    if (clientId == null) {
      clientId = registryAdminClientId;
    }

    Multimap<String, String> domainNameMap = validateAndGroupDomainNamesByTld(mainParameters);
    for (Collection<String> values : domainNameMap.asMap().values()) {
      setSoyTemplate(DomainCheckSoyInfo.getInstance(), DomainCheckSoyInfo.DOMAINCHECK);
      SoyMapData soyMapData = new SoyMapData("domainNames", values);
      if (!isNullOrEmpty(allocationToken)) {
        soyMapData.put("allocationToken", allocationToken);
      }
      addSoyRecord(clientId, soyMapData);
    }
  }
}
