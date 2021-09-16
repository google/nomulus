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

package google.registry.flows.domain;

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyTargetIdCount;
import static google.registry.flows.domain.DomainFlowUtils.checkAllowedAccessToTld;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainName;
import static google.registry.flows.domain.DomainFlowUtils.validateDomainNameWithIdnTables;
import static google.registry.flows.domain.DomainFlowUtils.verifyClaimsPeriodNotEnded;
import static google.registry.flows.domain.DomainFlowUtils.verifyNotInPredelegation;
import static google.registry.model.domain.launch.LaunchPhase.CLAIMS;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.config.RegistryConfig.Config;
import google.registry.flows.EppException;
import google.registry.flows.EppException.CommandUseErrorException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.domain.DomainCommand.Check;
import google.registry.model.domain.launch.LaunchCheckExtension;
import google.registry.model.domain.launch.LaunchCheckResponseExtension;
import google.registry.model.domain.launch.LaunchCheckResponseExtension.LaunchCheck;
import google.registry.model.domain.launch.LaunchCheckResponseExtension.LaunchCheckName;
import google.registry.model.domain.token.AllocationTokenExtension;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.model.tld.Registry;
import google.registry.model.tmch.ClaimsListDao;
import google.registry.util.Clock;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that checks whether domain labels are trademarked.
 *
 * @error {@link google.registry.flows.exceptions.TooManyResourceChecksException}
 * @error {@link DomainFlowUtils.BadCommandForRegistryPhaseException}
 * @error {@link DomainFlowUtils.ClaimsPeriodEndedException}
 * @error {@link DomainFlowUtils.NotAuthorizedForTldException}
 * @error {@link DomainFlowUtils.TldDoesNotExistException}
 * @error {@link DomainClaimsCheckNotAllowedWithAllocationTokens}
 */
@ReportingSpec(ActivityReportField.DOMAIN_CHECK)  // Claims check is a special domain check.
public final class DomainClaimsCheckFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject EppInput eppInput;
  @Inject ResourceCommand resourceCommand;
  @Inject @RegistrarId String registrarId;
  @Inject @Superuser boolean isSuperuser;
  @Inject Clock clock;
  @Inject @Config("maxChecks") int maxChecks;
  @Inject EppResponse.Builder responseBuilder;

  @Inject
  DomainClaimsCheckFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(LaunchCheckExtension.class, AllocationTokenExtension.class);
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    if (eppInput.getSingleExtension(AllocationTokenExtension.class).isPresent()) {
      throw new DomainClaimsCheckNotAllowedWithAllocationTokens();
    }
    ImmutableList<String> domainNames = ((Check) resourceCommand).getTargetIds();
    verifyTargetIdCount(domainNames, maxChecks);
    Set<String> seenTlds = new HashSet<>();
    ImmutableList.Builder<LaunchCheck> launchChecksBuilder = new ImmutableList.Builder<>();
    for (String domainName : ImmutableSet.copyOf(domainNames)) {
      InternetDomainName parsedDomain = validateDomainName(domainName);
      validateDomainNameWithIdnTables(parsedDomain);
      String tld = parsedDomain.parent().toString();
      // Only validate access to a TLD the first time it is encountered.
      if (seenTlds.add(tld)) {
        if (!isSuperuser) {
          checkAllowedAccessToTld(registrarId, tld);
          Registry registry = Registry.get(tld);
          DateTime now = clock.nowUtc();
          verifyNotInPredelegation(registry, now);
          verifyClaimsPeriodNotEnded(registry, now);
        }
      }
      Optional<String> claimKey = ClaimsListDao.get().getClaimKey(parsedDomain.parts().get(0));
      launchChecksBuilder.add(
          LaunchCheck.create(
              LaunchCheckName.create(claimKey.isPresent(), domainName), claimKey.orElse(null)));
    }
    return responseBuilder
        .setOnlyExtension(LaunchCheckResponseExtension.create(CLAIMS, launchChecksBuilder.build()))
        .build();
  }

  /** Claims checks are not allowed with allocation tokens. */
  static class DomainClaimsCheckNotAllowedWithAllocationTokens extends CommandUseErrorException {
    public DomainClaimsCheckNotAllowedWithAllocationTokens() {
      super("Claims checks are not allowed with allocation tokens");
    }
  }
}
