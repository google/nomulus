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

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.collect.Multimap;
import com.google.template.soy.data.SoyMapData;
import google.registry.config.RegistryConfig.Config;
import google.registry.tools.soy.DomainCheckClaimsSoyInfo;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.List;

/** A command to execute a domain check claims epp command. */
@Parameters(separators = " =", commandDescription = "Check claims on domain(s)")
final class CheckDomainClaimsCommand extends NonMutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description =
          "Client ID of the registrar to execute the command as, otherwise the registry registrar")
  String clientId;

  @Parameter(
      description = "Domain(s) to check.",
      required = true)
  private List<String> mainParameters;

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
      setSoyTemplate(
          DomainCheckClaimsSoyInfo.getInstance(), DomainCheckClaimsSoyInfo.DOMAINCHECKCLAIMS);
      addSoyRecord(clientId, new SoyMapData("domainNames", values));
    }
  }
}
