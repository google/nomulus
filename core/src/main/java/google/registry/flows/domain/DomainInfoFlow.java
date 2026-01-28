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
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyOptionalAuthInfo;
import static google.registry.flows.domain.DomainFlowUtils.addSecDnsExtensionIfPresent;
import static google.registry.flows.domain.DomainFlowUtils.handleFeeRequest;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.net.InternetDomainName;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.MutatingFlow;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.flows.custom.DomainInfoFlowCustomLogic;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.AfterValidationParameters;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.BeforeResponseParameters;
import google.registry.flows.custom.DomainInfoFlowCustomLogic.BeforeResponseReturnData;
import google.registry.model.domain.Domain;
import google.registry.model.domain.DomainCommand.Info;
import google.registry.model.domain.DomainCommand.Info.HostsRequest;
import google.registry.model.domain.DomainInfoData;
import google.registry.model.domain.bulktoken.BulkTokenExtension;
import google.registry.model.domain.bulktoken.BulkTokenResponseExtension;
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
import google.registry.persistence.IsolationLevel;
import google.registry.persistence.PersistenceModule;
import google.registry.util.Clock;
import jakarta.inject.Inject;
import java.util.Optional;
import org.joda.time.DateTime;

/**
 * An EPP flow that returns information about a domain.
 *
 * <p>The registrar that owns the domain, and any registrar presenting a valid authInfo for the
 * domain, will get a rich result with all the domain's fields. All other requests will be answered
 * with a minimal result containing only basic information about the domain.
 *
 * <p>This implements {@link MutatingFlow} instead of {@link TransactionalFlow} as a workaround so
 * that the common workflow of "create domain, then immediately get domain info" does not run into
 * replication lag issues where the info command claims the domain does not exist.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
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
@IsolationLevel(PersistenceModule.TransactionIsolationLevel.TRANSACTION_REPEATABLE_READ)
public final class DomainInfoFlow implements MutatingFlow {

  @Inject ExtensionManager extensionManager;
  @Inject ResourceCommand resourceCommand;
  @Inject EppInput eppInput;
  @Inject Optional<AuthInfo> authInfo;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject DomainInfoFlowCustomLogic flowCustomLogic;
  @Inject DomainPricingLogic pricingLogic;
  @Inject @Superuser boolean isSuperuser;

  @Inject
  DomainInfoFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.register(FeeInfoCommandExtensionV06.class, BulkTokenExtension.class);
    flowCustomLogic.beforeValidation();
    validateRegistrarIsLoggedIn(registrarId);
    extensionManager.validate();
    DateTime now = clock.nowUtc();
    Domain domain = loadAndVerifyExistence(Domain.class, targetId, now);
    verifyOptionalAuthInfo(authInfo, domain);
    flowCustomLogic.afterValidation(
        AfterValidationParameters.newBuilder().setDomain(domain).build());
    HostsRequest hostsRequest = ((Info) resourceCommand).getHostsRequest();
    // Registrars can only see a few fields on unauthorized domains.
    // This is a policy decision that is left up to us by the rfcs.
    DomainInfoData.Builder infoBuilder =
        DomainInfoData.newBuilder()
            .setDomainName(domain.getDomainName())
            .setRepoId(domain.getRepoId())
            .setCurrentSponsorRegistrarId(domain.getCurrentSponsorRegistrarId())
            .setStatusValues(domain.getStatusValues())
            .setNameservers(
                hostsRequest.requestDelegated() ? domain.loadNameserverHostNames() : null)
            .setCreationTime(domain.getCreationTime())
            .setLastEppUpdateTime(domain.getLastEppUpdateTime())
            .setRegistrationExpirationTime(domain.getRegistrationExpirationTime())
            .setLastTransferTime(domain.getLastTransferTime());

    // If authInfo is non-null, then the caller is authorized to see the full information since we
    // will have already verified the authInfo is valid.
    if (registrarId.equals(domain.getCurrentSponsorRegistrarId()) || authInfo.isPresent()) {
      infoBuilder
          .setSubordinateHosts(
              hostsRequest.requestSubordinate() ? domain.getSubordinateHosts() : null)
          .setCreationRegistrarId(domain.getCreationRegistrarId())
          .setLastEppUpdateRegistrarId(domain.getLastEppUpdateRegistrarId())
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

  private ImmutableList<ResponseExtension> getDomainResponseExtensions(Domain domain, DateTime now)
      throws EppException {
    ImmutableList.Builder<ResponseExtension> extensions = new ImmutableList.Builder<>();
    addSecDnsExtensionIfPresent(extensions, domain.getDsData());
    ImmutableSet<GracePeriodStatus> gracePeriodStatuses = domain.getGracePeriodStatuses();
    if (!gracePeriodStatuses.isEmpty()) {
      extensions.add(RgpInfoExtension.create(gracePeriodStatuses));
    }
    Optional<BulkTokenExtension> bulkPricingInfo =
        eppInput.getSingleExtension(BulkTokenExtension.class);
    if (bulkPricingInfo.isPresent()) {
      // Bulk pricing info was requested.
      if (isSuperuser || registrarId.equals(domain.getCurrentSponsorRegistrarId())) {
        // Only show bulk pricing info to owning registrar or superusers
        extensions.add(BulkTokenResponseExtension.create(domain.getCurrentBulkToken()));
      }
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
          false,
          tm().loadByKey(domain.getAutorenewBillingEvent()));
      extensions.add(builder.build());
    }
    return extensions.build();
  }
}
