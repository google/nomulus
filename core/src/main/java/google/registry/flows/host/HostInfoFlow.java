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

package google.registry.flows.host;

import static google.registry.flows.FlowUtils.validateRegistrarIsLoggedIn;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.model.EppResourceUtils.isLinked;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.persistence.transaction.TransactionManagerUtil.transactIfJpaTm;

import com.google.common.collect.ImmutableSet;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.Flow;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.domain.DomainBase;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.host.HostInfoData;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import google.registry.util.Clock;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that returns information about a host.
 *
 * <p>The returned information included IP addresses, if any, and details of the host's most recent
 * transfer if it has ever been transferred. Any registrar can see the information for any host.
 *
 * @error {@link google.registry.flows.FlowUtils.NotLoggedInException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link HostFlowUtils.HostNameNotLowerCaseException}
 * @error {@link HostFlowUtils.HostNameNotNormalizedException}
 * @error {@link HostFlowUtils.HostNameNotPunyCodedException}
 */
@ReportingSpec(ActivityReportField.HOST_INFO)
public final class HostInfoFlow implements Flow {

  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Clock clock;
  @Inject EppResponse.Builder responseBuilder;
  @Inject HostInfoFlow() {}

  @Override
  public EppResponse run() throws EppException {
    extensionManager.validate();  // There are no legal extensions for this flow.
    validateRegistrarIsLoggedIn(registrarId);
    validateHostName(targetId);
    DateTime now = clock.nowUtc();
    HostResource host = loadAndVerifyExistence(HostResource.class, targetId, now);
    ImmutableSet.Builder<StatusValue> statusValues = new ImmutableSet.Builder<>();
    statusValues.addAll(host.getStatusValues());
    if (isLinked(host.createVKey(), now)) {
      statusValues.add(StatusValue.LINKED);
    }
    HostInfoData.Builder hostInfoDataBuilder = HostInfoData.newBuilder();
    // Hosts transfer with their superordinate domains, so for hosts with a superordinate domain,
    // the client id, last transfer time, and pending transfer status need to be read off of it. If
    // there is no superordinate domain, the host's own values for these fields will be correct.
    if (host.isSubordinate()) {
      DomainBase superordinateDomain =
          transactIfJpaTm(
              () -> tm().loadByKey(host.getSuperordinateDomain()).cloneProjectedAtTime(now));
      hostInfoDataBuilder
          .setCurrentSponsorClientId(superordinateDomain.getCurrentSponsorRegistrarId())
          .setLastTransferTime(host.computeLastTransferTime(superordinateDomain));
      if (superordinateDomain.getStatusValues().contains(StatusValue.PENDING_TRANSFER)) {
        statusValues.add(StatusValue.PENDING_TRANSFER);
      }
    } else {
      hostInfoDataBuilder
          .setCurrentSponsorClientId(host.getPersistedCurrentSponsorRegistrarId())
          .setLastTransferTime(host.getLastTransferTime());
    }
    return responseBuilder
        .setResData(
            hostInfoDataBuilder
                .setFullyQualifiedHostName(host.getHostName())
                .setRepoId(host.getRepoId())
                .setStatusValues(statusValues.build())
                .setInetAddresses(host.getInetAddresses())
                .setCreationClientId(host.getCreationRegistrarId())
                .setCreationTime(host.getCreationTime())
                .setLastEppUpdateClientId(host.getLastEppUpdateRegistrarId())
                .setLastEppUpdateTime(host.getLastEppUpdateTime())
                .build())
        .build();
  }
}
