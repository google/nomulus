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
import static google.registry.flows.ResourceFlowUtils.checkLinkedDomains;
import static google.registry.flows.ResourceFlowUtils.loadAndVerifyExistence;
import static google.registry.flows.ResourceFlowUtils.verifyNoDisallowedStatuses;
import static google.registry.flows.ResourceFlowUtils.verifyResourceOwnership;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.eppoutput.Result.Code.SUCCESS_WITH_ACTION_PENDING;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;

import com.google.common.collect.ImmutableSet;
import google.registry.batch.AsyncTaskEnqueuer;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.ExtensionManager;
import google.registry.flows.FlowModule.RegistrarId;
import google.registry.flows.FlowModule.Superuser;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.TransactionalFlow;
import google.registry.flows.annotations.ReportingSpec;
import google.registry.model.EppResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppcommon.StatusValue;
import google.registry.model.eppcommon.Trid;
import google.registry.model.eppoutput.EppResponse;
import google.registry.model.eppoutput.Result;
import google.registry.model.host.HostHistory;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.model.reporting.HistoryEntry.Type;
import google.registry.model.reporting.IcannReportingTypes.ActivityReportField;
import javax.inject.Inject;
import org.joda.time.DateTime;

/**
 * An EPP flow that deletes a host.
 *
 * <p>Hosts that are in use by any domain cannot be deleted. The flow may return immediately if a
 * quick smoke check determines that deletion is impossible due to an existing reference. However, a
 * successful delete will always be asynchronous, as all existing domains must be checked for
 * references to the host before the deletion is allowed to proceed. A poll message will be written
 * with the success or failure message when the process is complete.
 *
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceDoesNotExistException}
 * @error {@link google.registry.flows.ResourceFlowUtils.ResourceNotOwnedException}
 * @error {@link google.registry.flows.exceptions.ResourceStatusProhibitsOperationException}
 * @error {@link google.registry.flows.exceptions.ResourceToDeleteIsReferencedException}
 * @error {@link HostFlowUtils.HostNameNotLowerCaseException}
 * @error {@link HostFlowUtils.HostNameNotNormalizedException}
 * @error {@link HostFlowUtils.HostNameNotPunyCodedException}
 */
@ReportingSpec(ActivityReportField.HOST_DELETE)
public final class HostDeleteFlow implements TransactionalFlow {

  private static final ImmutableSet<StatusValue> DISALLOWED_STATUSES =
      ImmutableSet.of(
          StatusValue.CLIENT_DELETE_PROHIBITED,
          StatusValue.PENDING_DELETE,
          StatusValue.SERVER_DELETE_PROHIBITED);

  private static final DnsQueue dnsQueue = DnsQueue.create();

  @Inject ExtensionManager extensionManager;
  @Inject @RegistrarId String registrarId;
  @Inject @TargetId String targetId;
  @Inject Trid trid;
  @Inject @Superuser boolean isSuperuser;
  @Inject HostHistory.Builder historyBuilder;
  @Inject AsyncTaskEnqueuer asyncTaskEnqueuer;
  @Inject EppResponse.Builder responseBuilder;

  @Inject
  HostDeleteFlow() {}

  @Override
  public final EppResponse run() throws EppException {
    extensionManager.register(MetadataExtension.class);
    extensionManager.validate();
    validateRegistrarIsLoggedIn(registrarId);
    DateTime now = tm().getTransactionTime();
    validateHostName(targetId);
    checkLinkedDomains(targetId, now, HostResource.class, DomainBase::getNameservers);
    HostResource existingHost = loadAndVerifyExistence(HostResource.class, targetId, now);
    verifyNoDisallowedStatuses(existingHost, DISALLOWED_STATUSES);
    if (!isSuperuser) {
      // Hosts transfer with their superordinate domains, so for hosts with a superordinate domain,
      // the client id, needs to be read off of it.
      EppResource owningResource =
          existingHost.isSubordinate()
              ? tm().loadByKey(existingHost.getSuperordinateDomain()).cloneProjectedAtTime(now)
              : existingHost;
      verifyResourceOwnership(registrarId, owningResource);
    }
    HistoryEntry.Type historyEntryType;
    Result.Code resultCode;
    HostResource newHost;
    if (tm().isOfy()) {
      asyncTaskEnqueuer.enqueueAsyncDelete(
          existingHost, tm().getTransactionTime(), registrarId, trid, isSuperuser);
      newHost = existingHost.asBuilder().addStatusValue(StatusValue.PENDING_DELETE).build();
      historyEntryType = Type.HOST_PENDING_DELETE;
      resultCode = SUCCESS_WITH_ACTION_PENDING;
    } else {
      newHost = existingHost.asBuilder().setStatusValues(null).setDeletionTime(now).build();
      if (existingHost.isSubordinate()) {
        dnsQueue.addHostRefreshTask(existingHost.getHostName());
        tm().update(
                tm().loadByKey(existingHost.getSuperordinateDomain())
                    .asBuilder()
                    .removeSubordinateHost(existingHost.getHostName())
                    .build());
      }
      historyEntryType = Type.HOST_DELETE;
      resultCode = SUCCESS;
    }
    historyBuilder.setType(historyEntryType).setHost(newHost);
    tm().insert(historyBuilder.build());
    tm().update(newHost);
    return responseBuilder.setResultFromCode(resultCode).build();
  }
}
