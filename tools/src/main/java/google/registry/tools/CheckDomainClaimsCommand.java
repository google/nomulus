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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import google.registry.config.RegistryConfig.Config;
import google.registry.model.domain.DomainCommand;
import google.registry.model.eppinput.EppExtensions;
import google.registry.model.eppinput.EppInput;
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

    Multimap<String, String> domainNameMap =
        validateAndGroupDomainNamesByTld(ImmutableList.copyOf(mainParameters));
    for (Collection<String> values : domainNameMap.asMap().values()) {
      DomainCommand.Check checkCommand = new DomainCommand.Check();
      checkCommand.setTargetIds(ImmutableList.copyOf(values));
      addEppInput(
          clientId,
          EppInput.create(EppInput.Check.create(checkCommand), EppExtensions.launchCheckClaims())
              .withClTrid("RegistryTool"));
    }
  }
}
