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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.util.CollectionUtils.findDuplicates;
import static google.registry.util.PreconditionsUtils.checkArgumentNotNull;
import static java.time.ZoneOffset.UTC;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Joiner;
import google.registry.flows.ResourceFlowUtils;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand;
import google.registry.model.domain.Period;
import google.registry.model.eppinput.EppExtensions;
import google.registry.model.eppinput.EppInput;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A command to renew domain(s) via EPP. */
@Parameters(separators = " =", commandDescription = "Renew domain(s) via EPP.")
final class RenewDomainCommand extends MutatingEppToolCommand {

  @Parameter(
      names = {"-c", "--client"},
      description =
          "The registrar to execute as and bill the renewal to; otherwise each domain's sponsoring"
              + " registrar. Renewals by non-sponsoring registrars require --superuser as well.")
  String clientId;

  @Parameter(
      names = {"-p", "--period"},
      description = "Number of years to renew the registration for (defaults to 1).")
  private int period = 1;

  @Parameter(description = "Names of the domains to renew.", required = true)
  private List<String> mainParameters;

  @Parameter(
      names = {"--reason"},
      description = "Reason for the change.")
  String reason;

  @Parameter(
      names = {"--registrar_request"},
      description = "Whether the change was requested by a registrar.",
      arity = 1)
  Boolean requestedByRegistrar;

  @Override
  protected void initMutatingEppToolCommand() throws Exception {
    Set<String> duplicates = findDuplicates(mainParameters);
    checkArgument(
        duplicates.isEmpty(),
        "Duplicate domain arguments found: '%s'",
        Joiner.on(", ").join(duplicates));
    checkArgument(period < 10, "Cannot renew domains for 10 or more years");
    Instant now = clock.now();
    for (String domainName : mainParameters) {
      Domain domain = ResourceFlowUtils.loadAndVerifyExistence(Domain.class, domainName, now);

      if (reason != null) {
        checkArgumentNotNull(
            requestedByRegistrar, "--registrar_request is required when --reason is specified");
      }

      DomainCommand.Renew.Builder renewBuilder =
          new DomainCommand.Renew.Builder()
              .setTargetId(domain.getDomainName())
              .setPeriod(Period.create(period, Period.Unit.YEARS))
              .setCurrentExpirationDate(
                  domain.getRegistrationExpirationTime().atZone(UTC).toLocalDate());

      addEppInput(
          isNullOrEmpty(clientId) ? domain.getCurrentSponsorRegistrarId() : clientId,
          EppInput.create(
                  EppInput.Renew.create(renewBuilder.build()),
                  EppExtensions.toolMetadata(reason, requestedByRegistrar))
              .withClTrid("RegistryTool"));
    }
  }
}
