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

import static google.registry.flows.FlowUtils.validateClientIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.verifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.domain.DomainFlowUtils.addSecDnsExtensionIfPresent;
import static google.registry.flows.domain.DomainFlowUtils.handleFeeRequest;
import static google.registry.flows.domain.DomainFlowUtils.loadForeignKeyedDesignatedContacts;
import static google.registry.model.EppResourceUtils.loadByForeignKey;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainInfoFlowCustomLogic;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.BeforeResponseReturnData;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.DomainCommand.Info;
import google.registry.model.domain.DomainCommand.Info.HostsRequest;
import google.registry.model.domain.DomainInfoData;
import google.registry.model.domain.fee06.FeeInfoCommandExtensionV06;
import google.registry.model.domain.fee06.FeeInfoResponseExtensionV06;
import google.registry.model.domain.rgp.GracePeriodStatus;
import google.registry.model.domain.rgp.RgpInfoExtension;
import google.registry.model.eppcommon.AuthInfo;
import google.registry.model.eppinput.EppInput;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.EppResponse.ResponseExtension;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.util.Clock;
import java.util.Optional;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that returns information about a domain.
 *
 * <p>The registrar that owns the domain, and any registrar presenting a valid authInfo for the
 * domain, will get a rich result with all of the domain's fields. All other requests will be
 * answered with a minimal result containing only basic information about the domain.
 *
 * @error {@link google.registry.flows.FlowUtils.UnknownCurrencyEppException}
 * @error {@link google.registry.flows.ResourceFlowUtils.BadAuthInfoForResourceException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link DomainFlowUtils.BadPeriodUnitException}
 * @error {@link DomainFlowUtils.CurrencyUnitMismatchException}
 * @error {@link DomainFlowUtils.FeeChecksDontSupportPhasesException}
 * @error {@link DomainFlowUtils.RestoresAreAlwaysForOneYearException}
 * @error {@link DomainFlowUtils.TransfersAreAlwaysForOneYearException}
 */
@ReportingSpec(ActivityReportField.DOMAIN_INFO)
public final class DomainInfoFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject ResourceCommand resourceCommand;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainInfoFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;

  @Inject
  DomainInfoFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(FeeInfoCommandExtensionV06.class);
    flowCustomLogic.beforeValidation();
    extensionManager.validate();
    validateClientIsLoggedIn(clientId);
    DateTime now = clock.nowUtc();
    DomainBase domain = verifyExistence(
        DomainBase.class, targetId, loadByForeignKey(DomainBase.class, targetId, now));
    verifyOptionalAuthInfo(authInfo, domain);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder().setDomain(domain).build());
    // Prefetch all referenced resources. Calling values() blocks until loading is done.
    tm().loadByKeys(domain.getNameservers());
    tm().loadByKeys(domain.getReferencedContacts());
    // Registrars can only see a few fields on unauthorized domains.
    // This is a policy decision that is left up to us by the rfcs.
    DomainInfoData.Builder infoBuilder =
        DomainInfoData.newBuilder()
            .setFullyQualifiedDomainName(domain.getDomainName())
            .setRepoId(domain.getRepoId())
            .setCurrentSponsorClientId(domain.getCurrentSponsorClientId())
            .setRegistrant(tm().loadByKey(domain.getRegistrant()).getContactId());
    // If authInfo is non-null, then the caller is authorized to see the full information since we
    // will have already verified the authInfo is valid.
    if (clientId.equals(domain.getCurrentSponsorClientId()) || authInfo.isPresent()) {
      HostsRequest hostsRequest = ((Info) resourceCommand).getHostsRequest();
      infoBuilder
          .setStatusValues(domain.getStatusValues())
          .setContacts(loadForeignKeyedDesignatedContacts(domain.getContacts()))
          .setNameservers(hostsRequest.requestDelegated() ? domain.loadNameserverHostNames() : null)
          .setSubordinateHosts(
              hostsRequest.requestSubordinate() ? domain.getSubordinateHosts() : null)
          .setCreationClientId(domain.getCreationClientId())
          .setCreationTime(domain.getCreationTime())
          .setLastEppUpdateClientId(domain.getLastEppUpdateClientId())
          .setLastEppUpdateTime(domain.getLastEppUpdateTime())
          .setRegistrationExpirationTime(domain.getRegistrationExpirationTime())
          .setLastTransferTime(domain.getLastTransferTime())
          .setAuthInfo(domain.getAuthInfo());
    }
    BeforeResponseReturnData responseData =
        flowCustomLogic.beforeResponse(
            BeforeResponseParameters.newBuilder()
                .setDomain(domain)
                .setResData(infoBuilder.build())
                .setResponseExtensions(getDomainResponseExtensions(domain, now))
                .build());
    return responseBuilder
        .setResData(responseData.resData())
        .setExtensions(responseData.responseExtensions())
        .build();
  }

  private ImmutableList<ResponseExtension> getDomainResponseExtensions(
      DomainBase domain, DateTime now) throws EppException {
    ImmutableList.Builder<ResponseExtension> extensions = new ImmutableList.Builder<>();
    addSecDnsExtensionIfPresent(extensions, domain.getDsData());
    ImmutableSet<GracePeriodStatus> gracePeriodStatuses = domain.getGracePeriodStatuses();
    if (!gracePeriodStatuses.isEmpty()) {
      extensions.add(RgpInfoExtension.create(gracePeriodStatuses));
    }
    Optional<FeeInfoCommandExtensionV06> feeInfo =
        eppInput.getSingleExtension(FeeInfoCommandExtensionV06.class);
    if (feeInfo.isPresent()) { // Fee check was requested.
      FeeInfoResponseExtensionV06.Builder builder = new FeeInfoResponseExtensionV06.Builder();
      handleFeeRequest(
          feeInfo.get(),
          builder,
          InternetDomainName.from(targetId),
          Optional.of(domain),
          null,
          now,
          pricingLogic,
          Optional.empty(),
          false);
      extensions.add(builder.build());
    }
    return extensions.build();
  }
}
